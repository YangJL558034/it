import { defineStore } from "pinia";
import request from "@/utils/request";
import fallbackLogo from "/branding/logo.png";
export default defineStore("mes-branding", () => {
  const name = ref(import.meta.env.VITE_APP_TITLE),
    logo = ref(fallbackLogo),
    revision = ref(0);
  let socket,
    timer,
    retry,
    stopped = true;
  const prefix = () => import.meta.env.VITE_APP_BASE_API || "";
  async function refresh() {
    try {
      const data = await request({ url: "/api/branding" });
      name.value = data.name;
      logo.value = prefix() + data.logoUrl;
      revision.value = data.revision;
      document
        .querySelector('link[rel="icon"]')
        ?.setAttribute("href", logo.value);
    } catch {}
  }
  function connect() {
    if (stopped) return;
    const url = new URL(prefix() + "/ws/notifications", location.origin);
    url.protocol = url.protocol === "https:" ? "wss:" : "ws:";
    socket = new WebSocket(url);
    socket.onmessage = (event) => {
      try {
        if (JSON.parse(event.data).type === "changed") refresh();
      } catch {}
    };
    socket.onclose = () => {
      if (!stopped) retry = setTimeout(connect, 5000);
    };
    socket.onerror = () => socket?.close();
  }
  function start() {
    if (!stopped) return;
    stopped = false;
    refresh();
    connect();
    timer = setInterval(refresh, 30000);
  }
  function stop() {
    stopped = true;
    clearInterval(timer);
    clearTimeout(retry);
    socket?.close();
  }
  return { name, logo, revision, refresh, start, stop };
});
