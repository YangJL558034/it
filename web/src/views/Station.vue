<script setup lang="ts">
import {computed,onMounted,onUnmounted,ref,nextTick,watch} from 'vue'
import {ElMessage} from 'element-plus'
import {api,post,state,connect,disconnect,stamp,uuid,resumeStation} from '../api'
import {Preferences} from '@capacitor/preferences'
import {base} from '../api'
import {unlockScannerAudio,scannerAlarm,closeScannerAudio} from '../scannerFeedback'
import SopPlayer from '../components/SopPlayer.vue'

const emit=defineEmits<{ 'request-login':[] }>()
const sopUsed=ref<string|null>(null),sopReady=ref(false)
const messages=ref<any[]>([]),inboxOpen=ref(false),showConfirmed=ref(false)
const unread=computed(()=>messages.value.filter(m=>!m.acked))
const visibleMessages=computed(()=>showConfirmed.value?messages.value:unread.value)
const awaitingBinding=computed(()=>!!summary.value?.context?.unitId)
function blockedFocus(){return chooser.value||inboxOpen.value||choosing.value||!state.online||!!document.querySelector('.el-overlay:not([style*="display: none"])')}
function focusScanner(){if(!blockedFocus())scanEl.value?.input?.focus({preventScroll:true})}
function onPointer(e:PointerEvent){
  unlockScannerAudio()
  const target=e.target as HTMLElement
  if(target.closest('input,textarea,select,[contenteditable="true"],.el-select,.el-dialog,.el-drawer'))return
  setTimeout(focusScanner,0)
}
function onKey(e:KeyboardEvent){
  unlockScannerAudio()
  if(blockedFocus()||e.ctrlKey||e.altKey||e.metaKey||e.isComposing||e.key.length!==1)return
  const active=document.activeElement as HTMLElement|null
  if(active?.matches('input,textarea,select,[contenteditable="true"]'))return
  if(active?.closest('button,a,[role="button"]'))return
  if(!scanEl.value?.input)return
  focusScanner();scan.value+=e.key;onScanInput(scan.value);e.preventDefault()
}
function onVisible(){if(document.visibilityState==='visible')focusScanner()}
const options=ref<any>({stations:[],orders:[],batches:[],processes:[]})
const stationId=ref(''),batchId=ref(''),orderId=ref(''),chooser=ref(false)
const summary=ref<any>(),scan=ref(''),waiting=ref('主码'),busy=ref(false),choosing=ref(false)
const notice=ref('等待扫描'),failed=ref(false),current=ref<any>(),scanEl=ref<any>(),tests=ref<any[]>([])
watch([busy,choosing,chooser,inboxOpen,()=>state.online],()=>nextTick(focusScanner))
const station=computed(()=>options.value.stations.find((s:any)=>s.id===stationId.value))
const process=computed(()=>options.value.processes.find((p:any)=>p.id===station.value?.processStepId))
const orders=computed(()=>options.value.orders.filter((o:any)=>o.lineId===station.value?.lineId))
const batches=computed(()=>options.value.batches.filter((b:any)=>b.workOrderId===orderId.value))
type BarcodeRule={kind:string;version:number;length:number;prefixes:string[];charset:string}
const barcodeRules=ref<BarcodeRule[]>([]),composing=ref(false)
const expectedKind=computed(()=>!state.user?null:waiting.value==='电芯码'?'cell':waiting.value==='SN'?'sn':waiting.value==='包装码'?'pack':'main')
function matchingRules(code:string){
  if(expectedKind.value)return barcodeRules.value.filter(r=>r.kind===expectedKind.value)
  const initial=barcodeRules.value.filter(r=>r.kind==='main'||r.kind==='sn')
  const matches=initial.filter(r=>!r.prefixes.length||r.prefixes.some(prefix=>code.startsWith(prefix)))
  return matches.length?matches:initial
}
const expectedLengths=computed(()=>[...new Set(matchingRules(scan.value).map(r=>r.length))])
let pending:any=null,deferredCode='',epoch=0
let timer:ReturnType<typeof setInterval>,heartbeat:ReturnType<typeof setInterval>
let autoScanTimer:ReturnType<typeof setTimeout>|undefined

