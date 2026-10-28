<script setup>
import { computed, onMounted, ref, watch } from "vue";
import { useRoute } from "vue-router";
import request from "@/utils/request";
import { ElMessage, ElMessageBox } from "element-plus";
const route = useRoute(),
  module = computed(() => route.path.split("/").pop()),
  rows = ref([]),
  options = ref({ users: [], stations: [], lines: [] }),
  busy = ref(false),
  dialog = ref(false),
  keyword = ref(""),
  form = ref({}),
  detail = ref(),
  detailOpen = ref(false),
  credential = ref("");
const names = {
  trace: "产品追溯",
  quality: "质量检验与复测",
  devices: "设备与测试仪",
  protocols: "仪器协议适配",
  receipts: "接口接收日志",
  grants: "工位授权",
  barcodes: "条码规则",
  messages: "消息中心",
};
const editable = computed(() =>
  ["devices", "grants", "barcodes", "messages"].includes(module.value),
);
const columns = computed(
  () =>
    ({
      trace: [
        ["mainCode", "主码"],
        ["cellCode", "电芯码"],
        ["snCode", "SN"],
        ["packCode", "包装码"],
        ["orderCode", "工单"],
        ["batchCode", "批次"],
      ],
      quality: [
        ["mainCode", "主码"],
        ["snCode", "SN"],
        ["orderCode", "工单"],
        ["batchCode", "批次"],
      ],
      devices: [
        ["code", "设备编号"],
        ["name", "名称"],
        ["station", "工位"],
        ["protocol", "协议"],
        ["programVersion", "程序版本"],
        ["active", "启用"],
        ["lastSeen", "最近心跳"],
      ],
      protocols: [
        ["code", "适配器"],
        ["name", "名称"],
        ["status", "状态"],
      ],
      receipts: [
        ["device", "设备"],
        ["operation", "操作"],
        ["recordId", "记录编号"],
        ["status", "状态"],
        ["reason", "处理原因"],
        ["at", "接收时间"],
      ],
      grants: [
        ["userName", "用户"],
        ["station", "授权工位"],
      ],
      barcodes: [
        ["kind", "条码类型"],
        ["version", "版本"],
        ["prefixes", "允许前缀"],
        ["length", "总长度"],
        ["charset", "字符类型"],
        ["publishedAt", "发布时间"],
      ],
      messages: [
        ["title", "标题"],
        ["body", "内容"],
        ["important", "重要"],
        ["acknowledged", "已确认人数"],
        ["at", "发布时间"],
      ],
    })[module.value] || [],
);
const requestList = () =>
  request({
    url: "/mes/business/" + module.value,
    params: { keyword: keyword.value },
  });
