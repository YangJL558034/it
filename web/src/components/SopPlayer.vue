<script setup lang="ts">
import {computed,nextTick,onMounted,onUnmounted,ref,watch} from 'vue'
import {api,blob,base} from '../api'
import {openSopPdf,type SopPdf} from '../pdfDocument'
const emit=defineEmits<{loaded:[id:string|null]}>()
const props=defineProps<{processId?:string;preview?:any}>()
const manifest=ref<any>({version:null,files:[]}),index=ref(0),playing=ref(true),url=ref(''),error=ref(''),cached=ref(false)
const canvas=ref<HTMLCanvasElement>(),video=ref<HTMLVideoElement>(),page=ref(1),pages=ref(1)
const file=computed(()=>manifest.value.files[index.value])
let timer:ReturnType<typeof setTimeout>,poll:ReturnType<typeof setInterval>,generation=0,loadEpoch=0
let pdf:SopPdf|undefined
async function database(){return new Promise<IDBDatabase>((resolve,reject)=>{const r=indexedDB.open('mes-sop-cache',1);r.onupgradeneeded=()=>r.result.createObjectStore('files');r.onsuccess=()=>resolve(r.result);r.onerror=()=>reject(r.error)})}
async function cache(key:string,value?:any){const db=await database();try{return await new Promise<any>((resolve,reject)=>{const tx=db.transaction('files',value===undefined?'readonly':'readwrite');const r=value===undefined?tx.objectStore('files').get(key):tx.objectStore('files').put(value,key);tx.oncomplete=()=>resolve(r.result);tx.onerror=()=>reject(tx.error)})}finally{db.close()}}
async function load(){
  const current=++loadEpoch,key=base()+'/sop/'+props.processId
  let m=props.preview
  try{
    if(!m){m=await api('/api/sop/current/'+props.processId);if(m.version?.id!==manifest.value.version?.id){for(const f of m.files)await cache(base()+'/file/'+f.id,await blob('/api/sop/files/'+f.id));await cache(key,m)}cached.value=false}
  }catch{m=await cache(key);cached.value=true}
  if(current!==loadEpoch||!m)return
  if(!props.preview&&m.version?.id===manifest.value.version?.id&&manifest.value.files.length)return
  const previous=manifest.value.version?.id
  manifest.value=m;index.value=previous===m.version?.id?Math.min(index.value,Math.max(0,m.files.length-1)):0
  await show()
  if(current===loadEpoch)emit('loaded',m.version?.id||null)
}
function schedule(){clearTimeout(timer);if(playing.value&&file.value&&!file.value.contentType.startsWith('video/'))timer=setTimeout(()=>step(1),file.value.seconds*1000)}
async function disposePdf(){
  const task=pdf;pdf=undefined
  if(task)try{await task.destroy()}catch{}
}
async function renderPdf(){
  const g=generation,document=pdf
  if(!document||!canvas.value)return
  try{
    await document.render(canvas.value,page.value)
  }catch(e:any){if(g===generation&&e.name!=='RenderingCancelledException')error.value=e.message}
}
async function show(){
  const g=++generation
  clearTimeout(timer)
  if(url.value)URL.revokeObjectURL(url.value)
  url.value='';error.value='';page.value=1;pages.value=1
  await disposePdf()
  if(g!==generation||!file.value)return
  const selected=file.value
  try{
    const b:Blob=await cache(base()+'/file/'+selected.id)||await blob('/api/sop/files/'+selected.id)
    if(g!==generation)return
    url.value=URL.createObjectURL(b)
    if(selected.contentType==='application/pdf'){
      const document=await openSopPdf(b)
      if(g!==generation){await document.destroy();return}
      pdf=document;pages.value=document.numPages;await nextTick();await renderPdf()
    }
    if(g!==generation)return
    await nextTick()
    if(playing.value)void video.value?.play().catch(()=>{})
    schedule()
  }catch(e:any){if(g===generation)error.value=e.message}
}
function step(d:number){if(!manifest.value.files.length)return;index.value=(index.value+d+manifest.value.files.length)%manifest.value.files.length;void show()}
function select(i:number){if(i<0||i>=manifest.value.files.length)return;index.value=i;void show()}
defineExpose({select})
function toggle(){playing.value=!playing.value;if(playing.value){void video.value?.play().catch(()=>{});schedule()}else{clearTimeout(timer);video.value?.pause()}}
watch(()=>props.processId,load);watch(()=>props.preview,load)
onMounted(()=>{void load();if(!props.preview)poll=setInterval(load,30000)})
onUnmounted(()=>{clearTimeout(timer);clearInterval(poll);generation++;loadEpoch++;void disposePdf();if(url.value)URL.revokeObjectURL(url.value)})
</script>
<template><div class="sop-player"><div class="panelhead"><b>{{manifest.version?.title||'暂无已发布 SOP'}} <span v-if="manifest.version">V{{manifest.version.version}}</span></b><span v-if="cached" class="badge amber">离线缓存</span></div><div class="sop-stage"><el-empty v-if="!file&&!error" description="暂无播放文件，请上传并发布当前工序的指导书"/><p v-else-if="error" class="offline">{{error}}</p><img v-else-if="file?.contentType.startsWith('image/')" :src="url" :alt="file.name"><canvas v-else-if="file?.contentType==='application/pdf'" ref="canvas"/><video v-else-if="file?.contentType.startsWith('video/')" ref="video" :src="url" controls muted playsinline @ended="playing&&step(1)"/></div><div v-if="file" class="actions"><el-button @click="step(-1)">上一份</el-button><el-button @click="toggle">{{playing?'暂停':'播放'}}</el-button><el-button @click="step(1)">下一份</el-button><small>{{index+1}} / {{manifest.files.length}} · {{file.name}} · {{file.seconds}} 秒</small><template v-if="pages>1"><el-button :disabled="page<=1" @click="page--;renderPdf()">上一页</el-button><span>{{page}} / {{pages}} 页</span><el-button :disabled="page>=pages" @click="page++;renderPdf()">下一页</el-button></template></div></div></template>
