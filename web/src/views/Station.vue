<script setup lang="ts">
import {computed,onMounted,onUnmounted,ref,nextTick,watch} from 'vue'
import {ElMessage} from 'element-plus'
import {api,post,state,connect,disconnect,stamp,uuid} from '../api'
import SopPlayer from '../components/SopPlayer.vue'

const emit=defineEmits<{ 'request-login':[] }>()
const sopUsed=ref<string|null>(null),sopReady=ref(false)
const messages=ref<any[]>([])
const options=ref<any>({stations:[],orders:[],batches:[],processes:[]})
const stationId=ref(''),batchId=ref(''),orderId=ref(''),chooser=ref(false)
const summary=ref<any>(),scan=ref(''),waiting=ref('主码'),busy=ref(false),choosing=ref(false)
const notice=ref('等待扫描'),failed=ref(false),current=ref<any>(),scanEl=ref<any>(),tests=ref<any[]>([])
const station=computed(()=>options.value.stations.find((s:any)=>s.id===stationId.value))
const process=computed(()=>options.value.processes.find((p:any)=>p.id===station.value?.processStepId))
const orders=computed(()=>options.value.orders.filter((o:any)=>o.lineId===station.value?.lineId))
const batches=computed(()=>options.value.batches.filter((b:any)=>b.workOrderId===orderId.value))
let pending:any=null,deferredCode='',epoch=0
let timer:ReturnType<typeof setInterval>,heartbeat:ReturnType<typeof setInterval>

function cancelGuestScan(){deferredCode='';scan.value='';nextTick(()=>scanEl.value?.focus())}
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
    const [data,results,inbox]=await Promise.all([
      api('/api/station/summary?stationId='+stationId.value+'&batchId='+batchId.value),
      process.value?.sequence===3?api('/api/tests?stationId='+stationId.value+'&batchId='+batchId.value):Promise.resolve([]),
      api('/api/messages')
    ])
    if(currentEpoch!==epoch||!state.user)return
    summary.value=data;tests.value=results;messages.value=inbox
    state.lastUpdate=new Date().toISOString()
  }catch(e:any){if(currentEpoch===epoch){notice.value=e.message;failed.value=true}}
}
async function choose(){
  if(!state.user||!stationId.value||!batchId.value||choosing.value)return
  choosing.value=true
  try{
    await post('/api/station/reset',{requestId:uuid(),stationId:stationId.value,batchId:batchId.value,code:''})
    waiting.value=process.value.sequence===5?'SN':'主码'
    current.value=null;pending=null;scan.value=''
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
    pending=null;waiting.value=r.waiting;notice.value=r.message;failed.value=false;current.value=r.trace
    await refresh()
  }catch(e:any){
    notice.value=e.message;failed.value=true
    if(state.online)pending=null
  }finally{busy.value=false;await nextTick();scanEl.value?.focus()}
}
async function reset(){await choose()}
async function sessionChanged(){
  const currentEpoch=++epoch
  await disconnect()
  options.value={stations:[],orders:[],batches:[],processes:[]}
  stationId.value='';orderId.value='';batchId.value='';summary.value=undefined
  current.value=undefined;tests.value=[];messages.value=[];scan.value='';pending=null
  sopUsed.value=null;sopReady.value=false;chooser.value=false
  if(!state.user){deferredCode='';await nextTick();scanEl.value?.focus();return}
  try{
    const data=await api('/api/station/options')
    if(currentEpoch!==epoch||!state.user)return
    options.value=data;chooser.value=true
    await connect(refresh)
  }catch(e:any){ElMessage.error(e.message)}
}
watch(()=>state.user?.id,sessionChanged,{immediate:true})
async function poll(){
  try{
    await api('/api/health')
    if(state.user){if(!state.connected)await connect(refresh);await refresh()}
  }catch{}
}
onMounted(()=>{
  poll()
  timer=setInterval(poll,15000)
  heartbeat=setInterval(()=>{
    if(state.user&&stationId.value&&batchId.value&&state.online)
      post('/api/station/heartbeat',{requestId:uuid(),stationId:stationId.value,batchId:batchId.value,code:''}).catch(()=>{})
  },30000)
})
onUnmounted(()=>{epoch++;deferredCode='';clearInterval(timer);clearInterval(heartbeat);disconnect()})
</script>
<template><div class="stationbar"><div><b>工位终端 · {{station?.code||(state.user?'请选择授权工位':'待开始作业')}}</b><p class="sub">{{process?.name}} · {{state.user?.name||'未登录'}}</p></div><div class="actions"><span :class="{'offline':!state.online}">{{state.online?'在线':'断网 · 禁止过站'}}</span><el-button @click="openChooser">⇄ 切换工位</el-button></div></div><main v-if="!state.user" class="content station-welcome">
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
        <p class="sub">扫描主码或 SN 后提示登录，登录完成后继续处理该次扫描。</p>
        <el-input ref="scanEl" v-model="scan" class="auto-scan" placeholder="请扫描条码，登录后开始作业" :disabled="!state.online" autocomplete="off" @keydown.enter.prevent="submit" @keydown.tab.prevent="submit"/>
        <p v-if="!state.online" class="offline">服务器未连接，暂时无法扫码。</p>
        <p class="hint">测试工位由仪器自动上传，无需手动扫码。</p>
      </section>
    </div>
  </section>
