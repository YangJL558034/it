import { reactive } from "vue";
export const APP_NAME = "聚星数据平台-制造MES";
export const branding = reactive({
  name: APP_NAME,
  logo: "/branding/logo.png",
  revision: 0,
});
let socket: WebSocket | undefined,
  timer: ReturnType<typeof setInterval> | undefined,
  retry: ReturnType<typeof setTimeout> | undefined,
  stopped = true,
  currentBase = "";
export async function refreshBranding() {
  try {
    const response = await fetch(currentBase + "/api/branding", {
      signal: AbortSignal.timeout(8000),
    });
    if (!response.ok) return;
    const value = await response.json();
    if (typeof value.name !== "string" || typeof value.logoUrl !== "string")
      return;
    branding.name = value.name;
    branding.logo = currentBase + value.logoUrl;
    branding.revision = value.revision;
    document.title = branding.name;
    document
      .querySelector<HTMLLinkElement>('link[rel="icon"]')
      ?.setAttribute("href", branding.logo);
  } catch {}
}
function connect() {
  if (stopped || !currentBase) return;
  const url = new URL(currentBase + "/ws/notifications");
  url.protocol = url.protocol === "https:" ? "wss:" : "ws:";
  socket = new WebSocket(url);
  socket.onmessage = (event) => {
    try {
      if (JSON.parse(event.data).type === "changed") void refreshBranding();
    } catch {}
  };
  socket.onclose = () => {
    if (!stopped) retry = setTimeout(connect, 5000);
  };
  socket.onerror = () => socket?.close();
}
export function startBranding(base: string) {
  stopBranding();
  currentBase = base;
  stopped = false;
  void refreshBranding();
  connect();
  timer = setInterval(refreshBranding, 30000);
}
export function stopBranding() {
  stopped = true;
  clearInterval(timer);
  clearTimeout(retry);
  socket?.close();
  socket = undefined;
}
