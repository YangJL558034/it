<script setup>
import { onMounted, onUnmounted, ref, computed } from "vue";
import request from "@/utils/request";
import { ElMessage } from "element-plus";
const versions = ref([]),
  processes = ref([]),
  selected = ref(),
  files = ref([]),
  createOpen = ref(false),
  form = ref({ processId: "", title: "" }),
  busy = ref(false),
  playing = ref(true),
  index = ref(0),
  previewUrl = ref(""),
  input = ref(),
  video = ref();
const current = computed(() => files.value[index.value]);
let timer,
  epoch = 0;
async function load() {
  versions.value = (await request({ url: "/mes/sop" })).data;
  processes.value = (await request({ url: "/mes/master/processes/list" })).data;
}
async function select(row) {
  clearTimeout(timer);
  selected.value = row;
  files.value = (await request({ url: "/mes/sop/" + row.id })).data.files;
  index.value = 0;
  await show();
}
async function show() {
  clearTimeout(timer);
  let task = ++epoch;
  const f = current.value;
  if (previewUrl.value) URL.revokeObjectURL(previewUrl.value);
  previewUrl.value = "";
  if (!f) return;
  const blob = await request({
    url: "/api/sop/files/" + f.id,
    responseType: "blob",
  });
  if (task !== epoch) return;
  previewUrl.value = URL.createObjectURL(
    new Blob([blob], { type: f.contentType }),
  );
  schedule();
}
function schedule() {
  clearTimeout(timer);
  if (
    playing.value &&
    current.value &&
    !current.value.contentType.startsWith("video/")
  )
    timer = setTimeout(() => step(1), current.value.seconds * 1000);
}
function step(n) {
  if (!files.value.length) return;
  index.value = (index.value + n + files.value.length) % files.value.length;
  void show();
}
function toggle() {
  playing.value = !playing.value;
  if (playing.value) {
    video.value?.play();
    schedule();
  } else {
    video.value?.pause();
    clearTimeout(timer);
  }
}
async function create() {
  const r = await request({
    url: "/mes/sop",
    method: "post",
    data: form.value,
  });
  createOpen.value = false;
  await load();
  await select(versions.value.find((v) => v.id === r.data.id));
}
async function upload(event) {
  const data = new FormData();
  for (const file of event.target.files) data.append("files", file);
  if (!data.has("files")) return;
  busy.value = true;
  try {
    await request({
      url: "/mes/sop/" + selected.value.id + "/files",
      method: "post",
      data,
      headers: { "Content-Type": "multipart/form-data" },
      timeout: 180000,
    });
    await select(selected.value);
    ElMessage.success("文件已上传");
  } finally {
    busy.value = false;
    event.target.value = "";
  }
}
function move(i, n) {
  const j = i + n;
  if (j < 0 || j >= files.value.length) return;
  [files.value[i], files.value[j]] = [files.value[j], files.value[i]];
}
async function saveOrder() {
  await request({
    url: "/mes/sop/" + selected.value.id + "/files",
    method: "put",
    data: files.value.map((f) => ({ id: f.id, seconds: f.seconds })),
  });
  ElMessage.success("播放顺序与停留时间已保存");
  await select(selected.value);
}
async function remove(f) {
  await request({
    url: "/mes/sop/" + selected.value.id + "/files/" + f.id,
    method: "delete",
  });
  await select(selected.value);
}
async function action(kind) {
  await request({
    url: "/mes/sop/" + selected.value.id + "/" + kind,
    method: "post",
  });
  ElMessage.success(kind === "review" ? "审核完成" : "已发布并归档旧版");
  await load();
  await select(versions.value.find((v) => v.id === selected.value.id));
}
onMounted(load);
onUnmounted(() => {
  clearTimeout(timer);
  epoch++;
  if (previewUrl.value) URL.revokeObjectURL(previewUrl.value);
});
</script>
<template>
  <div class="app-container sop-page">
    <div class="head">
      <div>
        <h2>SOP 作业指导书</h2>
        <p>按工序管理版本、多文件播放、审核发布与历史归档。</p>
      </div>
      <el-button
        v-hasPermi="['mes:sop:edit']"
        type="primary"
        @click="createOpen = true"
        >新建版本</el-button
      >
    </div>
    <div class="layout">
      <el-card shadow="never"
        ><el-table :data="versions" highlight-current-row @row-click="select"
          ><el-table-column
            prop="process"
            label="工序"
            min-width="110" /><el-table-column
            prop="title"
            label="标题"
            min-width="130" /><el-table-column
            prop="version"
            label="版本"
            width="70" /><el-table-column
            prop="status"
            label="状态"
            width="100" /></el-table></el-card
      ><el-card v-if="selected" v-loading="busy" shadow="never"
        ><div class="head">
          <b>{{ selected.title }} · V{{ selected.version }}</b
          ><el-tag>{{ selected.status }}</el-tag>
        </div>
        <div class="preview">
          <img
            v-if="current?.contentType.startsWith('image/') && previewUrl"
            :src="previewUrl"
          /><iframe
            v-else-if="current?.contentType === 'application/pdf' && previewUrl"
            :src="previewUrl"
            title="SOP PDF 预览"
          /><video
            v-else-if="current?.contentType.startsWith('video/') && previewUrl"
            ref="video"
            :src="previewUrl"
            :autoplay="playing"
            controls
            @ended="step(1)"
          /><el-empty v-else description="请选择或上传 SOP 文件" />
        </div>
        <div class="controls">
          <el-button @click="step(-1)">上一份</el-button
          ><el-button @click="toggle">{{ playing ? "暂停" : "播放" }}</el-button
          ><el-button @click="step(1)">下一份</el-button
          ><span
            >{{ files.length ? index + 1 : 0 }} / {{ files.length }} ·
            {{ current?.name }}</span
          >
        </div>
        <el-table :data="files"
          ><el-table-column
            prop="name"
            label="文件"
            min-width="140"
          /><el-table-column label="停留时间（秒）" width="160"
            ><template #default="{ row }"
              ><el-input-number
                v-model="row.seconds"
                :min="1"
                :max="3600"
                :disabled="selected.status !== 'Draft'"
                controls-position="right" /></template></el-table-column
          ><el-table-column label="操作" width="160"
            ><template #default="{ row, $index }"
              ><el-button
                link
                @click="
                  index = $index;
                  show();
                "
                >预览</el-button
              ><template v-if="selected.status === 'Draft'"
                ><el-button link @click="move($index, -1)">↑</el-button
                ><el-button link @click="move($index, 1)">↓</el-button
                ><el-button
                  v-hasPermi="['mes:sop:edit']"
                  link
                  type="danger"
                  @click="remove(row)"
                  >删除</el-button
                ></template
              ></template
            ></el-table-column
          ></el-table
        ><input
          ref="input"
          type="file"
          multiple
          accept="image/png,image/jpeg,application/pdf,video/mp4"
          hidden
          @change="upload"
        />
        <div class="controls">
          <template v-if="selected.status === 'Draft'"
            ><el-button v-hasPermi="['mes:sop:edit']" @click="input.click()"
              >批量上传文件</el-button
            ><el-button v-hasPermi="['mes:sop:edit']" @click="saveOrder"
              >保存顺序与时长</el-button
            ><el-button
              v-hasPermi="['mes:sop:review']"
              type="primary"
              @click="action('review')"
              >审核版本</el-button
            ></template
          ><el-button
            v-if="selected.status === 'Reviewed'"
            v-hasPermi="['mes:sop:publish']"
            type="success"
            @click="action('publish')"
            >发布到工位</el-button
          >
        </div>
        <p class="hint">
          图片、PDF
          按设定秒数切换，视频结束后切换。须由另一名有权限的用户审核；已审核和历史版本不可修改。
        </p></el-card
      >
    </div>
    <el-dialog v-model="createOpen" title="新建 SOP 版本" width="500px"
      ><el-form label-width="80px"
        ><el-form-item label="工序"
          ><el-select v-model="form.processId"
            ><el-option
              v-for="p in processes"
              :key="p.id"
              :value="p.id"
              :label="p.name" /></el-select></el-form-item
        ><el-form-item label="标题"
          ><el-input v-model="form.title" /></el-form-item></el-form
      ><template #footer
        ><el-button type="primary" @click="create"
          >创建草稿</el-button
        ></template
      ></el-dialog
    >
  </div>
</template>
<style scoped>
.head {
  display: flex;
  justify-content: space-between;
  align-items: center;
  gap: 16px;
}
.head p,
.hint {
  color: #8292a5;
  font-size: 13px;
}
.head h2 {
  margin: 0;
}
.layout {
  display: grid;
  grid-template-columns: minmax(350px, 0.8fr) minmax(520px, 1.2fr);
  gap: 20px;
  margin-top: 22px;
}
.el-card {
  border-radius: 12px;
}
.preview {
  height: 380px;
  background: #f4f7fb;
  display: flex;
  align-items: center;
  justify-content: center;
  margin: 20px 0;
  overflow: hidden;
}
.preview img,
.preview video {
  width: 100%;
  height: 100%;
  object-fit: contain;
}
.preview iframe {
  width: 100%;
  height: 100%;
  border: 0;
}
.controls {
  display: flex;
  align-items: center;
  gap: 10px;
  margin: 18px 0;
  flex-wrap: wrap;
}
.controls span {
  font-size: 12px;
  color: #8292a5;
}
.el-input-number {
  width: 130px;
}
@media (max-width: 1200px) {
  .layout {
    grid-template-columns: 1fr;
  }
}
</style>
