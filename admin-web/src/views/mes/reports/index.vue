<script setup>
import { computed, onMounted, onUnmounted, ref } from "vue";
import request from "@/utils/request";
import { ElMessage } from "element-plus";
const options = ref({ orders: [], batches: [] }),
  form = ref({
    orderId: "",
    batchId: "",
    kind: "products",
    keyword: "",
    verdict: "",
  }),
  rows = ref([]),
  jobs = ref([]),
  busy = ref(false);
const batches = computed(() =>
  options.value.batches.filter((b) => b.workOrderId === form.value.orderId),
);
let timer;
async function load() {
  jobs.value = (await request({ url: "/mes/reports/jobs" })).data;
}
async function search() {
  busy.value = true;
  try {
    rows.value = (
      await request({
        url: "/mes/business/reports",
        params: {
          orderId: form.value.orderId || undefined,
          batchId: form.value.batchId || undefined,
          keyword: form.value.keyword,
          verdict: form.value.verdict,
        },
      })
    ).data;
  } finally {
    busy.value = false;
  }
}
async function submit() {
  await request({ url: "/mes/reports/jobs", method: "post", data: form.value });
  ElMessage.success("后台导出任务已创建，范围与操作者已记录");
  await load();
}
async function download(row) {
  const blob = await request({
      url: "/mes/reports/jobs/" + row.id + "/download",
      responseType: "blob",
    }),
    url = URL.createObjectURL(blob),
    a = document.createElement("a");
  a.href = url;
  a.download = row.kind + "-" + row.orderCode + "-" + row.batchCode + ".csv";
  a.click();
  setTimeout(() => URL.revokeObjectURL(url), 1000);
}
onMounted(async () => {
  options.value = (await request({ url: "/mes/business/options" })).data;
  await load();
  timer = setInterval(() => load().catch(() => {}), 5000);
});
onUnmounted(() => clearInterval(timer));
</script>
<template>
  <div class="app-container">
    <h2>生产报表</h2>
    <p class="sub">
      按工单与批次限定导出范围，测试明细包含 PASS、FAIL、复测、参数和原始报文。
    </p>
    <el-card shadow="never"
      ><el-form inline
        ><el-form-item label="工单"
          ><el-select
            v-model="form.orderId"
            filterable
            style="width: 220px"
            @change="form.batchId = ''"
            ><el-option
              v-for="o in options.orders"
              :key="o.id"
              :value="o.id"
              :label="o.code" /></el-select></el-form-item
        ><el-form-item label="批次"
          ><el-select v-model="form.batchId" filterable style="width: 220px"
            ><el-option
              v-for="b in batches"
              :key="b.id"
              :value="b.id"
              :label="b.code" /></el-select></el-form-item
        ><el-form-item label="条码"
          ><el-input
            v-model="form.keyword"
            placeholder="主码 / 电芯 / SN / 包装码" /></el-form-item
        ><el-form-item label="测试结果"
          ><el-select v-model="form.verdict" style="width: 140px"
            ><el-option label="全部结果" value="" /><el-option
              label="PASS"
              value="PASS" /><el-option
              label="FAIL"
              value="FAIL" /></el-select></el-form-item
        ><el-form-item label="导出内容"
          ><el-select v-model="form.kind" style="width: 150px"
            ><el-option label="产品明细" value="products" /><el-option
              label="过站明细"
              value="passages" /><el-option
              label="测试明细（含复测）"
              value="tests" /></el-select></el-form-item
        ><el-form-item
          ><el-button @click="search">查询</el-button
          ><el-button
            v-hasPermi="['mes:reports:export']"
            type="primary"
            :disabled="!form.orderId || !form.batchId"
            @click="submit"
            >创建导出任务</el-button
          ></el-form-item
        ></el-form
      ><el-alert
        title="导出必须明确选择工单和批次。大批量数据在服务器生成，下载不依赖当前页面展示条数。"
        type="info"
        :closable="false" /><el-table v-loading="busy" :data="rows"
        ><el-table-column
          v-for="[key, label] in [
            ['mainCode', '主码'],
            ['cellCode', '电芯码'],
            ['snCode', 'SN'],
            ['packCode', '包装码'],
            ['orderCode', '工单'],
            ['batchCode', '批次'],
          ]"
          :key="key"
          :prop="key"
          :label="label"
          min-width="140" /></el-table></el-card
    ><el-card shadow="never"
      ><h3>导出任务</h3>
      <el-table :data="jobs"
        ><el-table-column prop="orderCode" label="工单" /><el-table-column
          prop="batchCode"
          label="批次"
        /><el-table-column prop="kind" label="内容" /><el-table-column
          prop="userId"
          label="操作者"
        /><el-table-column prop="status" label="状态" /><el-table-column
          prop="at"
          label="创建时间"
          min-width="180"
        /><el-table-column label="操作"
          ><template #default="{ row }"
            ><el-button
              v-hasPermi="['mes:reports:export']"
              link
              type="primary"
              :disabled="row.status !== 'Completed'"
              @click="download(row)"
              >下载</el-button
            ><span v-if="row.error">{{ row.error }}</span></template
          ></el-table-column
        ></el-table
      ></el-card
    >
  </div>
</template>
<style scoped>
.el-card {
  border-radius: 12px;
  margin: 20px 0;
}
.sub {
  color: #8292a5;
}
.el-alert {
  margin-bottom: 20px;
}
</style>
