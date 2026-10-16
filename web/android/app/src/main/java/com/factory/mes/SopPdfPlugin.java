package com.factory.mes;

import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.Matrix;
import android.graphics.pdf.PdfRenderer;
import android.os.ParcelFileDescriptor;
import android.util.Base64;
import com.getcapacitor.JSObject;
import com.getcapacitor.Plugin;
import com.getcapacitor.PluginCall;
import com.getcapacitor.PluginMethod;
import com.getcapacitor.annotation.CapacitorPlugin;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/** PdfRenderer is provided by Android since API 21; no WebView PDF features needed. */
@CapacitorPlugin(name = "SopPdf")
public class SopPdfPlugin extends Plugin {
    private static final class Document {
        final File file;
        final PdfRenderer renderer;
        Document(File file, PdfRenderer renderer) { this.file = file; this.renderer = renderer; }
        void close() { try { renderer.close(); } finally { file.delete(); } }
    }
    private final Map<String, Document> documents = new HashMap<>();

    @PluginMethod public synchronized void open(PluginCall call) {
        File file = null;
        ParcelFileDescriptor descriptor = null;
        try {
            String data = call.getString("data");
            if (data == null || data.length() > 45 * 1024 * 1024) throw new IllegalArgumentException("PDF 文件过大或为空");
            if (documents.size() >= 4) throw new IllegalStateException("请先关闭当前 PDF");
            byte[] bytes = Base64.decode(data, Base64.DEFAULT);
            if (bytes.length > 32 * 1024 * 1024) throw new IllegalArgumentException("PDF 超过 32 MB");
            file = File.createTempFile("mes-sop-", ".pdf", getContext().getCacheDir());
            try (FileOutputStream output = new FileOutputStream(file)) { output.write(bytes); }
            descriptor = ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY);
            PdfRenderer renderer = new PdfRenderer(descriptor);
            descriptor = null; // PdfRenderer now owns the descriptor.
            String handle = UUID.randomUUID().toString();
            documents.put(handle, new Document(file, renderer));
            JSObject result = new JSObject(); result.put("handle", handle); result.put("pages", renderer.getPageCount());
            call.resolve(result);
        } catch (Exception error) {
            if (descriptor != null) try { descriptor.close(); } catch (Exception ignored) { }
            if (file != null) file.delete();
            call.reject("PDF 打开失败：文件损坏、加密或格式不受支持", error);
        }
    }

    @PluginMethod public synchronized void render(PluginCall call) {
        Bitmap bitmap = null;
        try {
            Document document = documents.get(call.getString("handle"));
            Integer number = call.getInt("page");
            if (document == null || number == null || number < 0 || number >= document.renderer.getPageCount())
                throw new IllegalArgumentException("PDF 页码或文档无效");
            try (PdfRenderer.Page page = document.renderer.openPage(number)) {
                float scale = Math.min(1.4f, Math.min(1600f / page.getWidth(), 2400f / page.getHeight()));
                int width = Math.max(1, Math.round(page.getWidth() * scale));
                int height = Math.max(1, Math.round(page.getHeight() * scale));
                bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
                bitmap.eraseColor(Color.WHITE);
                Matrix matrix = new Matrix(); matrix.setScale(scale, scale);
                page.render(bitmap, null, matrix, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY);
                ByteArrayOutputStream output = new ByteArrayOutputStream();
                bitmap.compress(Bitmap.CompressFormat.PNG, 100, output);
                JSObject result = new JSObject();
                result.put("data", Base64.encodeToString(output.toByteArray(), Base64.NO_WRAP));
                result.put("width", width); result.put("height", height);
                call.resolve(result);
            }
        } catch (Exception error) { call.reject("PDF 页面渲染失败", error); }
        finally { if (bitmap != null) bitmap.recycle(); }
    }

    @PluginMethod public synchronized void close(PluginCall call) {
        Document document = documents.remove(call.getString("handle"));
        if (document != null) document.close();
        call.resolve();
    }

    @Override protected synchronized void handleOnDestroy() {
        for (Document document : documents.values()) document.close();
        documents.clear();
    }
}