async function load() {
  busy.value = true;
  try {
    rows.value = (await requestList()).data;
    if (["grants", "devices", "messages"].includes(module.value))
      options.value = (await request({ url: "/mes/business/options" })).data;
  } finally {
    busy.value = false;
  }
}
function create() {
  form.value =
    module.value === "barcodes"
      ? { kind: "main", length: 14, charset: "alnum", prefixesText: "" }
      : module.value === "devices"
        ? {
            code: "",
            name: "",
            stationId: "",
            programVersion: "",
            protocol: "generic-v1",
            requiredItems: [
              { name: "", unit: "", lowerLimit: 0, upperLimit: 1 },
            ],
          }
        : module.value === "grants"
          ? { userId: null, stationId: "", enabled: true }
          : { title: "", body: "", important: false, lineId: null };
  dialog.value = true;
}
async function save() {
  let data = { ...form.value };
  if (module.value === "barcodes") {
    data.prefixes = data.prefixesText
      .split(/[,，\n]/)
      .map((s) => s.trim())
      .filter(Boolean);
    delete data.prefixesText;
  }
  const r = await request({
    url: "/mes/business/" + module.value,
    method: "post",
    data,
  });
  if (r.data?.deviceKey) {
    credential.value = r.data.deviceKey;
    await ElMessageBox.alert(
      "设备凭证仅显示一次，请安全保存：\n" + credential.value,
      "设备凭证",
      { confirmButtonText: "已保存", closeOnClickModal: false },
    );
  }
  dialog.value = false;
  ElMessage.success("已保存");
  await load();
}
async function inspect(row) {
  detail.value = ["trace", "quality"].includes(module.value)
    ? (await request({ url: "/mes/business/trace/" + row.id })).data
    : row;
  detailOpen.value = true;
}
async function active(row) {
  await request({
    url: "/mes/business/devices/" + row.id + "/active",
    method: "put",
    data: { active: !row.active },
  });
  await load();
}
function show(value) {
  return Array.isArray(value)
    ? value.join("、")
    : typeof value === "boolean"
      ? value
        ? "是"
        : "否"
      : (value ?? "—");
}
watch(module, load);
onMounted(load);
</script>
<template>
  <div class="app-container business-page">
    <div class="business-head">
      <div>
        <h2>{{ names[module] }}</h2>
        <p>业务记录由服务端校验并保存，按若依权限与数据范围显示。</p>
      </div>
      <el-button
        v-if="editable"
        v-hasPermi="['mes:' + module + ':edit']"
        type="primary"
        @click="create"
        >{{
          module === "barcodes"
            ? "发布新版本"
            : module === "grants"
              ? "调整授权"
              : "新增"
        }}</el-button
      >
    </div>
    <el-alert
      v-if="module === 'protocols'"
      title="通用 HTTP 接口用于联调。厂家协议尚未提供，当前未声称接通真实仪器；不会猜测 HQAPI 子路径。"
      type="warning"
      :closable="false"
    /><el-card shadow="never"
      ><div class="toolbar">
        <el-input
          v-model="keyword"
          clearable
          placeholder="输入条码、记录编号或异常原因"
          @keyup.enter="load"
        /><el-button @click="load">查询 / 刷新</el-button>
      </div>
      <el-table v-loading="busy" :data="rows" stripe
        ><el-table-column
          v-for="[key, label] in columns"
          :key="key"
          :label="label"
          :min-width="key === 'reason' || key === 'body' ? 240 : 130"
          show-overflow-tooltip
          ><template #default="{ row }">{{
            show(row[key])
          }}</template></el-table-column
        ><el-table-column
          v-if="['trace', 'quality', 'receipts', 'devices'].includes(module)"
          label="操作"
          width="140"
          fixed="right"
          ><template #default="{ row }"
            ><el-button link type="primary" @click="inspect(row)"
              >详情</el-button
            ><el-button
              v-if="module === 'devices'"
              v-hasPermi="['mes:devices:edit']"
              link
              @click="active(row)"
              >{{ row.active ? "停用" : "启用" }}</el-button
            ></template
          ></el-table-column
        ></el-table
      >
      <p class="note">
        {{ rows.length }} 条记录；追溯和日志列表最多展示最近 500
        条，完整批次记录请在生产报表导出。
      </p></el-card
    >
    <el-dialog
      v-model="dialog"
      :title="names[module]"
      width="760px"
      destroy-on-close
      ><el-form label-width="110px"
        ><template v-if="module === 'barcodes'"
          ><el-form-item label="条码类型"
            ><el-select v-model="form.kind"
              ><el-option
                v-for="[value, label] in [
                  ['main', '主码'],
                  ['cell', '电芯码'],
                  ['sn', '成品 SN'],
                  ['pack', '包装码'],
                ]"
                :key="value"
                :value="value"
                :label="label" /></el-select></el-form-item
          ><el-form-item label="总位数"
            ><el-input-number
              v-model="form.length"
              :min="1"
              :max="128" /></el-form-item
          ><el-form-item label="字符类型"
            ><el-select v-model="form.charset"
              ><el-option label="字母与数字" value="alnum" /><el-option
                label="纯数字"
                value="numeric" /><el-option
                label="可打印 ASCII"
                value="printable" /></el-select></el-form-item
          ><el-form-item label="允许前缀"
            ><el-input
              v-model="form.prefixesText"
              type="textarea"
              placeholder="多个前缀用逗号或换行分隔；留空表示不限前缀" /></el-form-item
          ><el-alert
            title="发布后对后续扫描生效，历史规则和过站引用保留。"
            :closable="false"
        /></template>
        <template v-if="module === 'grants'"
          ><el-form-item label="用户"
            ><el-select v-model="form.userId" filterable
              ><el-option
                v-for="u in options.users"
                :key="u.id"
                :value="u.id"
                :label="u.name" /></el-select></el-form-item
          ><el-form-item label="工位"
            ><el-select v-model="form.stationId" filterable
              ><el-option
                v-for="s in options.stations"
                :key="s.id"
                :value="s.id"
                :label="s.code" /></el-select></el-form-item
          ><el-form-item label="授权"
            ><el-switch
              v-model="form.enabled"
              active-text="允许此工位"
              inactive-text="撤销此工位" /></el-form-item
          ><el-alert
            title="用户同时需要角色中的“工位扫码作业”权限；服务端校验工位授权和部门数据范围。"
            :closable="false"
        /></template>
        <template v-if="module === 'devices'"
          ><el-form-item
            v-for="[key, label] in [
              ['code', '设备编号'],
              ['name', '设备名称'],
              ['programVersion', '程序/标准版本'],
            ]"
            :key="key"
            :label="label"
            ><el-input v-model="form[key]" /></el-form-item
          ><el-form-item label="协议适配器"
            ><el-select v-model="form.protocol"
              ><el-option
                v-for="p in options.protocols"
                :key="p.code"
                :value="p.code"
                :label="p.name" /></el-select></el-form-item
          ><el-form-item label="测试工位"
            ><el-select v-model="form.stationId" filterable
              ><el-option
                v-for="s in options.stations"
                :key="s.id"
                :value="s.id"
                :label="s.code" /></el-select></el-form-item
          ><el-table :data="form.requiredItems"
            ><el-table-column label="测试项目"
              ><template #default="{ row }"
                ><el-input v-model="row.name" /></template></el-table-column
            ><el-table-column label="单位"
              ><template #default="{ row }"
                ><el-input v-model="row.unit" /></template></el-table-column
            ><el-table-column label="下限"
              ><template #default="{ row }"
                ><el-input-number
                  v-model="row.lowerLimit"
                  controls-position="right" /></template></el-table-column
            ><el-table-column label="上限"
              ><template #default="{ row }"
                ><el-input-number
                  v-model="row.upperLimit"
                  controls-position="right" /></template></el-table-column></el-table
          ><el-button
            @click="
              form.requiredItems.push({
                name: '',
                unit: '',
                lowerLimit: 0,
                upperLimit: 1,
              })
            "
            >增加测试项目</el-button
          ><el-alert
            title="新设备使用通用 HTTP v1 适配器，独立设备凭证仅显示一次。"
            :closable="false"
        /></template>
        <template v-if="module === 'messages'"
          ><el-form-item label="标题"
            ><el-input v-model="form.title" /></el-form-item
          ><el-form-item label="内容"
            ><el-input
              v-model="form.body"
              type="textarea"
              :rows="5" /></el-form-item
          ><el-form-item label="目标产线"
            ><el-select
              v-model="form.lineId"
              clearable
              placeholder="留空为全局消息，仅管理员可发布"
              ><el-option
                v-for="l in options.lines"
                :key="l.id"
                :value="l.id"
                :label="l.code + ' ' + l.name" /></el-select></el-form-item
          ><el-form-item label="重要消息"
            ><el-switch
              v-model="form.important" /></el-form-item></template></el-form
      ><template #footer
        ><el-button @click="dialog = false">取消</el-button
        ><el-button type="primary" @click="save"
          >保存{{ module === "barcodes" ? "并发布" : "" }}</el-button
        ></template
      ></el-dialog
    >
    <el-drawer v-model="detailOpen" title="完整记录" size="70%"
      ><template v-if="detail && ['trace', 'quality'].includes(module)"
        ><el-descriptions :column="2" border
          ><el-descriptions-item
            v-for="key in [
              'mainCode',
              'cellCode',
              'snCode',
              'packCode',
              'orderCode',
              'batchCode',
            ]"
            :key="key"
            :label="key"
            >{{ detail[key] || "—" }}</el-descriptions-item
          ></el-descriptions
        >
        <h3>过站与 SOP 版本</h3>
        <el-table :data="detail.passages"
          ><el-table-column prop="process" label="工序" /><el-table-column
            prop="station"
            label="工位" /><el-table-column
            prop="at"
            label="时间" /><el-table-column
            prop="sopVersionId"
            label="SOP 版本编号"
        /></el-table>
        <h3>全部测试与复测</h3>
        <el-card v-for="t in detail.tests" :key="t.id" shadow="never"
          ><b
            >{{ t.verdict }} · {{ t.testedAt }} · {{ t.device }} ·
            {{ t.taskId }}</b
          ><el-table :data="t.items"
            ><el-table-column
              v-for="[key, label] in [
                ['name', '项目'],
                ['value', '数值'],
                ['unit', '单位'],
                ['lowerLimit', '下限'],
                ['upperLimit', '上限'],
                ['verdict', '结果'],
              ]"
              :key="key"
              :prop="key"
              :label="label" /></el-table
          ><el-collapse
            ><el-collapse-item title="原始测试报文">
              <pre>{{ t.raw }}</pre>
            </el-collapse-item></el-collapse
          ></el-card
        ></template
      >
      <pre v-else>{{ JSON.stringify(detail, null, 2) }}</pre>
    </el-drawer>
  </div>
</template>
<style scoped>
.business-head {
  display: flex;
  justify-content: space-between;
  align-items: center;
  margin-bottom: 22px;
}
.business-head h2 {
  margin: 0;
  font-size: 24px;
}
.business-head p,
.note {
  color: #8292a5;
  font-size: 13px;
}
.toolbar {
  display: flex;
  gap: 12px;
  margin-bottom: 20px;
}
.toolbar .el-input {
  max-width: 420px;
}
.el-card {
  border-radius: 12px;
  margin: 14px 0;
}
.el-alert {
  margin: 16px 0;
}
pre {
  white-space: pre-wrap;
  word-break: break-all;
  line-height: 1.7;
}
.el-input-number {
  width: 135px;
}
</style>
