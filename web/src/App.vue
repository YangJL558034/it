<script setup lang="ts">
import {computed,onMounted,ref} from 'vue'
import {ElMessage} from 'element-plus'
import {api,post,state,init,setServer,disconnect} from './api'
import Admin from './views/Admin.vue'
import Station from './views/Station.vue'
const ready=ref(false),settings=ref(false),address=ref(''),account=ref(''),password=ref(''),busy=ref(false)
const mode=ref(location.hash.replace('#/','')||location.pathname.split('/')[1]||'pc')
const isNative=location.protocol==='capacitor:'||location.hostname==='localhost'&&location.port===''
const needsServer=computed(()=>settings.value||isNative&&!state.server)
onMounted(async()=>{await init();address.value=state.server;ready.value=true})
async function saveServer(){busy.value=true;try{await setServer(address.value);settings.value=false;ElMessage.success('数据库及文件服务连接正常')}catch(e:any){ElMessage.error(e.message)}finally{busy.value=false}}
async function login(){busy.value=true;try{const r=await post('/api/auth/login',{account:account.value,password:password.value});if(!r.authenticated)throw Error(r.message);state.token=r.token;state.user=r.user;password.value='';if(r.user.role==='Operator')mode.value='station';if(r.user.role==='TV')mode.value='tv'}catch(e:any){ElMessage.error(e.message)}finally{busy.value=false}}
async function logout(){try{await post('/api/auth/logout')}catch{}await disconnect();state.user=null;state.token=''}
</script>
<template>
  <div v-if="ready">
    <div v-if="needsServer" class="login"><section class="loginbox"><div class="eyebrow">WORKSTATION SETUP</div><h1>连接工厂 MES 服务器</h1><p class="sub">配置地址后验证数据库与文件服务，再登录生产工作台。</p><el-input v-model="address" placeholder="192.168.0.197:8080" @keyup.enter="saveServer"/><el-button type="primary" :loading="busy" @click="saveServer">验证并保存</el-button><el-button v-if="state.server" @click="settings=false">取消</el-button></section></div>
    <div v-else-if="!state.user" class="mes-login"><section class="login-intro"><div class="login-logo">ϟ POWER MES</div><div><div class="eyebrow">MANUFACTURING EXECUTION SYSTEM</div><h1>每一道工序，<br>都有据可循。</h1><p>从主码、电芯、测试到 SN 与包装，<br>让生产执行、质量与追溯紧密连接。</p><div class="login-points">01　强制工序流转<br>02　受控 SOP 作业<br>03　仪器结果自动接入</div></div><small>智造 MES · 生产执行系统</small></section><section class="login-side"><div class="login-card"><div class="eyebrow">WELCOME BACK</div><h1>登录生产工作台</h1><p class="sub">使用已分配的账号进入对应工作空间</p><label>账号<el-input v-model="account" autocomplete="username"/></label><label>密码<el-input v-model="password" type="password" show-password autocomplete="current-password" @keyup.enter="login"/></label><el-button type="primary" :loading="busy" @click="login">登录</el-button><el-button @click="settings=true">服务器设置</el-button><p class="hint">{{state.server||'当前服务器'}} · 账户权限由服务器验证</p></div></section></div>
    <template v-else><header class="topbar"><b>ϟ 智造 MES</b><div class="actions"><el-button v-if="state.user.role!=='TV'&&state.user.role!=='Operator'" @click="mode='pc'">管理后台</el-button><el-button v-if="['Admin','Supervisor','Operator'].includes(state.user.role)" @click="mode='station'">工位终端</el-button><el-button v-if="state.user.role!=='Operator'" @click="mode='tv'">生产大屏</el-button><el-button @click="settings=true">⚙ 设置</el-button><span>{{state.user.name}} · {{state.user.account}}</span><el-button @click="logout">退出</el-button></div></header><Admin v-if="mode==='pc'"/><Station v-else-if="mode==='station'"/><div v-else class="content">生产大屏正在建设。</div></template>
  </div>
</template>
