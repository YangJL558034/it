<script setup>
import { onUnmounted, ref } from "vue";
import { ElMessage } from "element-plus";
import request from "@/utils/request";
import useBrandingStore from "@/store/modules/branding";
const branding = useBrandingStore(),
  name = ref(branding.name),
  file = ref(),
  preview = ref(""),
  busy = ref(false);
function choose(item) {
  if (
    item.raw.size > 2 * 1024 * 1024 ||
    !["image/png", "image/jpeg"].includes(item.raw.type)
  ) {
    ElMessage.error("请选择不超过 2 MB 的 PNG 或 JPEG 图片");
    return;
  }
  if (preview.value) URL.revokeObjectURL(preview.value);
  file.value = item.raw;
  preview.value = URL.createObjectURL(item.raw);
}
async function save(reset = false) {
  busy.value = true;
  try {
    const data = new FormData();
    data.append("name", name.value);
    data.append("reset", String(reset));
    if (file.value && !reset) data.append("file", file.value);
    await request({
      url: "/mes/branding",
      method: "post",
      data,
      headers: { "Content-Type": undefined, repeatSubmit: false },
    });
    await branding.refresh();
    file.value = undefined;
    if (preview.value) URL.revokeObjectURL(preview.value);
    preview.value = "";
    ElMessage.success("已保存，各端将自动更新平台标识");
  } finally {
    busy.value = false;
  }
}
onUnmounted(() => {
  if (preview.value) URL.revokeObjectURL(preview.value);
});
</script>
<template>
  <div class="app-container">
    <el-card shadow="never" class="branding-card"
      ><h1>平台外观</h1>
      <p>管理后台、工位终端和生产大屏使用统一标识，修改后自动同步。</p>
      <div class="branding-preview">
        <img :src="preview || branding.logo" alt="平台 Logo" /><strong>{{
          name
        }}</strong>
      </div>
      <el-form label-width="100px"
        ><el-form-item label="平台名称"
          ><el-input v-model="name" maxlength="128" /></el-form-item
        ><el-form-item label="平台 Logo"
          ><el-upload
            :auto-upload="false"
            :show-file-list="false"
            accept="image/png,image/jpeg"
            :on-change="choose"
            ><el-button v-hasPermi="['mes:branding:edit']" icon="Upload"
              >选择图片</el-button
            ></el-upload
          ><span class="hint"
            >PNG / JPEG，最大 2 MB；透明 PNG 效果更好。</span
          ></el-form-item
        ><el-form-item
          ><el-button
            v-hasPermi="['mes:branding:edit']"
            type="primary"
            :loading="busy"
            @click="save(false)"
            >保存并同步</el-button
          ><el-button
            v-hasPermi="['mes:branding:edit']"
            :disabled="busy"
            @click="save(true)"
            >恢复默认 Logo</el-button
          ></el-form-item
        ></el-form
      ><el-alert
        title="终端需安装支持若依接口的新版本，并连接同一 MES 服务器。系统桌面上的 APK 图标随安装包更新，页面内 Logo 可由后台实时同步。"
        type="info"
        :closable="false"
    /></el-card>
  </div>
</template>
<style scoped>
.branding-card {
  max-width: 900px;
  margin: 20px auto;
  border-radius: 12px;
}
.branding-card h1 {
  font-size: 24px;
}
.branding-card p,
.hint {
  font-size: 13px;
  color: var(--el-text-color-secondary);
  line-height: 1.8;
}
.branding-preview {
  padding: 28px;
  margin: 25px 0;
  background: var(--el-fill-color-light);
  border-radius: 10px;
  display: flex;
  align-items: center;
  gap: 24px;
}
.branding-preview img {
  width: 90px;
  height: 90px;
  object-fit: contain;
}
.branding-preview strong {
  font-size: 22px;
}
.hint {
  margin-left: 16px;
}
.el-input {
  max-width: 500px;
}
</style>
