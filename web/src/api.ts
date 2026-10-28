import { reactive } from "vue";
import { Capacitor, registerPlugin } from "@capacitor/core";
import { Preferences } from "@capacitor/preferences";
export const CLIENT_VERSION = "1.1.0";
export const state = reactive({
  server: "",
  token: "",
  user: null as null | {
    id: string;
    account: string;
    name: string;
    role: string;
  },
  online: false,
  connected: false,
  lastUpdate: "",
  compatible: true,
  serverProblem: "",
});
const vault = registerPlugin<{
  get(): Promise<{ value?: string }>;
  set(input: { value: string }): Promise<void>;
  clear(): Promise<void>;
}>("StationVault");
export const nativeClient = Capacitor.isNativePlatform();
let stationMode = false,
  resuming: Promise<boolean> | undefined;
// Capacitor's patched fetch may ignore AbortSignal; enforce a UI deadline as well.
async function timedFetch(
  input: string,
  options: RequestInit = {},
  milliseconds = 20000,
): Promise<Response> {
  let timer: ReturnType<typeof setTimeout> | undefined;
  try {
    return await Promise.race([
      fetch(input, options),
      new Promise<Response>((_, reject) => {
        timer = setTimeout(
          () => reject(new Error("MES 网络请求超时")),
          milliseconds,
        );
      }),
    ]);
  } finally {
    if (timer) clearTimeout(timer);
  }
}
export async function init(rememberStation = false) {
  state.server = (await Preferences.get({ key: "mes.server" })).value || "";
  stationMode = rememberStation;
  try {
    const response = await timedFetch(
      base() + "/api/health",
      { signal: AbortSignal.timeout(8000) },
      8000,
    );
    const health = await response.json();
    state.online = health.status === "ok";
    state.compatible = health.backend === "ruoyi-java";
    if (!state.compatible)
      state.serverProblem =
        "此安装包需要若依 MES 服务，请修改服务器地址；原 .NET 服务不兼容。";
  } catch {
    state.online = false;
    state.compatible = false;
    state.serverProblem = "服务器未连接，请检查地址、端口和网络后重新验证。";
  }
  if (stationMode && state.compatible) await resumeStation();
}
async function credential() {
  if (!nativeClient) return null;
  try {
    const raw = (await vault.get()).value;
    if (raw) {
      const saved = JSON.parse(raw);
      if (saved.server === base()) return saved.token as string;
    }
  } catch {}
  return null;
}
export async function acceptLogin(result: any, remember: boolean) {
  if (remember) {
    if (nativeClient && result.stationToken)
      await vault.set({
        value: JSON.stringify({ server: base(), token: result.stationToken }),
      });
    await Preferences.remove({ key: "mes.station.signedOut" });
    await Preferences.set({ key: "mes.station.remembered", value: "yes" });
    stationMode = true;
  }
  state.token = result.token;
  state.user = result.user;
}
export async function resumeStation(): Promise<boolean> {
  if (
    !stationMode ||
    (await Preferences.get({ key: "mes.station.remembered" })).value !==
      "yes" ||
    (await Preferences.get({ key: "mes.station.signedOut" })).value === "yes"
  )
    return false;
  if (resuming) return resuming;
  resuming = (async () => {
    try {
      const token = await credential();
      if (nativeClient && !token) return false;
      const r = await timedFetch(
        base() + "/api/auth/station/resume",
        {
          method: "POST",
          credentials: "include",
          headers: {
            "Content-Type": "application/json",
            "X-MES-Station-Auth": "1",
          },
          body: JSON.stringify({ stationToken: token }),
          signal: AbortSignal.timeout(8000),
        },
        8000,
      );
      state.online = true;
      if (r.status === 401) {
        if (nativeClient) await vault.clear();
        await Preferences.remove({ key: "mes.station.remembered" });
        state.token = "";
        state.user = null;
        return false;
      }
      if (!r.ok) return false;
      const result = await r.json();
      if (result.code && result.code !== 200) {
        if (result.code === 401) {
          if (nativeClient) await vault.clear();
          await Preferences.remove({ key: "mes.station.remembered" });
          state.token = "";
          state.user = null;
        }
        return false;
      }
      state.token = result.token;
      state.user = result.user;
      return true;
    } catch {
      state.online = false;
      return false;
    } finally {
      resuming = undefined;
    }
  })();
  return resuming;
}
export async function forgetStation() {
  await Preferences.set({ key: "mes.station.signedOut", value: "yes" });
  const savedCredential = await credential();
  if (nativeClient && !savedCredential) {
    await vault.clear();
    return;
  }
  try {
    await timedFetch(
      base() + "/api/auth/station/forget",
      {
        method: "POST",
        credentials: "include",
        headers: {
          "Content-Type": "application/json",
          "X-MES-Station-Auth": "1",
          ...(state.token ? { Authorization: "Bearer " + state.token } : {}),
        },
        body: JSON.stringify({ stationToken: savedCredential }),
        signal: AbortSignal.timeout(5000),
      },
      5000,
    );
  } catch {}
  if (nativeClient) await vault.clear();
}
export function base() {
  return state.server || location.origin;
}
export async function setServer(value: string) {
  const u = new URL(value.includes("://") ? value : "http://" + value);
  if (
    !["http:", "https:"].includes(u.protocol) ||
    u.username ||
    u.password ||
    u.search ||
    u.hash ||
    u.pathname !== "/"
  )
    throw Error("请输入服务器基础地址，例如 192.168.0.197:8080");
  let health: any;
  try {
    const r = await timedFetch(
      u.origin + "/api/health",
      { signal: AbortSignal.timeout(8000) },
      8000,
    );
    if (!r.ok) throw Error("HTTP " + r.status);
    health = await r.json();
  } catch {
    throw Error("无法连接 MES 服务。请检查服务器局域网地址、端口和防火墙。");
  }
  if (health.backend !== "ruoyi-java")
    throw Error(
      "此安装包需要若依 MES 服务，当前地址返回了其他系统或管理页面。",
    );
  if (health.status !== "ok")
    throw Error(
      health.status === "maintenance"
        ? "服务器正在维护，请稍后重试"
        : "数据库或文件服务健康检查未通过",
    );
  await forgetStation();
  await disconnect();
  state.server = u.origin;
  state.token = "";
  state.user = null;
  state.online = true;
  state.compatible = true;
  state.serverProblem = "";
  await Preferences.set({ key: "mes.server", value: u.origin });
}
export async function api<T = any>(
  path: string,
  options: RequestInit = {},
  retried = false,
): Promise<T> {
  const headers = new Headers(options.headers);
  headers.set("X-MES-Station-Auth", "1");
  headers.set("X-MES-Client-Version", CLIENT_VERSION);
  if (state.token) headers.set("Authorization", "Bearer " + state.token);
  if (options.body && !(options.body instanceof FormData))
    headers.set("Content-Type", "application/json");
  let r: Response;
  try {
    r = await timedFetch(base() + path, {
      ...options,
      credentials: "include",
      headers,
      signal: options.signal ?? AbortSignal.timeout(20000),
    });
    state.online = true;
  } catch (e) {
    state.online = false;
    throw Error("网络中断，禁止过站；请恢复连接后重试同一扫码");
  }
  if (r.status === 204) return undefined as T;
  const text = await r.text();
  let data: any;
  try {
    data = text ? JSON.parse(text) : undefined;
  } catch {
    throw Error("服务器未返回 MES 接口数据，请检查地址和版本");
  }
  const code = typeof data?.code === "number" ? data.code : r.status;
  if (code === 401 && !retried && stationMode && (await resumeStation()))
    return api<T>(path, options, true);
  if (code === 401) {
    state.token = "";
    state.user = null;
    throw Error("登录已失效，请重新登录");
  }
  if (!r.ok || code >= 400) {
    if (code === 503) state.online = false;
    const error = Object.assign(
      Error(data?.message || data?.msg || "请求失败：" + code),
      { code },
    );
    throw error;
  }
  return data as T;
}
export const post = (path: string, body: any = {}) =>
  api(path, { method: "POST", body: JSON.stringify(body) });
