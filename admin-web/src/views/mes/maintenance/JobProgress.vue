<script setup>
import { onMounted, onUnmounted, ref, watch } from "vue";
import * as api from "@/api/mes/maintenance";
import { removeToken } from "@/utils/auth";
const props = defineProps({ job: Object }),
  emit = defineEmits(["finished"]),
  current = ref(),
  error = ref("");
let timer;
function stop() {
  clearTimeout(timer);
}
async function poll() {
  if (!current.value?.pollToken) return;
  try {
    const data = await api.poll(current.value.id, current.value.pollToken);
    current.value = { ...data, pollToken: current.value.pollToken };
    error.value = "";
    if (["Completed", "Failed"].includes(data.status)) {
      sessionStorage.removeItem("mes-maintenance-active");
      emit("finished", data);
      return;
    }
  } catch (e) {
    error.value = e.message || "暂时无法获取任务状态";
  }
  timer = setTimeout(poll, 1500);
}
function start(job) {
  if (!job?.id || !job.pollToken) return;
  stop();
  current.value = job;
  sessionStorage.setItem("mes-maintenance-active", JSON.stringify(job));
  poll();
}
watch(() => props.job, start);
onMounted(() => {
  if (props.job) start(props.job);
  else {
    try {
      const job = JSON.parse(sessionStorage.getItem("mes-maintenance-active"));
      if (job) start(job);
    } catch {}
  }
});
onUnmounted(stop);
function login() {
  removeToken();
  location.assign(import.meta.env.BASE_URL + "login");
}
</script>
<template>
  <el-card v-if="current" shadow="never" class="job-progress"
    ><div class="job-title">
      <b>{{
        current.kind === "Restore"
          ? "数据库恢复"
          : current.kind === "TestCleanup"
            ? "测试数据清理"
            : "数据库备份"
      }}</b
      ><el-tag
        :type="
          current.status === 'Completed'
            ? 'success'
            : current.status === 'Failed'
              ? 'danger'
              : 'warning'
        "
        >{{
          {
            Queued: "等待执行",
            Running: "正在执行",
            Completed: "已完成",
            Failed: "失败",
          }[current.status] || current.status
        }}</el-tag
      >
    </div>
    <p>{{ current.progress }}</p>
    <el-alert
      v-if="current.error || error"
      :title="current.error || error"
      type="error"
      :closable="false"
      show-icon
    />
    <p v-if="current.preBackupId" class="hint">
      操作前保护备份：{{ current.preBackupId }}
    </p>
    <p v-if="current.rolledBack">已自动回退至操作前状态。</p>
    <el-button
      v-if="current.kind === 'Restore' && current.status === 'Completed'"
      type="primary"
      @click="login"
      >恢复完成，重新登录</el-button
    ></el-card
  >
</template>
<style scoped>
.job-progress {
  margin: 20px 0;
  border-radius: 10px;
}
.job-title {
  display: flex;
  justify-content: space-between;
  align-items: center;
}
.hint {
  font-size: 12px;
  color: var(--el-text-color-secondary);
  word-break: break-all;
}
</style>
