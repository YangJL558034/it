<script setup>
import { onMounted, ref } from "vue";
import { ElMessage, ElMessageBox } from "element-plus";
import * as api from "@/api/mes/maintenance";
import JobProgress from "./JobProgress.vue";
const rows = ref([]),
  policy = ref({ enabled: false, intervalHours: 24, retentionDays: 7 }),
  busy = ref(false),
  job = ref(),
  preview = ref(),
  dialog = ref(false),
  password = ref(""),
  phrase = ref(""),
  saving = ref(false);
const labels = { Backup: "备份", Restore: "恢复", TestCleanup: "测试清理" },
  statuses = {
    Queued: "等待",
    Running: "执行中",
    Completed: "完成",
    Failed: "失败",
    Deleted: "已删除",
  };
const scopeLabels = {
  mes_work_order: "工单",
  mes_batch: "批次",
  mes_unit: "产品实例",
  mes_passage: "过站",
  mes_test_result: "测试结果",
  mes_sop_version: "SOP 版本",
  sys_user: "账号",
};
async function load() {
  busy.value = true;
  try {
    const [jobs, settings] = await Promise.all([
      api.listJobs(),
      api.settings(),
    ]);
    rows.value = jobs.data;
    policy.value = settings.data;
  } finally {
    busy.value = false;
  }
}
async function backup() {
  job.value = (await api.createBackup()).data;
  await load();
}
async function savePolicy() {
  await api.saveSettings(policy.value);
  ElMessage.success("策略已保存");
  await load();
}
async function inspect(row) {
  saving.value = true;
  try {
    preview.value = (await api.previewRestore(row.id)).data;
    password.value = "";
    phrase.value = "";
    dialog.value = true;
  } finally {
    saving.value = false;
  }
}
async function restore() {
  if (phrase.value !== preview.value.confirmationPhrase || !password.value) {
    ElMessage.warning("请填写当前管理员密码和完整确认文字");
    return;
  }
  saving.value = true;
  try {
    job.value = (
      await api.restore({
        planId: preview.value.planId,
        confirmationToken: preview.value.confirmationToken,
        password: password.value,
        phrase: phrase.value,
      })
    ).data;
    password.value = "";
    dialog.value = false;
  } catch {
    password.value = "";
  } finally {
    saving.value = false;
  }
}
async function remove(row) {
  try {
    await ElMessageBox.confirm(
      "删除此备份档案后将无法使用它恢复。业务数据库及生产记录不会被删除。",
      "删除备份档案",
      { type: "warning" },
    );
  } catch {
    return;
  }
  await api.removeBackup(row.id);
  await load();
}
function finished(data) {
  if (data.kind !== "Restore") load();
}
const date = (value) => (value ? new Date(value).toLocaleString("zh-CN") : "—");
const size = (value) =>
  value ? (value / 1024 / 1024).toFixed(2) + " MB" : "—";
