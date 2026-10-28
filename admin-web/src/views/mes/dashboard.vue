<script setup>
import { computed, onMounted, onUnmounted, ref } from "vue";
import { modules } from "@/api/mes/master";
import request from "@/utils/request";
import ChartPane from "./ChartPane.vue";
import useBrandingStore from "@/store/modules/branding";
const branding = useBrandingStore(),
  rows = ref([]),
  board = ref(),
  busy = ref(false),
  line = ref(""),
  error = ref(""),
  updated = ref("");
let timer;
const groups = computed(() => [...new Set(rows.value.map((r) => r.groupName))]);
const online = computed(
  () => board.value?.stations.filter((s) => s.online).length || 0,
);
const percent = (n, total) => (total ? Math.min(100, (n / total) * 100) : 0);
const trend = computed(() => ({
  color: ["#278eab", "#4fc3a6"],
  tooltip: { trigger: "axis" },
  legend: { top: 0, right: 0 },
  grid: { left: 44, right: 20, top: 40, bottom: 25 },
  xAxis: {
    type: "category",
    boundaryGap: false,
    data: (board.value?.hourly || []).map((h) =>
      new Date(h.at).toLocaleTimeString("zh-CN", {
        hour: "2-digit",
        minute: "2-digit",
        hour12: false,
      }),
    ),
    axisLine: { lineStyle: { color: "#dbe4ed" } },
    axisLabel: { color: "#8494a5", interval: 3 },
  },
  yAxis: {
    type: "value",
    minInterval: 1,
    splitLine: { lineStyle: { color: "#edf2f7" } },
    axisLabel: { color: "#8494a5" },
  },
  series: [
    {
      name: "主码投入",
      type: "line",
      smooth: true,
      showSymbol: false,
      areaStyle: { opacity: 0.08 },
      data: (board.value?.hourly || []).map((h) => h.started),
    },
    {
      name: "包装完成",
      type: "line",
      smooth: true,
      showSymbol: false,
      areaStyle: { opacity: 0.05 },
      data: (board.value?.hourly || []).map((h) => h.completed),
    },
  ],
}));
const quality = computed(() => ({
  color: ["#36b393", "#eb7f80"],
  tooltip: { trigger: "item" },
  series: [
    {
      type: "pie",
      radius: ["62%", "80%"],
      label: { show: false },
      emptyCircleStyle: { color: "#edf2f6" },
      data: board.value?.tested
        ? [
            { name: "PASS", value: board.value.passed },
            { name: "FAIL", value: board.value.tested - board.value.passed },
          ]
        : [],
    },
  ],
}));
async function load() {
  busy.value = true;
  try {
    const [catalog, snapshot] = await Promise.all([
      modules(),
      request({
        url: "/api/tv",
        params: line.value ? { lineId: line.value } : {},
      }),
    ]);
    rows.value = catalog.data;
    board.value = snapshot;
    updated.value = new Date(snapshot.at).toLocaleString("zh-CN");
    error.value = "";
  } catch (e) {
    error.value = e.message || "数据暂不可用";
  } finally {
    busy.value = false;
  }
}
onMounted(() => {
  load();
  timer = setInterval(load, 15000);
});
onUnmounted(() => clearInterval(timer));
</script>
<template>
  <div class="mes-dashboard">
    <header>
      <div>
        <div class="eyebrow">PRODUCTION OPERATIONS / MES</div>
        <h1>MES 生产总览</h1>
        <p>{{ branding.name }} · 当前若依库生产数据</p>
      </div>
      <div class="actions">
        <el-select
          v-model="line"
          placeholder="全部产线"
          clearable
          filterable
          style="width: 220px"
          @change="load"
          ><el-option
            v-for="item in board?.lines"
            :key="item.id"
            :label="item.name + ' · ' + item.code"
            :value="item.id" /></el-select
        ><el-button icon="Refresh" :loading="busy" @click="load"
          >刷新</el-button
        >
      </div>
    </header>
    <el-alert v-if="error" :title="error" type="error" :closable="false" />
    <section class="metrics">
      <el-card shadow="never"
        ><span>主码投入</span
        ><strong>{{ board?.started ?? "—" }}<em>件</em></strong
        ><small
          >{{ board?.orderCount ?? 0 }} 张工单 · 计划
          {{ board?.planned ?? 0 }}</small
        ></el-card
      ><el-card shadow="never"
        ><span>包装完成</span
        ><strong>{{ board?.completed ?? "—" }}<em>件</em></strong
        ><small
          >完成率
          {{ percent(board?.completed, board?.planned).toFixed(1) }}%</small
        ></el-card
      ><el-card shadow="never"
        ><span>最新有效测试合格率</span
        ><strong class="green"
          >{{ board?.tested ? board.yield.toFixed(1) : "—" }}<em>%</em></strong
        ><small
          >PASS {{ board?.passed ?? 0 }} / 有效测试
          {{ board?.tested ?? 0 }}</small
        ></el-card
      ><el-card shadow="never"
        ><span>在线工位</span
        ><strong
          >{{ online }}<em>/ {{ board?.stations.length ?? 0 }}</em></strong
        ><small>最近 90 秒内有效心跳</small></el-card
      >
    </section>
    <section class="charts">
      <el-card shadow="never"
        ><template #header
          ><div class="panel-title">
            <b>24 小时生产趋势</b><span>主码投入与包装完成 · 单位：件</span>
          </div></template
        >
        <div style="height: 260px"><ChartPane :option="trend" /></div></el-card
      ><el-card shadow="never"
        ><template #header><b>测试质量分布</b></template>
        <div class="quality">
          <ChartPane :option="quality" />
          <div class="quality-center">
            <strong>{{
              board?.tested ? board.yield.toFixed(1) + "%" : "—"
            }}</strong
            ><span>{{ board?.tested ? "测试合格率" : "等待测试数据" }}</span>
          </div>
        </div></el-card
      >
    </section>
    <el-card shadow="never" class="flow"
      ><template #header><b>五道生产工序</b></template>
      <div class="flow-grid">
        <article v-for="step in board?.processes" :key="step.id">
          <span>0{{ step.sequence }}</span>
          <div>
            <b>{{ step.name }}</b
            ><small>累计完成 {{ step.completed }} 件</small>
          </div>
        </article>
      </div></el-card
    >
    <section class="details">
      <el-card shadow="never"
        ><template #header><b>工单执行进度</b></template>
        <div
          v-for="order in board?.orders.slice(0, 5)"
          :key="order.id"
          class="order"
        >
          <div>
            <b>{{ order.code }}</b
            ><span>{{ order.completed }} / {{ order.plan }}</span>
          </div>
          <el-progress
            :percentage="
              Math.round(percent(order.completed, order.plan) * 10) / 10
            "
            :stroke-width="7"
          />
        </div>
        <el-empty
          v-if="!board?.orders.length"
          description="当前范围暂无工单" /></el-card
      ><el-card shadow="never"
        ><template #header><b>最新仪器结果</b></template
        ><el-table
          :data="board?.tests || []"
          max-height="280"
          empty-text="等待仪器自动上传"
          ><el-table-column prop="mainCode" label="产品主码" /><el-table-column
            label="结果"
            width="90"
            ><template #default="{ row }"
              ><el-tag :type="row.verdict === 'PASS' ? 'success' : 'danger'">{{
                row.verdict
              }}</el-tag></template
            ></el-table-column
          ><el-table-column label="时间" width="100"
            ><template #default="{ row }">{{
              new Date(row.receivedAt).toLocaleTimeString("zh-CN")
            }}</template></el-table-column
          ></el-table
        ></el-card
      >
    </section>
    <el-collapse class="migration-status"
      ><el-collapse-item title="功能接入状态" name="migration"
        ><el-alert
          title="完整生产历史与部分业务仍在迁移，以下状态不代表全部功能已完成。"
          type="info"
          :closable="false"
        />
        <section v-for="group in groups" :key="group" class="module-group">
          <h2>{{ group }}</h2>
          <div class="modules">
            <button
              v-for="item in rows.filter((r) => r.groupName === group)"
              :key="item.key"
              class="module"
              @click="$router.push(item.route)"
            >
              <div>
                <b>{{ item.title }}</b
                ><el-tag
                  :type="item.status === 'available' ? 'success' : 'warning'"
                  size="small"
                  >{{
                    item.status === "available" ? "已接入" : "迁移中"
                  }}</el-tag
                >
              </div>
              <p>{{ item.description }}</p>
              <span>打开模块 →</span>
            </button>
          </div>
        </section></el-collapse-item
      ></el-collapse
    >
    <footer>
      来自当前若依服务 · 最后更新 {{ updated || "加载中" }} · 15 秒刷新
    </footer>
  </div>
