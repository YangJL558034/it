<script setup>
import { computed, onMounted, onUnmounted, ref } from "vue";
import request from "@/utils/request";
const data = ref(),
  error = ref(""),
  busy = ref(false);
let timer;
const format = (n) =>
  n >= 1073741824
    ? (n / 1073741824).toFixed(2) + " GB"
    : n >= 1048576
      ? (n / 1048576).toFixed(2) + " MB"
      : (n / 1024).toFixed(1) + " KB";
const hit = computed(() => {
  const s = data.value?.statistics;
  if (!s || !(Number(s.hits) + Number(s.reads))) return "—";
  return (
    ((100 * Number(s.hits)) / (Number(s.hits) + Number(s.reads))).toFixed(2) +
    "%"
  );
});
async function load() {
  busy.value = true;
  try {
    data.value = (await request({ url: "/mes/monitor/database" })).data;
    error.value = "";
  } catch (e) {
    error.value = e.message || "数据库监控暂不可用";
  } finally {
    busy.value = false;
  }
}
onMounted(() => {
  load();
  timer = setInterval(load, 10000);
});
onUnmounted(() => clearInterval(timer));
</script>
<template>
  <div class="app-container db-monitor">
    <header>
      <div>
        <h1>数据库监控</h1>
        <p>PostgreSQL 实时状态与应用连接池 · 每 10 秒刷新</p>
      </div>
      <el-button icon="Refresh" :loading="busy" @click="load"
        >立即刷新</el-button
      >
    </header>
    <el-alert
      v-if="error"
      :title="error"
      type="error"
      :closable="false"
    /><template v-if="data"
      ><div class="metrics">
        <el-card shadow="never"
          ><span>数据库容量</span
          ><strong>{{ format(data.database.bytes) }}</strong
          ><small
            >{{ data.database.name }} · PostgreSQL
            {{ data.database.version }}</small
          ></el-card
        ><el-card shadow="never"
          ><span>当前数据库连接</span
          ><strong
            >{{ data.statistics.connections
            }}<em>/ {{ data.database.maxConnections }}</em></strong
          ><small>探测延迟 {{ data.latencyMs }} ms</small></el-card
        ><el-card shadow="never"
          ><span>数据库缓存命中率</span><strong>{{ hit }}</strong
          ><small>从数据库统计重置时刻累计</small></el-card
        ><el-card shadow="never"
          ><span>磁盘可用空间</span
          ><strong>{{ format(data.disk.usableBytes) }}</strong
          ><small
            >死锁 {{ data.statistics.deadlocks }} · 回滚
            {{ data.statistics.rollbacks }}</small
          ></el-card
        >
      </div>
      <el-card shadow="never"
        ><el-tabs
          ><el-tab-pane label="数据表"
            ><el-alert
              title="估算行数来自 PostgreSQL 统计信息，可能滞后；不作为生产计数。"
              type="info"
              :closable="false" /><el-table
              :data="data.tables"
              stripe
              max-height="550"
              ><el-table-column
                prop="name"
                label="数据表"
                min-width="240" /><el-table-column
                prop="estimatedRows"
                label="估算行数" /><el-table-column
                prop="deadRows"
                label="待回收行" /><el-table-column label="总大小"
                ><template #default="{ row }">{{
                  format(row.bytes)
                }}</template></el-table-column
              ><el-table-column
                prop="sequentialScans"
                label="顺序扫描" /><el-table-column
                prop="indexScans"
                label="索引扫描" /></el-table></el-tab-pane
          ><el-tab-pane label="数据库会话"
            ><el-table :data="data.sessions"
              ><el-table-column prop="state" label="状态" /><el-table-column
                prop="waitType"
                label="等待类型" /><el-table-column
                prop="count"
                label="连接数" /></el-table></el-tab-pane
          ><el-tab-pane label="应用连接池"
            ><el-descriptions border :column="3"
              ><el-descriptions-item label="使用中">{{
                data.pool.active
              }}</el-descriptions-item
              ><el-descriptions-item label="空闲">{{
                data.pool.idle
              }}</el-descriptions-item
              ><el-descriptions-item label="上限">{{
                data.pool.max
              }}</el-descriptions-item
              ><el-descriptions-item label="累计建立">{{
                data.pool.created
              }}</el-descriptions-item
              ><el-descriptions-item label="连接错误">{{
                data.pool.errors
              }}</el-descriptions-item
              ><el-descriptions-item label="累计提交">{{
                data.statistics.commits
              }}</el-descriptions-item></el-descriptions
            ></el-tab-pane
          ></el-tabs
        ></el-card
      >
      <footer>
        最后更新：{{ new Date(data.at).toLocaleString("zh-CN") }}
      </footer></template
    >
  </div>
</template>
<style scoped>
.db-monitor {
  padding: 28px;
  background: #f3f6fa;
  min-height: 100vh;
}
header {
  display: flex;
  justify-content: space-between;
  align-items: center;
  margin-bottom: 24px;
}
h1 {
  font-size: 25px;
}
header p,
footer {
  font-size: 13px;
  color: var(--el-text-color-secondary);
}
.metrics {
  display: grid;
  grid-template-columns: repeat(4, 1fr);
  gap: 16px;
  margin-bottom: 24px;
}
.el-card {
  border-radius: 10px;
}
.metrics span {
  color: var(--el-text-color-secondary);
  font-size: 13px;
}
.metrics strong {
  display: block;
  font-size: 29px;
  margin: 20px 0;
  color: #193e58;
}
.metrics em {
  font-size: 16px;
  font-style: normal;
  color: #8494a8;
}
.metrics small {
  font-size: 12px;
  color: #8494a8;
}
footer {
  margin-top: 22px;
}
@media (max-width: 1000px) {
  .metrics {
    grid-template-columns: repeat(2, 1fr);
  }
}
</style>