</main><main v-else class="content"><el-alert v-if="!state.online" title="网络已断开，禁止离线过站；可继续查看已缓存 SOP。连接恢复后重新扫描同一码。" type="error" :closable="false"/><section v-if="summary" class="panel station-order"><div class="panelhead"><h2>{{summary.order.code}} · 批次 {{summary.batch.code}}</h2><span>{{station?.name}}</span></div><div class="stats"><div class="stat"><span>工单计划</span><strong>{{summary.order.plan}}</strong></div><div class="stat"><span>批次计划</span><strong>{{summary.batch.plan}}</strong></div><div class="stat"><span>本工位完成</span><strong>{{summary.done}}</strong></div><div class="stat"><span>批次剩余</span><strong>{{summary.remaining}}</strong></div></div></section><div v-if="station&&!chooser" class="stationlayout"><section class="panel"><h2>SOP · {{process?.name}}</h2><SopPlayer :process-id="station.processStepId" @loaded="onSopLoaded"/></section><section v-if="process?.sequence!==3" class="panel"><div class="panelhead"><h2>扫码作业</h2><span class="badge teal">等待{{waiting}}</span></div><p class="sub">使用同一把扫码枪连续扫描，Enter / Tab 自动提交</p><el-input ref="scanEl" v-model="scan" class="auto-scan" :placeholder="'请扫描'+waiting" :disabled="busy||choosing||!state.online" autofocus autocomplete="off" @keydown.enter.prevent="submit" @keydown.tab.prevent="submit"/><div class="statusbox" :class="{fail:failed}" role="status"><h2>{{notice}}</h2></div><el-button @click="reset">重新扫描{{process?.sequence===5?'SN':'主码'}}</el-button><div v-if="current"><div v-for="(label,key) in {mainCode:'主码',cellCode:'电芯码',snCode:'SN',packCode:'包装码'}" :key="key" class="summaryrow"><span>{{label}}</span><b>{{current.unit[key]||'未绑定'}}</b></div><div class="timeline"><div v-for="p in current.passages" :key="p.id" class="event"><b>{{options.processes.find((x:any)=>x.id===p.processStepId)?.name}}</b><p>{{stamp(p.at)}}</p></div></div></div></section><section v-else class="panel"><h2>仪器自动测试结果</h2><p class="sub">由仪器扫描主码并启动测试，MES 自动接收结果。</p><div v-if="tests[0]" class="instrument-result" :class="{isfail:tests[0].result.verdict==='FAIL'}"><h1>{{tests[0].result.verdict}}</h1><div>{{tests[0].mainCode}}<p>{{stamp(tests[0].result.receivedAt)}}</p></div></div><p v-else>等待仪器上传结果</p><div class="test-history"><el-table :data="tests"><el-table-column prop="mainCode" label="主码"/><el-table-column prop="result.verdict" label="结果"/><el-table-column label="接收时间"><template #default="{row}">{{stamp(row.result.receivedAt)}}</template></el-table-column></el-table></div></section></div><section v-if="messages.length" class="panel"><h2>工位消息</h2><div v-for="m in messages" :key="m.message.id" class="lineitem"><div class="fill"><b>{{m.message.important?'【重要】':''}}{{m.message.title}}</b><p>{{m.message.body}}</p></div><el-button :disabled="m.acked||!state.online" @click="ack(m.message.id)">{{m.acked?'已确认':'确认收到'}}</el-button></div></section><section v-if="summary" class="panel"><h2>最近过站记录</h2><el-table :data="summary.recent"><el-table-column prop="u.mainCode" label="主码"/><el-table-column prop="u.cellCode" label="电芯码"/><el-table-column prop="u.snCode" label="SN"/><el-table-column prop="u.packCode" label="包装码"/><el-table-column label="时间"><template #default="{row}">{{stamp(row.pass.at)}}</template></el-table-column></el-table></section></main><el-dialog v-if="state.user" v-model="chooser" title="切换授权工位与工单" :close-on-click-modal="false" :show-close="!!summary"><el-form label-width="80"><el-form-item label="工位"><el-select v-model="stationId" @change="orderId='';batchId=''" placeholder="选择授权工位"><el-option v-for="s in options.stations" :key="s.id" :value="s.id" :label="s.code+' · '+s.name"/></el-select></el-form-item><el-form-item label="工单"><el-select v-model="orderId" @change="batchId=''"><el-option v-for="o in orders" :key="o.id" :value="o.id" :label="o.code"/></el-select></el-form-item><el-form-item label="批次"><el-select v-model="batchId"><el-option v-for="b in batches" :key="b.id" :value="b.id" :label="b.code"/></el-select></el-form-item></el-form><p v-if="!options.stations.length" class="sub">当前用户没有授权工位，请管理员配置。</p><template #footer><el-button type="primary" :disabled="!batchId" @click="choose">进入工位</el-button></template></el-dialog></template>
