<script setup lang="ts">
import {
  computed,
  onMounted,
  onUnmounted,
  watch,
  ref,
  nextTick,
  defineAsyncComponent,
} from "vue";
import { ElMessage } from "element-plus";
import { Preferences } from "@capacitor/preferences";
import {
  post,
  state,
  init,
  setServer,
  disconnect,
  acceptLogin,
  forgetStation,
  nativeClient,
} from "./api";
import { startBranding, stopBranding } from "./branding";
import BrandMark from "./components/BrandMark.vue";
const Station = defineAsyncComponent(() => import("./views/Station.vue"));
const Tv = defineAsyncComponent(() => import("./views/Tv.vue"));
const CachedSop = defineAsyncComponent(
  () => import("./components/SopPlayer.vue"),
);
const cachedProcess = ref<string>(),
  offlineSopOpen = ref(false);
async function loadCachedSelection() {
  try {
    const raw = (await Preferences.get({ key: "mes.station.selection" })).value;
    if (raw) {
      const saved = JSON.parse(raw);
      cachedProcess.value =
        saved.server === (state.server || location.origin)
          ? saved.processId
          : undefined;
    }
  } catch {}
}
const mode =
  (location.hash.replace("#/", "") || location.pathname.split("/")[1]) === "tv"
    ? "tv"
    : "station";
const ready = ref(false),
  settings = ref(false),
  address = ref(""),
  account = ref(""),
  password = ref(""),
  busy = ref(false);
const stationLogin = ref(false),
  stationView = ref<{ cancelGuestScan(): void }>();
const isNative = nativeClient,
  needsServer = computed(
    () => settings.value || (isNative && (!state.server || !state.compatible)),
  );
function cancelStationLogin() {
  password.value = "";
  if (!state.user) stationView.value?.cancelGuestScan();
}
onMounted(async () => {
  try {
    await init(mode === "station");
  } catch (e: any) {
    ElMessage.error("启动配置读取失败：" + e.message);
  } finally {
    address.value = state.server;
    await loadCachedSelection();
    ready.value = true;
    startBranding(state.server || location.origin);
    await nextTick();
    window.mesStartup?.ready();
  }
});
watch(settings, (open) => {
  if (open) void loadCachedSelection();
});
watch(
  () => state.server,
  (value) => startBranding(value || location.origin),
);
onUnmounted(stopBranding);
async function saveServer() {
  busy.value = true;
  try {
    await setServer(address.value);
    settings.value = false;
    ElMessage.success("若依 MES 数据库及文件服务连接正常");
  } catch (e: any) {
    ElMessage.error(e.message);
  } finally {
    busy.value = false;
  }
}
async function login() {
  busy.value = true;
  try {
    const r = await post("/api/auth/station/login", {
      account: account.value,
      password: password.value,
      rememberStation: true,
      native: nativeClient,
    });
    if (!r.authenticated) throw Error(r.message);
    await acceptLogin(r, true);
    password.value = "";
    stationLogin.value = false;
  } catch (e: any) {
    ElMessage.error(e.message);
  } finally {
    busy.value = false;
  }
}
async function logout() {
  try {
    await post("/api/auth/logout");
  } catch {}
  await forgetStation();
  await disconnect();
  state.user = null;
  state.token = "";
}
</script>
<template>
  <div v-if="ready" :class="'app-shell mode-' + mode">
    <div v-if="needsServer" class="login connection-screen">
      <section class="loginbox connection-card">
        <BrandMark />
        <h1>连接工厂 MES 服务器</h1>
        <p class="sub">填写服务器的局域网地址和服务端口。</p>
        <el-alert
          v-if="state.serverProblem"
          :title="state.serverProblem"
          type="warning"
          :closable="false"
        /><el-input
          v-model="address"
          placeholder="例如 192.168.0.208:5081"
          @keyup.enter="saveServer"
        />
        <div class="connection-actions">
          <el-button type="primary" :loading="busy" @click="saveServer"
            >验证并保存</el-button
          ><el-button
            v-if="state.server && state.compatible"
            @click="settings = false"
            >取消</el-button
          ><el-button
            v-if="mode === 'station' && cachedProcess"
            @click="offlineSopOpen = true"
            >查看缓存 SOP</el-button
          >
        </div>
      </section>
    </div>
    <template v-else
      ><header v-if="mode !== 'tv'" class="topbar">
        <BrandMark />
        <div class="actions">
          <el-button @click="settings = true">⚙ 设置</el-button
          ><span>{{
            state.user ? state.user.name + " · " + state.user.account : "未登录"
          }}</span
          ><el-button v-if="state.user" @click="logout">退出</el-button
          ><el-button v-else @click="stationLogin = true">登录</el-button>
        </div>
      </header>
      <Station
        v-if="mode === 'station'"
        ref="stationView"
        @request-login="stationLogin = true" /><Tv
        v-else
        @settings="settings = true"
    /></template>
    <el-dialog
      class="terminal-dialog station-auth-dialog"
      v-model="stationLogin"
      title="登录后开始扫码作业"
      width="440"
      :close-on-click-modal="false"
      :close-on-press-escape="!busy"
      :show-close="!busy"
      @closed="cancelStationLogin"
      ><p class="sub">
        使用后台分配的若依账号。此终端会记住登录；退出、账号停用或修改密码后需重新认证。
      </p>
      <el-form label-position="top" @submit.prevent="login"
        ><el-form-item label="账号"
          ><el-input v-model="account" autocomplete="username" /></el-form-item
        ><el-form-item label="密码"
          ><el-input
            v-model="password"
            type="password"
            show-password
            autocomplete="current-password"
            @keydown.enter.prevent="login" /></el-form-item></el-form
      ><template #footer
        ><el-button :disabled="busy" @click="stationLogin = false"
          >取消</el-button
        ><el-button type="primary" :loading="busy" @click="login"
          >登录并继续</el-button
        ></template
      ></el-dialog
    >
    <el-dialog
      v-model="offlineSopOpen"
      class="terminal-dialog"
      title="离线 SOP · 仅查看缓存，禁止过站"
      width="900"
      destroy-on-close
      ><CachedSop
        v-if="cachedProcess && offlineSopOpen"
        :process-id="cachedProcess"
        offline-only
    /></el-dialog>
  </div>
</template>
