import {reactive} from 'vue'
import {Preferences} from '@capacitor/preferences'
import {HubConnectionBuilder,LogLevel} from '@microsoft/signalr'
export const state=reactive({server:'',token:'',user:null as null|{id:string;account:string;name:string;role:string},online:false,connected:false,lastUpdate:''})
export async function init(){state.server=(await Preferences.get({key:'mes.server'})).value||''}
export function base(){return state.server||location.origin}
export async function setServer(value:string){
  const u=new URL(value.includes('://')?value:'http://'+value)
  if(!['http:','https:'].includes(u.protocol)||u.username||u.password||u.search||u.hash||u.pathname!=='/')throw Error('请输入服务器基础地址，例如 192.168.0.197:8080')
  const r=await fetch(u.origin+'/api/health',{signal:AbortSignal.timeout(8000)});if(!r.ok||(await r.json()).status!=='ok')throw Error('服务器健康检查未通过')
  await disconnect(); state.server=u.origin;state.token='';state.user=null;state.online=true;await Preferences.set({key:'mes.server',value:u.origin})
}
export async function api<T=any>(path:string,options:RequestInit={}):Promise<T>{
  const headers=new Headers(options.headers);if(state.token)headers.set('Authorization','Bearer '+state.token)
  if(options.body && !(options.body instanceof FormData))headers.set('Content-Type','application/json')
  let r:Response
  try{r=await fetch(base()+path,{...options,headers,signal:options.signal??AbortSignal.timeout(20000)});state.online=true}catch(e){state.online=false;throw Error('网络中断，禁止过站；请恢复连接后重试同一扫码')}
  if(r.status===401){state.token='';state.user=null;throw Error('登录已过期，请重新登录')}
  if(!r.ok){let e;try{e=await r.json()}catch{};throw Error(e?.message||'请求失败：'+r.status)}
  if(r.status===204)return undefined as T
  const text=await r.text();return text?JSON.parse(text):undefined as T
}
export const post=(path:string,body:any={})=>api(path,{method:'POST',body:JSON.stringify(body)})
export async function blob(path:string){const r=await fetch(base()+path,{headers:{Authorization:'Bearer '+state.token}});if(!r.ok)throw Error('文件读取失败 '+r.status);return r.blob()}
let hub:ReturnType<HubConnectionBuilder['build']>|undefined
export async function connect(refresh:()=>void,publicTv=false){await disconnect();hub=new HubConnectionBuilder().withUrl(base()+(publicTv?'/hubs/tv':'/hubs/mes'),publicTv?{}:{accessTokenFactory:()=>state.token}).withAutomaticReconnect([0,2000,5000,10000,30000]).configureLogging(LogLevel.Error).build();hub.on('changed',()=>refresh());hub.onreconnecting(()=>state.connected=false);hub.onreconnected(()=>{state.connected=true;refresh()});hub.onclose(()=>state.connected=false);try{await hub.start();state.connected=true}catch{state.connected=false}}
export async function disconnect(){if(hub)await hub.stop();hub=undefined;state.connected=false}
export async function download(path:string,name:string){const url=URL.createObjectURL(await blob(path));const a=document.createElement('a');a.href=url;a.download=name;a.click();setTimeout(()=>URL.revokeObjectURL(url),1000)}
export function stamp(value:string){return value?new Date(value).toLocaleString('zh-CN',{timeZone:'Asia/Shanghai',hour12:false}):'—'}

export function uuid(){return typeof crypto.randomUUID==='function'?crypto.randomUUID():'10000000-1000-4000-8000-100000000000'.replace(/[018]/g,c=>(Number(c)^crypto.getRandomValues(new Uint8Array(1))[0]!&15>>Number(c)/4).toString(16))}
