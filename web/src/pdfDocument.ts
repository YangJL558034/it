import {Capacitor,registerPlugin} from '@capacitor/core'

export interface SopPdf {
  numPages:number
  render(canvas:HTMLCanvasElement,page:number):Promise<void>
  destroy():Promise<void>
}
const nativePdf=registerPlugin<{
  open(input:{data:string}):Promise<{handle:string;pages:number}>
  render(input:{handle:string;page:number}):Promise<{data:string;width:number;height:number}>
  close(input:{handle:string}):Promise<void>
}>('SopPdf')

export async function openSopPdf(blob:Blob):Promise<SopPdf>{
  if(Capacitor.isNativePlatform()){
    if(blob.size>32*1024*1024)throw Error('此 PDF 超过 32 MB，请在后台压缩后发布')
    const data=await new Promise<string>((resolve,reject)=>{
      const reader=new FileReader();reader.onload=()=>resolve(String(reader.result).split(',')[1]!);reader.onerror=()=>reject(reader.error);reader.readAsDataURL(blob)
    })
    const document=await nativePdf.open({data})
    return {
      numPages:document.pages,
      async render(canvas,page){
        const result=await nativePdf.render({handle:document.handle,page:page-1})
        const img=new Image()
        await new Promise<void>((resolve,reject)=>{img.onload=()=>resolve();img.onerror=()=>reject(Error('PDF 页面显示失败'));img.src='data:image/png;base64,'+result.data})
        canvas.width=result.width;canvas.height=result.height;canvas.getContext('2d')!.drawImage(img,0,0)
      },
      async destroy(){await nativePdf.close({handle:document.handle})}
    }
  }
  // PDF.js is never evaluated when a terminal/TV starts. Android uses PdfRenderer.
  const {getDocument,GlobalWorkerOptions}=await import('pdfjs-dist/legacy/build/pdf.mjs')
  const {default:worker}=await import('pdfjs-dist/legacy/build/pdf.worker.min.mjs?url')
  GlobalWorkerOptions.workerSrc=worker
  const task=getDocument({data:new Uint8Array(await blob.arrayBuffer())})
  const document=await task.promise
  let renderTask:any
  return {
    numPages:document.numPages,
    async render(canvas,page){
      renderTask?.cancel()
      const pdfPage=await document.getPage(page),viewport=pdfPage.getViewport({scale:1.4})
      canvas.width=viewport.width;canvas.height=viewport.height
      renderTask=pdfPage.render({canvas,canvasContext:canvas.getContext('2d')!,viewport})
      await renderTask.promise
    },
    async destroy(){renderTask?.cancel();await task.destroy()}
  }
}
