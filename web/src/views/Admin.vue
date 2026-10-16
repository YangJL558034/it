<script setup lang="ts">
import {computed,onMounted,ref,watch} from 'vue'
import {ElMessage,ElMessageBox} from 'element-plus'
import {api,post,state,stamp} from '../api'
import Quality from './Quality.vue'
import Reports from './Reports.vue'
import Backups from './Backups.vue'
const tab=ref('orders'),catalog=ref<any>({lines:[],stations:[],processes:[],products:[],orders:[],batches:[]}),rows=ref<any[]>([]),dialog=ref(false),form=ref<any>({}),editing=ref('')
const names:Record<string,string>={lines:'产线',stations:'工位',products:'产品',orders:'生产工单',batches:'生产批次',users:'用户与角色',grants:'工位授权',permissions:'角色权限',rules:'条码规则',sop:'SOP 管理',devices:'设备登记',protocols:'仪器协议适配',receipts:'设备接收日志',trace:'完整追溯',messages:'消息发布与确认',reports:'生产报表',backups:'备份恢复与系统配置',audit:'操作审计'}
const fields:Record<string,string[]>={lines:['code','name','workshop'],stations:['code','name','lineId','processStepId'],products:['code','name'],orders:['code','productId','lineId','plan','status'],batches:['code','workOrderId','plan'],users:['account','name','role','password','active'],grants:['userId','stationId'],permissions:['role','permission'],rules:['kind','prefixes','length','charset']}
const labels:Record<string,string>={code:'编码',name:'名称',workshop:'车间',lineId:'产线',processStepId:'工序',productId:'产品',plan:'计划数量',status:'状态',workOrderId:'工单',account:'账号',role:'角色',permission:'权限项',password:'密码',active:'启用',userId:'用户',stationId:'工位',kind:'条码类型',prefixes:'前缀（逗号分隔）',length:'总位数',charset:'字符类型',at:'操作时间',action:'操作内容',outcome:'结果',detail:'详情'}
const users=ref<any[]>([]),search=ref(''),statusFilter=ref(''),page=ref(1),pageSize=ref(10),loading=ref(false)
const groups=[
  {name:'生产管理',items:['orders','batches','lines','stations','products']},
  {name:'质量与工艺',items:['rules','sop','devices','protocols','receipts','trace']},
  {name:'运营中心',items:['reports','messages']},
  {name:'系统管理',items:['users','grants','permissions','backups','audit']}
]
const translations:Record<string,string>={Open:'进行中',Closed:'已关闭',Admin:'管理员',Supervisor:'生产主管',Quality:'质量人员',Operator:'操作员',TV:'大屏账号',main:'主码',cell:'电芯码',sn:'成品 SN',pack:'包装码',alnum:'字母与数字',numeric:'纯数字',printable:'可打印字符',Manage:'基础配置管理',Office:'生产管理与报表',Operate:'工位扫码作业',Board:'大屏查看',ReadProduction:'生产追溯'}
const descriptions:Record<string,string>={orders:'统一安排生产任务，掌握计划与执行状态。',rules:'按条码类型配置前缀、长度和字符范围，发布后立即生效。',users:'管理操作人员身份、角色与账号状态。',backups:'配置工厂信息与数据保护策略，查看备份和恢复记录。',sop:'让每道工序使用经过审核的作业指导。',grants:'为操作人员分配可用工位，生产权限由服务器验证。'}
const filtered=computed(()=>rows.value.filter(row=>(!statusFilter.value||String(row.status??row.active)===statusFilter.value)&&(!search.value||Object.keys(row).filter(k=>!['id','password','passwordHash'].includes(k)).some(k=>String(display(k,row[k])??'').toLowerCase().includes(search.value.toLowerCase())))))
const visibleRows=computed(()=>filtered.value.slice((page.value-1)*pageSize.value,page.value*pageSize.value))
const currentGroup=computed(()=>groups.find(g=>g.items.includes(tab.value))?.name)
const orderPlan=computed(()=>catalog.value.orders.reduce((sum:number,o:any)=>sum+o.plan,0))
const stats=computed(()=>[
 {label:'生产工单',value:catalog.value.orders.length,unit:'张',note:catalog.value.orders.filter((o:any)=>o.status==='Open').length+' 张进行中',tone:'blue'},
 {label:'计划总量',value:orderPlan.value.toLocaleString(),unit:'件',note:'全部已建工单',tone:'teal'},
 {label:'产线 / 工位',value:catalog.value.lines.length+' / '+catalog.value.stations.length,unit:'',note:'已配置生产资源',tone:'violet'},
 {label:'生产批次',value:catalog.value.batches.length,unit:'批',note:'批次独立追溯',tone:'amber'}
])
watch([search,statusFilter,pageSize],()=>page.value=1)
watch(()=>filtered.value.length,()=>page.value=Math.min(page.value,Math.max(1,Math.ceil(filtered.value.length/pageSize.value))))
function selectTab(key:string){tab.value=key;search.value='';statusFilter.value='';page.value=1;load()}
let loadEpoch=0
function options(k:string):any[]{return ({lineId:catalog.value.lines,processStepId:catalog.value.processes,productId:catalog.value.products,workOrderId:catalog.value.orders,stationId:catalog.value.stations,userId:users.value,permission:['Manage','Office','Quality','Operate','Board','ReadProduction'],role:['Admin','Supervisor','Quality','Operator','TV'],status:['Open','Closed'],kind:['main','cell','sn','pack'],charset:['alnum','numeric','printable']} as any)[k]||[]}
function display(k:string,v:any){const opts=options(k);if(k==='at')return stamp(v);if(typeof v==='boolean')return v?'已启用':'已停用';return opts.find((o:any)=>o.id===v)?.name||opts.find((o:any)=>o.id===v)?.code||translations[v]||(Array.isArray(v)?v.join(', '):v)}
async function load(){const epoch=++loadEpoch,selected=tab.value;loading.value=true;try{const [c,r,u]=await Promise.all([api('/api/catalog'),fields[selected]||selected==='audit'?api('/api/admin/'+selected):Promise.resolve([]),state.user?.role==='Admin'?api('/api/admin/users'):Promise.resolve([])]);if(epoch!==loadEpoch)return;catalog.value=c;rows.value=r;users.value=u}catch(e:any){if(epoch===loadEpoch)ElMessage.error(e.message)}finally{if(epoch===loadEpoch)loading.value=false}}
function edit(row?:any){editing.value=row?.id||'';form.value=row?{...row}:{plan:100,status:'Open',role:'Operator',active:true,kind:'main',prefixes:'MC',length:14,charset:'alnum'};if(Array.isArray(form.value.prefixes))form.value.prefixes=form.value.prefixes.join(',');dialog.value=true}
async function save(){try{const body={...form.value};if(tab.value==='rules')body.prefixes=body.prefixes.split(',').map((s:string)=>s.trim()).filter(Boolean);if(editing.value)await api('/api/admin/'+tab.value+'/'+editing.value,{method:'PUT',body:JSON.stringify(body)});else await post('/api/admin/'+tab.value,body);dialog.value=false;await load();ElMessage.success('已保存')}catch(e:any){ElMessage.error(e.message)}}
async function remove(row:any){try{await ElMessageBox.confirm('确认删除？已有生产历史或关联的数据会被服务器拦截。','删除确认');await api('/api/admin/'+tab.value+'/'+row.id,{method:'DELETE'});await load()}catch(e:any){if(e?.message)ElMessage.error(e.message)}}
onMounted(load)