function cancelAutoScan(){if(autoScanTimer)clearTimeout(autoScanTimer);autoScanTimer=undefined}
function onScanInput(value:string){
  cancelAutoScan()
  if(composing.value||!value||!matchingRules(value).some(r=>r.length===value.length))return
  // Wait for the scanner's character burst to finish; never truncate an overlong code.
  autoScanTimer=setTimeout(()=>{if(scan.value===value&&!composing.value)void submit()},100)
}
async function refreshRules(){barcodeRules.value=await api<BarcodeRule[]>('/api/station/barcode-rules')}

function cancelGuestScan(){cancelAutoScan();deferredCode='';scan.value='';nextTick(()=>scanEl.value?.focus())}
defineExpose({cancelGuestScan})

function openChooser(){
  if(!state.user){emit('request-login');return}
  chooser.value=true
}
async function ack(id:string){
  await post('/api/messages/'+id+'/ack')
  messages.value=await api('/api/messages')
}
async function refresh(){
  if(!state.user||!stationId.value||!batchId.value)return
  const currentEpoch=epoch
  try{
    await refreshRules()
    const [data,results,inbox]=await Promise.all([
      api('/api/station/summary?stationId='+stationId.value+'&batchId='+batchId.value),
      process.value?.sequence===3?api('/api/tests?stationId='+stationId.value+'&batchId='+batchId.value):Promise.resolve([]),
      api('/api/messages')
    ])
    if(currentEpoch!==epoch||!state.user)return
    summary.value=data;tests.value=results;messages.value=inbox
    if(!busy.value&&!choosing.value)waiting.value=data.context?.unitId?(process.value?.sequence===2?'电芯码':process.value?.sequence===4?'SN':'包装码'):(process.value?.sequence===5?'SN':'主码')
    state.lastUpdate=new Date().toISOString()
  }catch(e:any){if(currentEpoch===epoch){notice.value=e.message;failed.value=true}}
}
async function choose(){
  if(!state.user||!stationId.value||!batchId.value||choosing.value)return
  choosing.value=true
  cancelAutoScan()
  try{
    await post('/api/station/reset',{requestId:uuid(),stationId:stationId.value,batchId:batchId.value,code:''})
    waiting.value=process.value.sequence===5?'SN':'主码'
    current.value=null;pending=null;scan.value=''
    await Preferences.set({key:'mes.station.selection',value:JSON.stringify({server:base(),userId:state.user.id,stationId:stationId.value,orderId:orderId.value,batchId:batchId.value})})
    if(chooser.value){sopReady.value=false;sopUsed.value=null}
    chooser.value=false;failed.value=false;notice.value='等待扫描'+waiting.value
    if(process.value.sequence===3&&deferredCode){
      deferredCode='';ElMessage.info('测试工位由仪器自动上传，无需人工扫码')
    }
    await refresh();await nextTick();scanEl.value?.focus()
  }catch(e:any){ElMessage.error(e.message)}
  finally{choosing.value=false;await continueDeferred();await nextTick();scanEl.value?.focus()}
}
async function onSopLoaded(id:string|null){
  sopUsed.value=id;sopReady.value=true
  await continueDeferred()
}
async function continueDeferred(){
  if(deferredCode&&sopReady.value&&state.user&&!chooser.value&&!choosing.value&&process.value?.sequence!==3){
    scan.value=deferredCode;deferredCode=''
    await submit()
  }
}
async function submit(){
  cancelAutoScan()
  if(busy.value||choosing.value||!scan.value||!state.online)return
  if(!state.user){
    deferredCode=scan.value;scan.value=''
    emit('request-login')
    return
  }
  if(!sopReady.value){notice.value='请等待 SOP 加载完成后扫码';return}
  busy.value=true
  const code=scan.value;scan.value=''
  if(!pending||pending.code!==code)pending={requestId:uuid(),stationId:stationId.value,batchId:batchId.value,code,sopVersionId:sopUsed.value}
  try{
    const r=await post('/api/station/scan',pending)
    pending=null;waiting.value=r.waiting;notice.value=r.message;failed.value=!!r.duplicate;current.value=r.trace
    if(r.duplicate)void scannerAlarm()
    await refresh()
  }catch(e:any){
    notice.value=e.message;failed.value=true
    if(state.online)void scannerAlarm()
    if(state.online)pending=null
  }finally{busy.value=false;await nextTick();focusScanner();if(scan.value)onScanInput(scan.value)}
}
async function reset(){await choose()}
async function sessionChanged(){
  const currentEpoch=++epoch
  cancelAutoScan()
  await disconnect()
  options.value={stations:[],orders:[],batches:[],processes:[]}
  stationId.value='';orderId.value='';batchId.value='';summary.value=undefined
  current.value=undefined;tests.value=[];messages.value=[];scan.value='';pending=null
  sopUsed.value=null;sopReady.value=false;chooser.value=false;inboxOpen.value=false
  if(!state.user){deferredCode='';await nextTick();scanEl.value?.focus();return}
  try{
    const data=await api('/api/station/options')
    if(currentEpoch!==epoch||!state.user)return
    options.value=data;chooser.value=true
    const raw=(await Preferences.get({key:'mes.station.selection'})).value
    if(raw){try{const saved=JSON.parse(raw),s=data.stations.find((x:any)=>x.id===saved.stationId),o=data.orders.find((x:any)=>x.id===saved.orderId),b=data.batches.find((x:any)=>x.id===saved.batchId)
      if(saved.server===base()&&saved.userId===state.user.id&&s&&o?.lineId===s.lineId&&b?.workOrderId===o.id){stationId.value=s.id;orderId.value=o.id;batchId.value=b.id;await choose()}
    }catch{}}
    await connect(refresh)
  }catch(e:any){ElMessage.error(e.message)}
}
watch(()=>state.user?.id,sessionChanged,{immediate:true})
async function poll(){
  try{
    await api('/api/health')
    await refreshRules()
    if(!state.user)await resumeStation()
    if(state.user){if(!state.connected)await connect(refresh);await refresh()}
  }catch{}
}
onMounted(()=>{
  document.addEventListener('keydown',onKey);document.addEventListener('pointerup',onPointer);document.addEventListener('visibilitychange',onVisible);window.addEventListener('focus',focusScanner)
  nextTick(focusScanner)
  poll()
  timer=setInterval(poll,15000)
  heartbeat=setInterval(()=>{
    if(state.user&&stationId.value&&batchId.value&&state.online)
      post('/api/station/heartbeat',{requestId:uuid(),stationId:stationId.value,batchId:batchId.value,code:''}).catch(()=>{})
  },30000)
})
onUnmounted(()=>{document.removeEventListener('keydown',onKey);document.removeEventListener('pointerup',onPointer);document.removeEventListener('visibilitychange',onVisible);window.removeEventListener('focus',focusScanner);closeScannerAudio();epoch++;deferredCode='';cancelAutoScan();clearInterval(timer);clearInterval(heartbeat);disconnect()})
</script>
<template><div class="stationbar"><div><b>工位终端 · {{station?.code||(state.user?'请选择授权工位':'待开始作业')}}</b><p class="sub">{{process?.name}} · {{state.user?.name||'未登录'}}</p></div><div class="actions"><span :class="{'offline':!state.online}">{{state.online?'在线':'断网 · 禁止过站'}}</span><el-button v-if="state.user" class="station-inbox-button" @click="inboxOpen=true">消息 <span v-if="unread.length" class="inbox-count">{{unread.length}}</span></el-button><el-button @click="openChooser">⇄ 切换工位</el-button></div></div><main v-if="!state.user" class="content station-welcome">
  <section class="panel">
    <div class="eyebrow">WORKSTATION</div><h1>工位工作台</h1>
    <p class="sub">扫码时登录，选择授权工位与当前工单后开始作业。</p>
    <div class="stationlayout">
      <div class="station-welcome-flow">
        <h2>生产流程</h2>
        <div v-for="(name,index) in ['主码过站','电芯绑定','仪器测试','SN 绑定','包装绑定']" :key="name" class="summaryrow"><span>{{String(index+1).padStart(2,'0')}}</span><b>{{name}}</b></div>
        <p class="sub">工单、SOP、生产记录和仪器结果将在登录后按权限显示。</p>
      </div>
      <section class="panel">
        <div class="panelhead"><h2>扫码作业</h2><span class="badge amber">等待登录</span></div>
        <p class="sub">扫描主码或 SN，达到配置长度自动提示登录，再继续处理该次扫描。</p>
        <p class="scan-length-hint sub" v-if="expectedLengths.length">已输入 {{scan.length}} 位 · {{expectedLengths.join(' / ')}} 位自动提交</p><p v-else class="sub">正在获取已发布条码规则…</p><el-input ref="scanEl" v-model="scan" class="auto-scan" @input="onScanInput" @compositionstart="composing=true;cancelAutoScan()" @compositionend="composing=false;onScanInput(scan)" placeholder="请扫描条码，登录后开始作业" :disabled="!state.online" autocomplete="off" @keydown.enter.prevent="submit" @keydown.tab.prevent="submit"/>
        <p v-if="!state.online" class="offline">服务器未连接，暂时无法扫码。</p>
        <p class="hint">测试工位由仪器自动上传，无需手动扫码。</p>
      </section>
    </div>
  </section>
