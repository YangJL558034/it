import request from "@/utils/request";
export const listJobs = () => request({ url: "/mes/maintenance/jobs" });
export const createBackup = () =>
  request({ url: "/mes/maintenance/backups", method: "post" });
export const settings = () => request({ url: "/mes/maintenance/settings" });
export const saveSettings = (data) =>
  request({ url: "/mes/maintenance/settings", method: "put", data });
export const previewRestore = (id) =>
  request({
    url: "/mes/maintenance/backups/" + id + "/preview",
    method: "post",
    timeout: 120000,
    headers: { repeatSubmit: false },
  });
export const restore = (data) =>
  request({ url: "/mes/maintenance/restore", method: "post", data });
export const removeBackup = (id) =>
  request({ url: "/mes/maintenance/backups/" + id, method: "delete" });
export const poll = (id, token) =>
  request({
    url: "/api/maintenance/status/" + id,
    headers: { isToken: false, "X-MES-Maintenance-Token": token },
  });
export async function downloadBackup(id) {
  const file = await request({
    url: "/mes/maintenance/backups/" + id + "/download",
    responseType: "blob",
    timeout: 0,
  });
  if (file.type.includes("json"))
    throw Error(JSON.parse(await file.text()).msg || "下载被拒绝");
  const url = URL.createObjectURL(file),
    link = document.createElement("a");
  link.href = url;
  link.download = "mes-backup-" + id + ".zip";
  link.click();
  setTimeout(() => URL.revokeObjectURL(url), 1000);
}
export const testOrders = (keyword) =>
  request({ url: "/mes/maintenance/test-orders", params: { keyword } });
export const markTest = (data) =>
  request({ url: "/mes/maintenance/test-orders/mark", method: "post", data });
export const previewCleanup = (orderIds) =>
  request({
    url: "/mes/maintenance/test-orders/preview",
    method: "post",
    data: { orderIds },
    timeout: 120000,
  });
export const cleanup = (data) =>
  request({
    url: "/mes/maintenance/test-orders/cleanup",
    method: "post",
    data,
  });
