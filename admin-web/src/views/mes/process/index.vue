<script setup>
import {onMounted,ref} from 'vue'
import {processes} from '@/api/mes/master'
const rows=ref([])
onMounted(async()=>rows.value=(await processes()).data)
</script>
<template><div class="app-container"><el-card shadow="never"><h1>工艺路线</h1><p>按顺序完成五道工序。工序编号与工位、SOP、过站历史通过数据库关系关联。</p><el-steps :active="-1" align-center style="margin:40px 0"><el-step v-for="row in rows" :key="row.id" :title="row.name" :description="'第 '+row.sequence+' 道工序'"/></el-steps><el-table :data="rows" empty-text="尚未导入原有工序"><el-table-column prop="sequence" label="顺序"/><el-table-column prop="name" label="工序名称"/><el-table-column prop="id" label="关联编号"/></el-table><el-alert title="当前提供工序资料查询；单入口扫码和防跳站逻辑仍在迁移，尚未开放若依过站。" type="info" :closable="false" style="margin-top:24px"/></el-card></div></template>