</template>
<style scoped>
.mes-dashboard {
  padding: 28px;
  background: #f3f6fa;
  min-height: 100vh;
}
header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  margin-bottom: 24px;
}
.eyebrow {
  color: #278da4;
  font-size: 11px;
  letter-spacing: 2px;
  font-weight: 700;
}
h1 {
  font-size: 28px;
  margin: 12px 0;
  color: #1c3a52;
}
header p,
footer {
  color: #8494a5;
  font-size: 13px;
}
.actions {
  display: flex;
  gap: 12px;
}
.metrics {
  display: grid;
  grid-template-columns: repeat(4, 1fr);
  gap: 18px;
  margin-bottom: 22px;
}
.el-card {
  border-radius: 12px;
}
.metrics span {
  font-size: 13px;
  color: #7c8da0;
}
.metrics strong {
  display: block;
  font-size: 34px;
  color: #183d57;
  margin: 17px 0;
}
.metrics strong.green {
  color: #249e85;
}
.metrics em {
  font-size: 16px;
  font-style: normal;
  margin-left: 8px;
  color: #90a0b0;
}
.metrics small {
  font-size: 12px;
  color: #8797a8;
}
.charts {
  display: grid;
  grid-template-columns: 2fr 1fr;
  gap: 20px;
}
.panel-title {
  display: flex;
  justify-content: space-between;
  align-items: center;
}
.panel-title > span {
  font-size: 12px;
  color: #90a0b0;
}
.quality {
  position: relative;
  height: 260px;
}
.quality-center {
  position: absolute;
  inset: 0;
  display: flex;
  flex-direction: column;
  justify-content: center;
  align-items: center;
  pointer-events: none;
}
.quality-center strong {
  font-size: 30px;
  color: #269d85;
}
.quality-center span {
  margin-top: 8px;
  font-size: 12px;
  color: #90a0b0;
}
.flow {
  margin-top: 22px;
}
.flow-grid {
  display: grid;
  grid-template-columns: repeat(5, 1fr);
  gap: 16px;
}
.flow-grid article {
  display: flex;
  align-items: center;
  gap: 14px;
  padding: 10px 8px;
}
.flow-grid article > span {
  font-size: 26px;
  font-weight: 700;
  color: #bed9e3;
}
.flow-grid small {
  display: block;
  margin-top: 7px;
  color: #8a9cad;
  font-size: 12px;
}
.details {
  display: grid;
  grid-template-columns: 1fr 1fr;
  gap: 20px;
  margin-top: 22px;
}
.order {
  margin-bottom: 18px;
}
.order > div {
  display: flex;
  justify-content: space-between;
  margin-bottom: 9px;
  font-size: 13px;
}
.order span {
  color: #8797a8;
}
.migration-status {
  margin-top: 22px;
  background: white;
  padding: 0 20px;
  border-radius: 10px;
}
.module-group {
  margin: 24px 0;
}
.module-group h2 {
  font-size: 16px;
}
.modules {
  display: grid;
  grid-template-columns: repeat(3, 1fr);
  gap: 14px;
}
.module {
  text-align: left;
  background: white;
  border: 1px solid #e4ebf2;
  border-radius: 10px;
  padding: 20px;
  cursor: pointer;
}
.module:hover {
  border-color: #3099a9;
}
.module > div {
  display: flex;
  gap: 12px;
  justify-content: space-between;
  align-items: center;
}
.module p {
  font-size: 13px;
  color: #8a99aa;
  line-height: 1.8;
  min-height: 46px;
}
.module > span {
  font-size: 12px;
  color: #268e9e;
}
footer {
  margin-top: 24px;
}
@media (max-width: 1100px) {
  .metrics {
    grid-template-columns: repeat(2, 1fr);
  }
  .charts,
  .details {
    grid-template-columns: 1fr;
  }
  .flow-grid {
    grid-template-columns: repeat(3, 1fr);
  }
  .modules {
    grid-template-columns: repeat(2, 1fr);
  }
}
</style>
