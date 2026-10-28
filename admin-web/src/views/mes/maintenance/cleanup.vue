<script setup>
import { onMounted, ref } from "vue";
import { ElMessage } from "element-plus";
import * as api from "@/api/mes/maintenance";
import JobProgress from "./JobProgress.vue";
const rows = ref([]),
  selected = ref([]),
  keyword = ref(""),
  busy = ref(false),
  job = ref(),
  dialog = ref(false),
  mode = ref(""),
  preview = ref(),
  password = ref(""),
  phrase = ref(""),
  reason = ref(""),
  saving = ref(false);
const labels = {
  orders: "工单",
  batches: "批次",
  units: "产品实例",
  passages: "过站记录",
  tasks: "测试任务",
  results: "测试结果",
  items: "测试参数",
  barcodes: "条码映射",
  scanRequests: "扫码请求",
  scanContexts: "扫码状态",
  exports: "导出任务",
  legacyGuards: "旧系统历史关联标记",
  marks: "测试标记",
};
async function load() {
  busy.value = true;
  try {
    rows.value = (await api.testOrders(keyword.value)).data;
    selected.value = [];
  } finally {
    busy.value = false;
  }
}
function mark() {
  mode.value = "mark";
  password.value = "";
  phrase.value = "";
  reason.value = "";
  dialog.value = true;
}
async function inspect() {
  preview.value = (
    await api.previewCleanup(selected.value.map((o) => o.id))
  ).data;
  mode.value = "cleanup";
  password.value = "";
  phrase.value = "";
  dialog.value = true;
}
async function execute() {
  saving.value = true;
  try {
    if (mode.value === "mark") {
      await api.markTest({
        orderIds: selected.value.map((o) => o.id),
        reason: reason.value,
        password: password.value,
        phrase: phrase.value,
      });
      ElMessage.success("已标记选中的测试工单");
      await load();
    } else {
      job.value = (
        await api.cleanup({
          planId: preview.value.planId,
          confirmationToken: preview.value.confirmationToken,
          password: password.value,
          phrase: phrase.value,
        })
      ).data;
    }
    password.value = "";
    dialog.value = false;
  } catch {
    password.value = "";
  } finally {
    saving.value = false;
  }
}
onMounted(load);
</script>
<template>
  <div class="app-container cleanup">
    <h1>测试业务数据清理</h1>
    <el-alert
      title="仅清理明确标记、明确选择的测试工单。执行前显示范围并自动备份，不会清空整库，也不会改写旧 .NET 数据库。"
      type="warning"
      :closable="false"
      show-icon
    /><JobProgress :job="job" @finished="load" /><el-card shadow="never"
      ><div class="toolbar">
        <el-input
          v-model="keyword"
          placeholder="输入工单编码筛选"
          clearable
          style="width: 260px"
          @keyup.enter="load"
        /><el-button @click="load">查询</el-button
        ><el-button :disabled="!selected.length" @click="mark"
          >标记选中工单为测试</el-button
        ><el-button
          type="danger"
          plain
          :disabled="!selected.length"
          @click="inspect"
          >预览清理范围</el-button
        ><span>已选 {{ selected.length }} 个工单</span>
      </div>
      <el-table
        v-loading="busy"
        :data="rows"
        row-key="id"
        @selection-change="selected = $event"
        ><el-table-column type="selection" width="50" /><el-table-column
          prop="code"
          label="工单编码"
          min-width="180"
        /><el-table-column prop="plan" label="计划数量" /><el-table-column
          prop="batches"
          label="批次"
        /><el-table-column
          prop="units"
          label="当前若依库产品数"
        /><el-table-column label="标记"
          ><template #default="{ row }"
            ><el-tag :type="row.isTest ? 'warning' : 'info'">{{
              row.isTest ? "已标记测试" : "未标记，禁止清理"
            }}</el-tag></template
          ></el-table-column
        ></el-table
      ></el-card
    >
    <el-dialog
      v-model="dialog"
      :title="mode === 'mark' ? '标记测试工单' : '确认测试数据清理范围'"
      width="700px"
      :close-on-click-modal="false"
      ><el-alert
        type="error"
        :closable="false"
        :title="
          mode === 'mark'
            ? '请确认这些工单确为测试数据。此标记使它们可以进入清理流程。'
            : '清理后只能通过保护备份恢复，请核对范围。'
        "
      />
      <p v-if="mode === 'mark'">
        选中工单：{{ selected.map((o) => o.code).join("、") }}
      </p>
      <template v-else-if="preview"
        ><el-descriptions :column="3" border style="margin: 20px 0"
          ><el-descriptions-item
            v-for="(count, key) in preview.scope.counts"
            :key="key"
            :label="labels[key] || key"
            >{{ count }}</el-descriptions-item
          ></el-descriptions
        >
        <p>保留：{{ preview.preserved.join("；") }}。</p></template
      ><el-form label-position="top"
        ><el-form-item v-if="mode === 'mark'" label="标记原因"
          ><el-input v-model="reason" maxlength="256" /></el-form-item
        ><el-form-item label="当前管理员密码"
          ><el-input
            v-model="password"
            type="password"
            autocomplete="off"
            show-password /></el-form-item
        ><el-form-item
          :label="
            '请输入：' +
            (mode === 'mark' ? '标记为测试工单' : preview?.confirmationPhrase)
          "
          ><el-input v-model="phrase" /></el-form-item></el-form
      ><template #footer
        ><el-button @click="dialog = false">取消</el-button
        ><el-button type="danger" :loading="saving" @click="execute">{{
          mode === "mark" ? "确认标记" : "备份并清理选中范围"
        }}</el-button></template
      ></el-dialog
    >
  </div>
</template>
<style scoped>
.cleanup {
  padding: 28px;
  background: #f3f6fa;
  min-height: 100vh;
}
.cleanup h1 {
  font-size: 25px;
}
.el-card {
  margin-top: 22px;
  border-radius: 10px;
}
.toolbar {
  display: flex;
  gap: 10px;
  align-items: center;
  margin-bottom: 20px;
}
.toolbar > span {
  margin-left: auto;
  font-size: 13px;
  color: var(--el-text-color-secondary);
}
</style>