export async function blob(path: string) {
  const r = await timedFetch(base() + path, {
    headers: { Authorization: "Bearer " + state.token },
  });
  if (!r.ok) throw Error("文件读取失败 " + r.status);
  return r.blob();
}
let socket: WebSocket | undefined,
  retry: ReturnType<typeof setTimeout> | undefined,
  ping: ReturnType<typeof setInterval> | undefined,
  generation = 0;
export async function connect(refresh: () => void, _publicTv = false) {
  if (
    socket &&
    (socket.readyState === WebSocket.OPEN ||
      socket.readyState === WebSocket.CONNECTING)
  )
    return;
  const epoch = ++generation;
  let attempt = 0;
  const open = () => {
    if (epoch !== generation) return;
    const url = new URL(base() + "/ws/notifications");
    url.protocol = url.protocol === "https:" ? "wss:" : "ws:";
    socket = new WebSocket(url);
    let pong = Date.now();
    socket.onopen = () => {
      if (epoch !== generation) return;
      state.connected = true;
      attempt = 0;
      Promise.resolve(refresh()).catch(() => {});
      clearInterval(ping);
      ping = setInterval(() => {
        if (Date.now() - pong > 65000) {
          socket?.close();
          return;
        }
        if (socket?.readyState === WebSocket.OPEN) socket.send("ping");
      }, 25000);
    };
    socket.onmessage = (event) => {
      pong = Date.now();
      try {
        if (JSON.parse(event.data).type === "changed")
          Promise.resolve(refresh()).catch(() => {});
      } catch {}
    };
    socket.onclose = () => {
      if (epoch !== generation) return;
      state.connected = false;
      clearInterval(ping);
      retry = setTimeout(
        open,
        [1000, 2000, 5000, 10000, 30000][Math.min(attempt++, 4)],
      );
    };
    socket.onerror = () => socket?.close();
  };
  open();
}
export async function disconnect() {
  generation++;
  clearTimeout(retry);
  clearInterval(ping);
  socket?.close();
  socket = undefined;
  state.connected = false;
}
export async function download(path: string, name: string) {
  const url = URL.createObjectURL(await blob(path));
  const a = document.createElement("a");
  a.href = url;
  a.download = name;
  a.click();
  setTimeout(() => URL.revokeObjectURL(url), 1000);
}
export function stamp(value: string) {
  return value
    ? new Date(value).toLocaleString("zh-CN", {
        timeZone: "Asia/Shanghai",
        hour12: false,
      })
    : "—";
}

export function uuid() {
  return typeof crypto.randomUUID === "function"
    ? crypto.randomUUID()
    : "10000000-1000-4000-8000-100000000000".replace(/[018]/g, (c) =>
        (
          Number(c) ^
          (crypto.getRandomValues(new Uint8Array(1))[0]! &
            (15 >> (Number(c) / 4)))
        ).toString(16),
      );
}
