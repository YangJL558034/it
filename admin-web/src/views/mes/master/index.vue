<script setup>
import {computed,onMounted,ref,watch} from 'vue'
import {useRoute} from 'vue-router'
import {ElMessage,ElMessageBox} from 'element-plus'
import * as api from '@/api/mes/master'
const route=useRoute(),resource=computed(()=>route.path.split('/').filter(Boolean).at(-1))
const configs={
 lines:{title:'产线管理',help:'将产线归属到若依部门，按角色的数据范围控制访问。',fields:['code','name','workshop','deptId']},
 stations:{title:'工位管理',help:'工位必须关联具体产线和工序，已有生产历史的关键关联受到保护。',fields:['code','name','lineId','processStepId']},
 products:{title:'产品管理',help:'维护产品基础资料，已被工单使用的产品禁止随意删除或修改编码。',fields:['code','name','deptId']},
 orders:{title:'生产工单',help:'选择产线、产品并设置计划数量；批次数量与工单计划由服务端统一校验。',fields:['code','lineId','productId','plan','status']},
 batches:{title:'生产批次',help:'每个批次只关联一个工单，批次数量合计不能超过工单计划。',fields:['code','workOrderId','plan']}
}
const labels={code:'编码',name:'名称',workshop:'车间',deptId:'所属部门',lineId:'所属产线',processStepId:'工序',productId:'产品',workOrderId:'生产工单',plan:'计划数量',status:'状态'}
const config=computed(()=>configs[resource.value]),rows=ref([]),total=ref(0),busy=ref(false),keyword=ref(''),pageNum=ref(1),pageSize=ref(20),catalog=ref({}),dialog=ref(false),editing=ref(null),form=ref({}),saving=ref(false),formRef=ref()
const source={deptId:'departments',lineId:'lines',processStepId:'processes',productId:'products',workOrderId:'orders'}
function choices(key){return catalog.value[source[key]]||[]}
function choiceText(row){return [row.code,row.name].filter(Boolean).join(' · ')}
function display(row,key){if(source[key])return choiceText(choices(key).find(x=>String(x.id)===String(row[key]))||{})||'无权限查看关联资料';if(key==='status')return row.status==='Open'?'生产中':'已关闭';return row[key]}
async function load(){if(!config.value)return;busy.value=true;try{const [data,refs]=await Promise.all([api.list(resource.value,{keyword:keyword.value,pageNum:pageNum.value,pageSize:pageSize.value}),api.options(resource.value)]);rows.value=data.rows;total.value=data.total;catalog.value=refs.data}finally{busy.value=false}}
function open(row){editing.value=row?.id||null;form.value=Object.fromEntries(config.value.fields.map(k=>[k,row?.[k]??(k==='plan'?1:k==='status'?'Open':null)]));if(row)form.value.version=row.version;dialog.value=true}
async function save(){await formRef.value.validate();saving.value=true;try{if(editing.value)await api.update(resource.value,editing.value,form.value);else await api.create(resource.value,form.value);ElMessage.success('保存成功');dialog.value=false;await load()}finally{saving.value=false}}
async function del(row){try{await ElMessageBox.confirm('删除后不可在本页撤销。有下游关联或生产历史的资料会被服务器拦截。','删除 '+row.code,{type:'warning'});}catch{return}await api.remove(resource.value,row.id,row.version);ElMessage.success('删除成功');await load()}
watch(resource,()=>{dialog.value=false;keyword.value='';pageNum.value=1;load()});onMounted(load)
</script>
<template>
 <div v-if="config" class="app-container mes-master">
  <section class="mes-heading"><div><span class="mes-kicker">MANUFACTURING / MES</span><h1>{{config.title}}</h1><p>{{config.help}}</p></div><el-button v-hasPermi="['mes:'+resource+':add']" type="primary" icon="Plus" @click="open()">新增{{config.title==='生产工单'?'工单':config.title==='生产批次'?'批次':'资料'}}</el-button></section>
  <el-card shadow="never"><div class="mes-toolbar"><el-input v-model="keyword" placeholder="搜索编码或名称" clearable style="width:280px" @keyup.enter="pageNum=1;load()"/><el-button type="primary" plain icon="Search" @click="pageNum=1;load()">查询</el-button><el-button icon="Refresh" @click="load">刷新</el-button><span>共 {{total}} 条</span></div>
   <el-table v-loading="busy" :data="rows" stripe row-key="id" empty-text="暂无资料，请先新增或迁移已有资料">
    <el-table-column v-for="key in config.fields" :key="key" :label="labels[key]" :min-width="key==='code'?170:145" show-overflow-tooltip><template #default="{row}"><el-tag v-if="key==='status'" :type="row.status==='Open'?'success':'info'">{{display(row,key)}}</el-tag><span v-else>{{display(row,key)}}</span></template></el-table-column>
    <el-table-column label="操作" fixed="right" width="155"><template #default="{row}"><el-button v-hasPermi="['mes:'+resource+':edit']" link type="primary" icon="Edit" @click="open(row)">修改</el-button><el-button v-hasPermi="['mes:'+resource+':remove']" link type="danger" icon="Delete" @click="del(row)">删除</el-button></template></el-table-column>
   </el-table><pagination v-if="total>0" :total="total" v-model:page="pageNum" v-model:limit="pageSize" @pagination="load"/>
  </el-card>
  <el-dialog v-model="dialog" :title="(editing?'修改':'新增')+config.title" width="560px" append-to-body :close-on-click-modal="false">
   <el-form ref="formRef" :model="form" label-width="100px"><el-form-item v-for="key in config.fields" :key="key" :label="labels[key]" :prop="key" :rules="[{required:true,message:'请填写'+labels[key],trigger:'change'}]">
    <el-select v-if="source[key]" v-model="form[key]" filterable :placeholder="'请选择'+labels[key]" style="width:100%"><el-option v-for="item in choices(key)" :key="item.id" :value="item.id" :label="choiceText(item)"/></el-select>
    <el-input-number v-else-if="key==='plan'" v-model="form[key]" :min="1" :max="2147483647" :precision="0"/>
    <el-select v-else-if="key==='status'" v-model="form[key]"><el-option label="生产中" value="Open"/><el-option label="已关闭" value="Closed"/></el-select>
    <el-input v-else v-model="form[key]" maxlength="128" show-word-limit/>
   </el-form-item></el-form><template #footer><el-button @click="dialog=false">取消</el-button><el-button type="primary" :loading="saving" @click="save">保存</el-button></template>
  </el-dialog>
 </div>
</template>
<style scoped>
.mes-master{background:#f3f6fa;min-height:calc(100vh - 100px);padding:28px}.mes-heading{display:flex;align-items:center;justify-content:space-between;margin:0 0 24px}.mes-kicker{font-size:11px;letter-spacing:2px;color:#278b9c;font-weight:700}.mes-heading h1{font-size:25px;margin:10px 0;color:var(--el-text-color-primary)}.mes-heading p{font-size:13px;color:var(--el-text-color-secondary);margin:0;line-height:1.7}.mes-toolbar{display:flex;align-items:center;gap:10px;margin-bottom:20px}.mes-toolbar>span{margin-left:auto;font-size:13px;color:var(--el-text-color-secondary)}.el-card{border-radius:10px}
</style>
