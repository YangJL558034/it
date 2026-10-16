<script setup>
import {computed,onMounted,ref} from 'vue'
import {useRoute} from 'vue-router'
import {modules} from '@/api/mes/master'
const route=useRoute(),items=ref([]),busy=ref(true)
const current=computed(()=>items.value.find(m=>m.route===route.path))
onMounted(async()=>{try{items.value=(await modules()).data}finally{busy.value=false}})
</script>
<template><div v-loading="busy" class="app-container"><el-card v-if="current" shadow="never"><div class="module-head"><h1>{{current.title}}</h1><el-tag type="warning">正在迁移至若依</el-tag></div><p>{{current.description}}</p><el-alert title="该业务尚未完成 Java 接口与历史数据迁移，目前不可在若依中操作。" type="warning" :closable="false" show-icon/><p class="module-note">现有 MES 源码、业务记录和附件已保留。迁移完成并通过工序、权限及追溯验证后开放操作。</p><el-button @click="$router.push('/index')">返回 MES 工作台</el-button></el-card></div></template>
<style scoped>.module-head{display:flex;gap:20px;align-items:center}.module-head h1{font-size:24px}.module-note{color:var(--el-text-color-secondary);line-height:1.8;margin:24px 0}.el-card{max-width:1100px;margin:20px auto;border-radius:12px}p{line-height:1.8}</style>
