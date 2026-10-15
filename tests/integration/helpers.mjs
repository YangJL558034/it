import fs from 'node:fs/promises'
import assert from 'node:assert/strict'
export {assert}
export const base=process.env.MES_URL||'http://127.0.0.1:5080'
export const secret=JSON.parse((await fs.readFile(new URL('../../.local/development.json',import.meta.url),'utf8')).replace(/^\uFEFF/,''))
export let token=''
export const results=[]
export async function req(path,body,method=body===undefined?'GET':'POST',auth=token){const r=await fetch(base+path,{method,headers:{'Content-Type':'application/json',...(auth?{Authorization:'Bearer '+auth}:{})},body:body===undefined?undefined:JSON.stringify(body)});const text=await r.text();let data;try{data=JSON.parse(text)}catch{data=text}return {status:r.status,data}}
export async function ok(path,body,method){const r=await req(path,body,method);assert.ok(r.status<300,JSON.stringify(r));return r.data}
export async function login(){const r=await ok('/api/auth/login',{account:'admin',password:secret.adminPassword});assert.equal(r.authenticated,true);token=r.token}
export async function test(name,fn){try{await fn();results.push({name,status:'passed'});console.log('PASS',name)}catch(e){results.push({name,status:'failed',error:String(e)});throw e}}
export async function save(phase){await fs.mkdir(new URL('../../.local/test-results/',import.meta.url),{recursive:true});await fs.writeFile(new URL(`../../.local/test-results/${phase}.json`,import.meta.url),JSON.stringify({at:new Date().toISOString(),base,results},null,2))}
export const suffix=Date.now().toString().slice(-9)
export async function fixture(){
 const line=await ok('/api/admin/lines',{code:'L'+suffix,name:'闭环验收产线',workshop:'验收车间'});
 const product=await ok('/api/admin/products',{code:'P'+suffix,name:'验收产品'});
 const order=await ok('/api/admin/orders',{code:'MO'+suffix,lineId:line.id,productId:product.id,plan:100,status:'Open'});
 const batch=await ok('/api/admin/batches',{code:'B'+suffix,workOrderId:order.id,plan:50});
 const catalog=await ok('/api/catalog'),stations=[];
 for(const p of catalog.processes)stations.push(await ok('/api/admin/stations',{code:'S'+suffix+'-'+p.sequence,name:p.name,lineId:line.id,processStepId:p.id}));
 return {line,product,order,batch,stations,processes:catalog.processes};
}
