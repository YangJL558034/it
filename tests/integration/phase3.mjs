import {assert,req,ok,login,test,save,fixture,suffix,base,token} from './helpers.mjs'
import signalr from '../../web/node_modules/@microsoft/signalr/dist/cjs/index.js'
let hub
try{
await login();const f=await fixture(),main='MC'+suffix.padStart(12,'0'),cell='CELL'+suffix.padStart(12,'0'),sn='SN'+suffix.padStart(12,'0'),pack='PK'+suffix.padStart(12,'0');
const scan=(n,code)=>req('/api/station/scan',{requestId:crypto.randomUUID(),stationId:f.stations[n-1].id,batchId:f.batch.id,code});
const spec=[{name:'Voltage',unit:'V',lower:4.8,upper:5.2}];const d=await ok('/api/admin/devices',{code:'ATE'+suffix,name:'HTTP 模拟仪器',stationId:f.stations[2].id,protocol:'generic-v1',programVersion:'STD-1',items:spec});
const device=async(path,body,key=d.key)=>{const r=await fetch(base+'/api/instrument/v1/'+path,{method:'POST',headers:{'Content-Type':'application/json','X-Device-Key':key},body:JSON.stringify(body)});return {status:r.status,data:await r.json()}};
let task,upload,events=0;
const check=(changes={})=>device('check',{externalTaskId:crypto.randomUUID(),mainCode:main,deviceCode:'ATE'+suffix,stationCode:f.stations[2].code,workOrderId:f.order.id,batchId:f.batch.id,programVersion:'STD-1',...changes});
const result=(verdict)=>({recordId:crypto.randomUUID(),taskId:task,mainCode:main,deviceCode:'ATE'+suffix,stationCode:f.stations[2].code,testedAt:new Date().toISOString(),programVersion:'STD-1',verdict,items:[{...spec[0],value:verdict==='PASS'?5:4,verdict}],rawData:'SIMULATOR RAW'});
await test('设备密钥认证，错误密钥拒绝',async()=>assert.equal((await device('heartbeat',{},'x'.repeat(64))).status,401));
await test('无主码/无电芯工艺检查拒绝并保留异常',async()=>{assert.equal((await check()).data.accepted,false);await scan(1,main);assert.equal((await check()).data.accepted,false);await scan(2,main);await scan(2,cell)});
await test('跨工单工艺检查拒绝',async()=>assert.equal((await check({workOrderId:crypto.randomUUID()})).data.accepted,false));
await test('待测任务阻止后续流转',async()=>{const r=await check();assert.equal(r.data.accepted,true);task=r.data.data.taskId;assert.equal((await scan(4,main)).status,409)});
await test('参数不完整保留异常，不完成任务',async()=>{const r=await device('results',{...result('PASS'),items:[]});assert.equal(r.data.accepted,false)});
await test('SignalR 数据提交后通知',async()=>{hub=new signalr.HubConnectionBuilder().withUrl(base+'/hubs/mes',{accessTokenFactory:()=>token}).build();hub.on('changed',()=>events++);await hub.start();upload=result('FAIL');assert.equal((await device('results',upload)).data.accepted,true);await new Promise(r=>setTimeout(r,100));assert.ok(events>0)});
await test('FAIL 拦截 SN 放行',async()=>assert.equal((await scan(4,main)).status,409));
await test('12 并发重复上传只保存一份结果',async()=>{const rs=await Promise.all(Array.from({length:12},()=>device('results',upload)));rs.forEach(r=>{assert.equal(r.data.accepted,true);assert.equal(r.data.data.duplicate,true)});assert.equal((await ok('/api/trace?code='+main)).tests.length,1)});
await test('同一记录号篡改内容拒绝',async()=>assert.equal((await device('results',{...upload,rawData:'altered'})).data.accepted,false));
await test('未知任务原报文保留，不推进',async()=>assert.equal((await device('results',{...result('PASS'),taskId:crypto.randomUUID()})).data.accepted,false));
await test('复测追加 PASS，历史 FAIL 和参数仍保留',async()=>{task=(await check()).data.data.taskId;assert.equal((await device('results',result('PASS'))).data.accepted,true);const trace=await ok('/api/trace?code='+main);assert.equal(trace.tests.length,2);assert.deepEqual(trace.tests.map(t=>t.result.verdict),['FAIL','PASS']);assert.equal(trace.tests[0].items.length,1)});
await test('PASS 后再次复测 FAIL 必须拦截',async()=>{task=(await check()).data.data.taskId;assert.equal((await device('results',result('FAIL'))).data.accepted,true);assert.equal((await scan(4,main)).status,409)});
await test('最终 PASS 完成 SN 包装闭环且测试数量不重复',async()=>{task=(await check()).data.data.taskId;assert.equal((await device('results',result('PASS'))).data.accepted,true);assert.equal((await scan(4,main)).status,200);assert.equal((await scan(4,sn)).status,200);assert.equal((await scan(5,sn)).data.trace.tests.length,4);assert.equal((await scan(5,pack)).status,200);assert.equal((await ok('/api/trace?code='+pack)).passages.length,5);assert.equal((await ok(`/api/station/summary?stationId=${f.stations[2].id}&batchId=${f.batch.id}`)).done,1)});
await test('流转后补测拒绝，异常接收可追查',async()=>{assert.equal((await check()).data.accepted,false);const logs=await ok('/api/admin/receipts');assert.ok(logs.some(r=>r.status==='Rejected'&&r.raw.includes(main)))});
}finally{if(hub)await hub.stop();await save('phase3')}
