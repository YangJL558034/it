import 'abortcontroller-polyfill/dist/polyfill-patch-fetch'
import {ResizeObserver as CompatibleResizeObserver} from '@juggle/resize-observer'

// Android OS version does not determine its independently updated WebView version.
if(typeof globalThis.ResizeObserver==='undefined')globalThis.ResizeObserver=CompatibleResizeObserver
if(typeof AbortSignal.timeout!=='function'){
  AbortSignal.timeout=(milliseconds:number)=>{
    const controller=new AbortController()
    setTimeout(()=>controller.abort(),milliseconds)
    return controller.signal
  }
}
