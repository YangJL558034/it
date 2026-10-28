package com.ruoyi.mes.branding;

import com.ruoyi.common.exception.ServiceException;
import com.ruoyi.common.utils.SecurityUtils;
import com.ruoyi.mes.maintenance.MesMaintenanceState;
import com.ruoyi.mes.realtime.MesChanges;
import com.ruoyi.mes.storage.MesDataPaths;
import java.io.*;
import java.util.*;
import javax.imageio.ImageIO;
import org.springframework.core.io.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

@Service
public class MesBrandingService {

  private final JdbcTemplate db;
  private final MesDataPaths paths;
  private final MesChanges changes;
  private final MesMaintenanceState maintenance;

  public MesBrandingService(
    JdbcTemplate db,
    MesDataPaths paths,
    MesChanges changes,
    MesMaintenanceState maintenance
  ) {
    this.db = db;
    this.paths = paths;
    this.changes = changes;
    this.maintenance = maintenance;
  }

  public Map<String, Object> current() {
    var row = db.queryForMap(
      "select name,logo_key,revision,updated_at from mes_branding where id=1"
    );
    return Map.of(
      "name",
      row.get("name"),
      "logoUrl",
      "/api/branding/logo/" +
      (row.get("logo_key") == null ? "default" : row.get("logo_key")),
      "revision",
      row.get("revision")
    );
  }

  public Resource logo(String key) {
    if ("default".equals(key)) return new ClassPathResource(
      "branding/logo.png"
    );
    if (!key.matches("[a-f0-9]{64}")) throw new ServiceException(
      "Logo 不存在",
      404
    );
    var resource = new FileSystemResource(
      paths.resolve("branding/" + key + ".png")
    );
    if (!resource.exists()) throw new ServiceException("Logo 文件不存在", 404);
    return resource;
  }

  @Transactional(rollbackFor = Exception.class)
  public Map<String, Object> save(
    String name,
    MultipartFile upload,
    boolean reset
  ) throws IOException {
    db.execute("select pg_advisory_xact_lock(74215003)");
    maintenance.assertWritable();
    if (
      name == null || name.isBlank() || name.strip().length() > 128
    ) throw new ServiceException("平台名称必填且最多 128 字符", 422);
    if (
      reset && upload != null && !upload.isEmpty()
    ) throw new ServiceException("不能同时上传和恢复默认 Logo", 422);
    String key = null;
    if (upload != null && !upload.isEmpty()) {
      if (upload.getSize() > 2 * 1024 * 1024) throw new ServiceException(
        "Logo 图片最大 2 MB",
        422
      );
      byte[] input = upload.getBytes();
      try (
        var stream = ImageIO.createImageInputStream(
          new ByteArrayInputStream(input)
        )
      ) {
        var readers = ImageIO.getImageReaders(stream);
        if (!readers.hasNext()) throw new ServiceException(
          "请上传 PNG 或 JPEG 图片",
          422
        );
        var reader = readers.next();
        try {
          reader.setInput(stream);
          String format = reader.getFormatName();
          if (
            !format.equalsIgnoreCase("png") && !format.equalsIgnoreCase("jpeg")
          ) throw new ServiceException("只支持 PNG/JPEG Logo", 422);
          int width = reader.getWidth(0), height = reader.getHeight(0);
          if (
            width < 1 ||
            height < 1 ||
            width > 4096 ||
            height > 4096 ||
            (long) width * height > 16000000
          ) throw new ServiceException("图片尺寸须不超过 4096×4096", 422);
          var image = reader.read(0);
          var output = new ByteArrayOutputStream();
          ImageIO.write(image, "png", output);
          var bytes = output.toByteArray();
          key = MesDataPaths.sha256(bytes);
          paths.atomicWrite(paths.resolve("branding/" + key + ".png"), bytes);
        } finally {
          reader.dispose();
        }
      }
    }
    if (reset) db.update(
      "update mes_branding set name=?,logo_key=null,revision=revision+1,updated_by=?,updated_at=now() where id=1",
      name.strip(),
      SecurityUtils.getUserId()
    );
    else if (key != null) db.update(
      "update mes_branding set name=?,logo_key=?,revision=revision+1,updated_by=?,updated_at=now() where id=1",
      name.strip(),
      key,
      SecurityUtils.getUserId()
    );
    else db.update(
      "update mes_branding set name=?,revision=revision+1,updated_by=?,updated_at=now() where id=1",
      name.strip(),
      SecurityUtils.getUserId()
    );
    changes.publish();
    return current();
  }
}