</main><main v-else class="content"><el-alert v-if="!state.online" title="网络已断开，禁止离线过站；可继续查看已缓存 SOP。连接恢复后重新扫描同一码。" type="error" :closable="false"/><section v-if="summary" class="panel station-order"><div class="panelhead"><h2>{{summary.order.code}} · 批次 {{summary.batch.code}}</h2><span>{{station?.name}}</span></div><div class="stats"><div class="stat"><span>工单计划</span><strong>{{summary.order.plan}}</strong></div><div class="stat"><span>批次计划</span><strong>{{summary.batch.plan}}</strong></div><div class="stat"><span>本工位完成</span><strong>{{summary.done}}</strong></div><div class="stat"><span>批次剩余</span><strong>{{summary.remaining}}</strong></div></div></section><div v-if="station&&!chooser" class="stationlayout"><section class="panel"><h2>SOP · {{process?.name}}</h2><SopPlayer :process-id="station.processStepId" @loaded="onSopLoaded"/></section><section v-if="process?.sequence!==3" class="panel"><div class="panelhead"><h2>扫码作业</h2><span class="badge teal">等待{{waiting}}</span></div><p class="sub">使用同一把扫码枪连续扫描，达到配置长度自动提交，无需回车</p><p class="scan-length-hint sub" v-if="expectedLengths.length">已输入 {{scan.length}} 位 · {{expectedLengths.join(' / ')}} 位自动提交</p><p v-else class="sub">正在获取已发布条码规则…</p><el-input ref="scanEl" v-model="scan" class="auto-scan" @input="onScanInput" @compositionstart="composing=true;cancelAutoScan()" @compositionend="composing=false;onScanInput(scan)" :placeholder="'请扫描'+waiting" :disabled="choosing||!state.online" autofocus autocomplete="off" @keydown.enter.prevent="submit" @keydown.tab.prevent="submit"/><div class="statusbox" :class="{fail:failed}" role="status"><h2>{{notice}}</h2></div><p class="scan-continuous-hint sub">扫码框自动接收下一条码，无需点击。</p><el-button v-if="awaitingBinding" @click="reset">取消本次绑定</el-button><div v-if="current"><div v-for="(label,key) in {mainCode:'主码',cellCode:'电芯码',snCode:'SN',packCode:'包装码'}" :key="key" class="summaryrow"><span>{{label}}</span><b>{{current.unit[key]||'未绑定'}}</b></div><div class="timeline"><div v-for="p in current.passages" :key="p.id" class="event"><b>{{options.processes.find((x:any)=>x.id===p.processStepId)?.name}}</b><p>{{stamp(p.at)}}</p></div></div></div></section><section v-else class="panel"><h2>仪器自动测试结果</h2><p class="sub">由仪器扫描主码并启动测试，MES 自动接收结果。</p><div v-if="tests[0]" class="instrument-result" :class="{isfail:tests[0].result.verdict==='FAIL'}"><h1>{{tests[0].result.verdict}}</h1><div>{{tests[0].mainCode}}<p>{{stamp(tests[0].result.receivedAt)}}</p></div></div><p v-else>等待仪器上传结果</p><div class="test-history"><el-table :data="tests"><el-table-column prop="mainCode" label="主码"/><el-table-column prop="result.verdict" label="结果"/><el-table-column label="接收时间"><template #default="{row}">{{stamp(row.result.receivedAt)}}</template></el-table-column></el-table></div></section></div><section v-if="summary" class="panel"><h2>最近过站记录</h2><el-table :data="summary.recent"><el-table-column prop="u.mainCode" label="主码"/><el-table-column prop="u.cellCode" label="电芯码"/><el-table-column prop="u.snCode" label="SN"/><el-table-column prop="u.packCode" label="包装码"/><el-table-column label="时间"><template #default="{row}">{{stamp(row.pass.at)}}</template></el-table-column></el-table></section></main><el-drawer v-model="inboxOpen" title="工位消息" size="min(420px, 92vw)" class="station-inbox" @closed="focusScanner"><div class="panelhead"><span>{{unread.length}} 条待确认</span><el-switch v-model="showConfirmed" active-text="显示已确认"/></div><el-empty v-if="!visibleMessages.length" description="暂无待确认消息"/><article v-for="m in visibleMessages" :key="m.message.id" class="inbox-message"><div class="panelhead"><b>{{m.message.title}}</b><span v-if="m.message.important" class="badge amber">重要</span></div><p>{{m.message.body}}</p><div class="panelhead"><small class="sub">{{stamp(m.message.at)}}</small><el-button size="small" :disabled="m.acked||!state.online" @click="ack(m.message.id)">{{m.acked?'已确认':'确认收到'}}</el-button></div></article></el-drawer><el-dialog v-if="state.user" v-model="chooser" title="切换授权工位与工单" :close-on-click-modal="false" :show-close="!!summary"><el-form label-width="80"><el-form-item label="工位"><el-select v-model="stationId" @change="orderId='';batchId=''" placeholder="选择授权工位"><el-option v-for="s in options.stations" :key="s.id" :value="s.id" :label="s.code+' · '+s.name"/></el-select></el-form-item><el-form-item label="工单"><el-select v-model="orderId" @change="batchId=''"><el-option v-for="o in orders" :key="o.id" :value="o.id" :label="o.code"/></el-select></el-form-item><el-form-item label="批次"><el-select v-model="batchId"><el-option v-for="b in batches" :key="b.id" :value="b.id" :label="b.code"/></el-select></el-form-item></el-form><p v-if="!options.stations.length" class="sub">当前用户没有授权工位，请管理员配置。</p><template #footer><el-button type="primary" :disabled="!batchId" @click="choose">进入工位</el-button></template></el-dialog></template>