onMounted(load);
</script>
<template>
  <div class="app-container maintenance">
    <header>
      <div>
        <h1>数据库备份与恢复</h1>
        <p>备份 PostgreSQL、SOP、附件与平台 Logo，恢复前自动保存当前状态。</p>
      </div>
      <el-button
        v-hasPermi="['mes:backup:create']"
        type="primary"
        icon="Download"
        @click="backup"
        >立即备份</el-button
      >
    </header>
    <JobProgress :job="job" @finished="finished" /><el-card shadow="never"
      ><template #header><b>自动备份策略</b></template
      ><el-alert
        v-if="policy.toolsAvailable === false"
        title="服务器尚未配置 PostgreSQL 备份工具，请由管理员完成安装配置。"
        type="error"
        :closable="false"
      /><el-form inline label-width="100px"
        ><el-form-item label="定时备份"
          ><el-switch v-model="policy.enabled" /></el-form-item
        ><el-form-item label="间隔小时"
          ><el-input-number
            v-model="policy.intervalHours"
            :min="1"
            :max="720" /></el-form-item
        ><el-form-item label="保留天数"
          ><el-input-number
            v-model="policy.retentionDays"
            :min="1"
            :max="3650" /></el-form-item
        ><el-form-item
          ><el-button v-hasPermi="['mes:backup:config']" @click="savePolicy"
            >保存策略</el-button
          ></el-form-item
        ></el-form
      >
      <p class="hint">
        恢复及测试清理之前生成的保护备份不会自动删除。
      </p></el-card
    ><el-card shadow="never" class="jobs"
      ><template #header
        ><div class="row">
          <b>备份与运维日志</b
          ><el-button icon="Refresh" @click="load">刷新</el-button>
        </div></template
      ><el-table
        v-loading="busy"
        :data="rows"
        empty-text="暂无任务，请创建第一次数据库备份"
        ><el-table-column label="类型" width="95"
          ><template #default="{ row }">{{
            labels[row.kind] || row.kind
          }}</template></el-table-column
        ><el-table-column label="状态" width="100"
          ><template #default="{ row }"
            ><el-tag
              :type="
                row.status === 'Completed'
                  ? 'success'
                  : row.status === 'Failed'
                    ? 'danger'
                    : 'info'
              "
              >{{ statuses[row.status] || row.status }}</el-tag
            ></template
          ></el-table-column
        ><el-table-column
          prop="operatorName"
          label="操作者"
          width="120"
        /><el-table-column label="时间" width="185"
          ><template #default="{ row }">{{
            date(row.createdAt)
          }}</template></el-table-column
        ><el-table-column label="大小" width="110"
          ><template #default="{ row }">{{
            size(row.bytes)
          }}</template></el-table-column
        ><el-table-column label="记录" min-width="220"
          ><template #default="{ row }"
            ><span v-if="row.protected">操作前保护备份 · </span
            >{{ row.error || row.progress || row.id }}</template
          ></el-table-column
        ><el-table-column label="操作" width="225" fixed="right"
          ><template #default="{ row }"
            ><template
              v-if="row.kind === 'Backup' && row.status === 'Completed'"
              ><el-button
                v-hasPermi="['mes:backup:download']"
                link
                type="primary"
                @click="api.downloadBackup(row.id)"
                >下载</el-button
              ><el-button
                v-hasPermi="['mes:backup:restore']"
                link
                type="warning"
                :disabled="saving"
                @click="inspect(row)"
                >校验 / 恢复</el-button
              ><el-button
                v-hasPermi="['mes:backup:remove']"
                link
                type="danger"
                @click="remove(row)"
                >删除档案</el-button
              ></template
            ></template
          ></el-table-column
        ></el-table
      ></el-card
    >
    <el-dialog
      v-model="dialog"
      title="确认整个 MES 数据库与附件恢复范围"
      width="720px"
      :close-on-click-modal="false"
      ><template v-if="preview"
        ><el-alert
          title="恢复将替换当前数据库和附件。所有用户需重新登录，工位端需重新认证。请使用备份当时的账号密码。"
          type="warning"
          :closable="false"
          show-icon />
        <p>
          目标数据库：<b>{{ preview.targetDatabase }}</b
          >　备份时间：{{ date(preview.scope.createdAt) }}
        </p>
        <el-descriptions :column="3" border
          ><el-descriptions-item
            v-for="(count, key) in preview.scope.scope"
            :key="key"
            :label="scopeLabels[key] || key"
            >{{ count }}</el-descriptions-item
          ></el-descriptions
        >
        <p>
          已校验
          {{
            preview.scope.entries.length
          }}
          个数据库和附件条目。恢复前还会备份当前状态；失败时尝试自动回退。
        </p>
        <el-form label-position="top"
          ><el-form-item label="当前管理员密码"
            ><el-input
              v-model="password"
              type="password"
              autocomplete="off"
              show-password /></el-form-item
          ><el-form-item :label="'请输入：' + preview.confirmationPhrase"
            ><el-input v-model="phrase" /></el-form-item></el-form></template
      ><template #footer
        ><el-button @click="dialog = false">取消</el-button
        ><el-button type="danger" :loading="saving" @click="restore"
          >备份当前状态并恢复</el-button
        ></template
      ></el-dialog
    >
  </div>
</template>
<style scoped>
.maintenance {
  background: #f3f6fa;
  min-height: 100vh;
  padding: 28px;
}
header,
.row {
  display: flex;
  justify-content: space-between;
  align-items: center;
}
header {
  margin-bottom: 24px;
}
header h1 {
  font-size: 25px;
}
header p,
.hint {
  font-size: 13px;
  color: var(--el-text-color-secondary);
}
.jobs {
  margin-top: 22px;
}
.el-card {
  border-radius: 10px;
}
.el-form--inline {
  margin-top: 15px;
}
</style>