</script>
<template>
  <aside class="sidebar admin-sidebar">
    <div class="workspace-label"><span class="workspace-mark">聚</span><div><b>制造管理中心</b><small>JUXING · MES WORKSPACE</small></div></div>
    <nav class="nav"><div v-for="group in groups" :key="group.name" class="nav-group"><div class="nav-group-label">{{group.name}}</div><button v-for="key in group.items" :key="key" :class="{active:tab===key}" @click="selectTab(key)"><span class="nav-bullet"></span>{{names[key]}}<span v-if="tab===key" class="nav-current">›</span></button></div></nav>
    <div class="navfoot"><span class="dot"></span>生产数据实时同步<small>聚星数据平台 · 制造 MES</small></div>
  </aside>
  <main class="main content admin-content">
    <div class="admin-breadcrumb">工作空间 <span>/</span> {{currentGroup}} <span>/</span> <b>{{names[tab]}}</b></div>
    <div class="heading"><div><h1>{{names[tab]}}</h1><p class="sub">{{descriptions[tab]||'统一管理业务资料，保持生产流程与数据关联准确。'}}</p></div><div class="actions"><el-button :loading="loading" @click="load">刷新数据</el-button><el-button v-if="fields[tab]" type="primary" @click="edit()">＋ 新增{{names[tab]}}</el-button></div></div>
    <div v-if="['orders','batches','lines','stations','products'].includes(tab)" class="admin-overview">
      <article v-for="(s,i) in stats" :key="s.label" :class="s.tone"><div><span>{{s.label}}</span><span class="metric-symbol">{{['▤','↗','⌘','▦'][i]}}</span></div><strong>{{s.value}}<small>{{s.unit}}</small></strong><p>{{s.note}}</p></article>
    </div>
    <Reports v-if="tab==='reports'" :catalog="catalog"/>
    <Backups v-else-if="tab==='backups'"/>
    <Quality v-else-if="['sop','devices','protocols','receipts','trace','messages'].includes(tab)" :tab="tab" :catalog="catalog"/>
    <section v-else class="panel admin-list" v-loading="loading">
      <div class="list-title"><div><h2>{{names[tab]}}列表 <span class="count-pill">{{rows.length}}</span></h2><p class="sub">搜索与筛选当前业务资料</p></div><span class="source-label"><i></i> 已连接数据库</span></div>
      <div class="list-filters"><el-input v-model="search" clearable :placeholder="'搜索'+names[tab]+'编码、名称或关联信息'"><template #prefix><span>⌕</span></template></el-input><el-select v-if="tab==='orders'||tab==='users'" v-model="statusFilter" placeholder="全部状态" clearable><el-option v-for="o in (tab==='orders'?[['Open','进行中'],['Closed','已关闭']]:[['true','已启用'],['false','已停用']])" :key="o[0]" :value="o[0]" :label="o[1]"/></el-select><el-button v-if="search||statusFilter" text @click="search='';statusFilter=''">重置筛选</el-button><span class="filter-count">共 {{filtered.length}} 条结果</span></div>
      <el-table :data="visibleRows" row-key="id" :max-height="'max(260px, calc(100dvh - 680px))'" empty-text="暂无符合条件的数据">
        <el-table-column type="index" label="#" width="56" :index="(i:number)=>(page-1)*pageSize+i+1"/>
        <el-table-column v-for="key in (fields[tab]||['at','action','outcome','detail']).filter(k=>k!=='password')" :key="key" :label="labels[key]||key" :min-width="key==='detail'?260:140" show-overflow-tooltip>
          <template #default="{row}"><el-tag v-if="key==='status'||key==='active'" :type="row[key]==='Open'||row[key]===true?'success':'info'" effect="light" round>{{display(key,row[key])}}</el-tag><span v-else :class="{'table-code':['code','account'].includes(key),'table-number':['plan','length'].includes(key)}">{{display(key,row[key])??'—'}}</span></template>
        </el-table-column>
        <el-table-column v-if="fields[tab]" label="操作" width="154" fixed="right"><template #default="{row}"><el-button v-if="!['grants','rules','permissions'].includes(tab)" link type="primary" @click="edit(row)">修改</el-button><el-button v-if="!['users','rules'].includes(tab)" link type="danger" @click="remove(row)">删除</el-button><span v-if="tab==='rules'" class="sub">已发布</span></template></el-table-column>
      </el-table>
      <div class="list-pagination"><span>第 {{page}} 页 · 每页 {{pageSize}} 条</span><el-pagination v-model:current-page="page" v-model:page-size="pageSize" :page-sizes="[10,20,50]" :total="filtered.length" layout="sizes, prev, pager, next" background/></div>
    </section>
  </main>
  <el-dialog v-model="dialog" :title="(editing?'修改':'新增')+names[tab]" width="600" class="admin-dialog">
    <p class="form-intro">完善以下信息后保存，关联数据将由系统统一校验。</p>
    <el-form label-position="top" class="admin-form"><el-form-item v-for="field in fields[tab]" :key="field" :label="labels[field]"><el-select v-if="options(field).length" v-model="form[field]" filterable placeholder="请选择"><el-option v-for="opt in options(field)" :key="opt.id||opt" :label="opt.name||opt.code||translations[opt]||opt" :value="opt.id||opt"/></el-select><el-input-number v-else-if="['plan','length'].includes(field)" v-model="form[field]" :min="1" controls-position="right"/><el-switch v-else-if="field==='active'" v-model="form[field]"/><el-input v-else v-model="form[field]" :type="field==='password'?'password':'text'" :placeholder="field==='password'&&editing?'留空保持原密码':'请输入'+labels[field]" :show-password="field==='password'"/></el-form-item></el-form>
    <template #footer><el-button @click="dialog=false">取消</el-button><el-button type="primary" @click="save">保存{{names[tab]}}</el-button></template>
  </el-dialog>
</template>
