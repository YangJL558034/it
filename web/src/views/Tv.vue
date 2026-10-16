<script setup lang="ts">
import {computed,onMounted,onUnmounted,ref} from 'vue'
import {api,connect,disconnect,state,stamp} from '../api'
import {APP_NAME} from '../branding'
import BoardChart from '../components/BoardChart.vue'
const emit=defineEmits<{settings:[]}>()
const data=ref<any>(),line=ref(''),workshop=ref(''),clock=ref(new Date()),error=ref(''),rotation=ref(0)
let poll:ReturnType<typeof setInterval>,tick:ReturnType<typeof setInterval>,alive=true,refreshEpoch=0
const workshops=computed<string[]>(()=>Array.from(new Set<string>((data.value?.lines||[]).map((x:any)=>x.workshop))))
const online=computed(()=>data.value?.stations.filter((s:any)=>s.online).length||0)
const scope=computed(()=>data.value?.lines.find((l:any)=>l.id===line.value)?.name||workshop.value||'全厂生产总览')
const orders=computed<any[]>(()=>{const a=data.value?.orders||[];const start=(rotation.value%Math.max(1,Math.ceil(a.length/3)))*3;return a.slice(start,start+3)})
const stations=computed(()=>{const a=data.value?.stations||[];const start=(rotation.value%Math.max(1,Math.ceil(a.length/6)))*6;return a.slice(start,start+6)})
const percent=(value:number,total:number)=>total?Math.min(100,value/total*100):0
const viewport=ref(window.innerWidth)
const chartFont=computed(()=>Math.max(11,Math.round(viewport.value/1920*14)))
function resized(){viewport.value=window.innerWidth}
const trend=computed(()=>({
  animation:false,animationDurationUpdate:500,color:['#3ad9ed','#7e93ff'],textStyle:{fontFamily:'Microsoft YaHei',fontSize:chartFont.value},
  tooltip:{trigger:'axis',renderMode:'richText'},legend:{right:12,top:0,textStyle:{color:'#92aec9',fontSize:chartFont.value},itemWidth:12,itemHeight:6},
  grid:{left:12,right:18,top:45,bottom:8,containLabel:true},
  xAxis:{type:'category',boundaryGap:false,data:(data.value?.hourly||[]).map((h:any)=>new Date(h.at).toLocaleTimeString('zh-CN',{timeZone:'Asia/Shanghai',hour:'2-digit',minute:'2-digit',hour12:false})),axisLine:{lineStyle:{color:'#264661'}},axisTick:{show:false},axisLabel:{color:'#7694b0',fontSize:chartFont.value,interval:3}},
  yAxis:{type:'value',minInterval:1,splitLine:{lineStyle:{color:'#1a344d',type:'dashed'}},axisLabel:{color:'#7694b0',fontSize:chartFont.value}},
  series:[{name:'主码投入',type:'line',smooth:true,symbol:'circle',showSymbol:false,lineStyle:{width:3},areaStyle:{color:{type:'linear',x:0,y:0,x2:0,y2:1,colorStops:[{offset:0,color:'#3ad9ed45'},{offset:1,color:'#3ad9ed00'}]}},data:(data.value?.hourly||[]).map((h:any)=>h.started)},{name:'包装完成',type:'line',smooth:true,showSymbol:false,lineStyle:{width:3},data:(data.value?.hourly||[]).map((h:any)=>h.completed)}]
}))
const quality=computed(()=>({
  animation:false,animationDurationUpdate:500,color:['#38d9b3','#ff927a'],tooltip:{trigger:'item',renderMode:'richText'},
  series:[{type:'pie',radius:['72%','88%'],center:['50%','50%'],label:{show:false},emptyCircleStyle:{color:'#1a344d'},itemStyle:{borderRadius:6,borderColor:'#0d2237',borderWidth:4},data:data.value?.tested?[{name:'PASS',value:data.value.passed},{name:'FAIL',value:data.value.tested-data.value.passed}]:[]}]
}))
async function refresh(){const epoch=++refreshEpoch;try{const q=new URLSearchParams();if(line.value)q.set('lineId',line.value);if(workshop.value)q.set('workshop',workshop.value);const snapshot=await api('/api/tv?'+q);if(!alive||epoch!==refreshEpoch)return;data.value=snapshot;state.lastUpdate=snapshot.at;error.value=''}catch(e:any){if(alive&&epoch===refreshEpoch)error.value=e.message}}
onMounted(async()=>{window.addEventListener('resize',resized);await refresh();if(!alive)return;await connect(refresh,true);if(!alive){await disconnect();return}poll=setInterval(async()=>{rotation.value++;await refresh();if(alive&&!state.connected)await connect(refresh,true)},15000);tick=setInterval(()=>clock.value=new Date(),1000)})
onUnmounted(()=>{alive=false;refreshEpoch++;clearInterval(poll);clearInterval(tick);window.removeEventListener('resize',resized);disconnect()})
function fullscreen(){document.documentElement.requestFullscreen?.().catch(()=>{})}
</script>
<template>
<main class="mes-tv">
  <header class="board-header"><div class="board-brand"><span class="board-logo">聚</span><div>{{APP_NAME}}<small>MANUFACTURING INTELLIGENCE</small></div></div><div class="board-title"><h1>生产运行实时看板</h1><span>PRODUCTION OPERATIONS CENTER</span></div><div class="board-clock"><strong>{{clock.toLocaleTimeString('zh-CN',{timeZone:'Asia/Shanghai',hour12:false})}}</strong><small>{{clock.toLocaleDateString('zh-CN',{timeZone:'Asia/Shanghai',year:'numeric',month:'2-digit',day:'2-digit',weekday:'long'})}}</small></div></header>
  <div class="board-toolbar"><div class="scope-title"><i></i>{{scope}}<span>累计生产数据 · 实时监控</span></div><div class="actions"><select aria-label="车间" v-model="workshop" @change="line='';rotation=0;refresh()"><option value="">全部车间</option><option v-for="w in workshops" :key="w">{{w}}</option></select><select aria-label="产线" v-model="line" @change="rotation=0;refresh()"><option value="">全部产线</option><option v-for="l in data?.lines.filter((l:any)=>!workshop||l.workshop===workshop)" :key="l.id" :value="l.id">{{l.name}}</option></select><button @click="emit('settings')">服务器设置</button><button @click="fullscreen">⛶ 全屏</button></div></div>
  <div class="tv-metrics">
    <article><span>主码投入 <small>INPUT</small></span><strong>{{data?.started?.toLocaleString()??'—'}}<em>件</em></strong><p>当前范围累计投入</p><span class="metric-decoration">↗</span></article>
    <article><span>包装完成 <small>OUTPUT</small></span><strong>{{data?.completed?.toLocaleString()??'—'}}<em>件</em></strong><p>完成率 {{percent(data?.completed,data?.planned).toFixed(1)}}% · 计划 {{data?.planned??'—'}}</p><div class="metric-track"><i :style="{width:percent(data?.completed,data?.planned)+'%'}"/></div></article>
    <article><span>最新测试合格率 <small>QUALITY</small></span><strong>{{data?.tested?data.yield.toFixed(1):'—'}}<em>%</em></strong><p>PASS {{data?.passed??0}} / 有效测试 {{data?.tested??0}}</p><span class="metric-decoration">◎</span></article>
    <article><span>在线工位 <small>STATIONS</small></span><strong>{{online}}<em>/ {{data?.stations.length??0}}</em></strong><p><i class="live-dot"></i>{{state.connected&&state.online?'实时连接正常':'正在连接服务器'}}</p><span class="metric-decoration">⌘</span></article>
  </div>
  <div class="board-grid">
    <section class="board-panel board-orders"><div class="board-panel-heading"><h2>工单执行进度</h2><span>{{data?.orderCount??0}} 张工单</span></div><div v-for="(o,i) in orders" :key="o.id" class="tv-order"><div class="order-caption"><span class="order-index">0{{i+1}}</span><b>{{o.code}}</b><strong>{{percent(o.completed,o.plan).toFixed(1)}}<small>%</small></strong></div><div class="progress"><i :style="{width:percent(o.completed,o.plan)+'%'}"/></div><p><span>已完成 {{o.completed.toLocaleString()}} 件</span><span>计划 {{o.plan.toLocaleString()}}</span></p></div><div v-if="!orders.length" class="board-empty">当前范围暂无生产工单</div><div class="panel-footnote">按最近工单轮播 · {{data?.orders.length??0}} 张可见</div></section>
    <section class="board-panel board-trend"><div class="board-panel-heading"><h2>24 小时生产趋势</h2><span>单位：件 · 北京时间</span></div><BoardChart :option="trend" label="最近24小时真实主码投入和包装完成数量趋势"/></section>
    <section class="board-panel board-quality"><div class="board-panel-heading"><h2>测试质量分布</h2><span>最新有效结果</span></div><div class="quality-chart"><BoardChart :option="quality" label="最新有效测试PASS与FAIL分布"/><div class="quality-center"><strong>{{data?.tested?data.yield.toFixed(1)+'%':'—'}}</strong><span>{{data?.tested?'测试合格率':'等待测试数据'}}</span></div></div><div class="quality-legend"><span><i/>PASS <b>{{data?.passed??0}}</b></span><span><i/>FAIL <b>{{(data?.tested??0)-(data?.passed??0)}}</b></span></div></section>
    <section class="board-panel board-tests"><div class="board-panel-heading"><h2>最新仪器结果</h2><span class="live-label">LIVE</span></div><table><thead><tr><th>产品主码</th><th>测试结果</th><th>接收时间</th></tr></thead><tbody><tr v-for="t in data?.tests.slice(0,4)" :key="t.receivedAt+t.mainCode"><td>{{t.mainCode}}</td><td><span class="result-pill" :class="t.verdict==='PASS'?'pass':'fail'">{{t.verdict}}</span></td><td>{{new Date(t.receivedAt).toLocaleTimeString('zh-CN',{timeZone:'Asia/Shanghai',hour12:false})}}</td></tr></tbody></table><div v-if="!data?.tests.length" class="board-empty">等待仪器自动上传测试结果</div></section>
    <section class="board-panel board-stations"><div class="board-panel-heading"><h2>工位运行状态</h2><span>{{online}} 在线 / {{data?.stations.length??0}} 总计</span></div><div class="station-grid"><div v-for="s in stations" :key="s.code" :class="{isOnline:s.online}"><i/><div><b>{{s.name}}</b><small>{{s.code}}</small></div><span>{{s.online?'在线':'离线'}}</span></div></div><div v-if="!stations.length" class="board-empty">当前范围尚未配置工位</div></section>
    <section class="board-panel board-exceptions"><div class="board-panel-heading"><h2>异常接收动态</h2><span>{{data?.exceptions.length??0}} 条最近记录</span></div><div v-for="(e,i) in data?.exceptions.slice(0,3)" :key="i" class="tv-exception"><span class="exception-mark">!</span><div><b>{{e.device}}</b><p>{{e.reason}}</p></div></div><div v-if="!data?.exceptions.length" class="board-empty success-empty"><span>✓</span>暂无异常接收记录</div></section>
  </div>
  <section class="tv-flow"><div v-for="p in data?.processes" :key="p.id"><span class="flow-number">0{{p.sequence}}</span><div><b>{{p.name}}</b><small>累计完成</small></div><strong>{{p.completed.toLocaleString()}}<small>件</small></strong></div></section>
  <footer><span :class="{'fail':!state.connected||!state.online}"><i class="live-dot"/>{{state.connected&&state.online?'实时连接正常':'连接中断 / 重连中'}} <span class="board-error">{{error}}</span></span><span>最后更新 {{stamp(state.lastUpdate)}} · 15 秒快照校验</span><span>JUXING MES · 生产数据中心</span></footer>
</main>
</template>
