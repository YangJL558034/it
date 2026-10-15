import {assert,req,ok,login,test,save,fixture,suffix} from './helpers.mjs'
try{
await login();const f=await fixture(),main='MC'+suffix.padStart(12,'0'),cell='CELL'+suffix.padStart(12,'0');
const scan=(n,code,id=crypto.randomUUID(),batchId=f.batch.id)=>req('/api/station/scan',{requestId:id,stationId:f.stations[n-1].id,batchId,code});
await test('条码不合规不创建生产记录',async()=>assert.equal((await scan(1,'BAD')).status,422));
await test('未主码过站不能绑定电芯',async()=>assert.equal((await scan(2,main)).status,404));
await test('并发相同请求幂等，仅一个主码一次计数',async()=>{const id=crypto.randomUUID();const rs=await Promise.all(Array.from({length:12},()=>scan(1,main,id)));rs.forEach(r=>assert.equal(r.status,200));const s=await ok(`/api/station/summary?stationId=${f.stations[0].id}&batchId=${f.batch.id}`);assert.equal(s.done,1);});
await test('绑定前跳过电芯进入 SN 被拦截',async()=>assert.equal((await scan(4,main)).status,409));
await test('跨批次主码不能重复过站',async()=>{const b=await ok('/api/admin/batches',{code:'B2'+suffix,workOrderId:f.order.id,plan:50});assert.equal((await scan(1,main,crypto.randomUUID(),b.id)).status,409)});
await test('同一输入协议自动进入等待电芯码并原子绑定',async()=>{assert.equal((await scan(2,main)).data.waiting,'电芯码');const id=crypto.randomUUID();const rs=await Promise.all(Array.from({length:10},()=>scan(2,cell,id)));rs.forEach(r=>assert.equal(r.status,200));assert.equal((await ok(`/api/station/summary?stationId=${f.stations[1].id}&batchId=${f.batch.id}`)).done,1)});
await test('未通过测试禁止 SN 和包装',async()=>{assert.equal((await scan(4,main)).status,409);assert.equal((await scan(5,'SN'+suffix.padStart(12,'0'))).status,404)});
await test('仪器工位拒绝手工扫码',async()=>assert.equal((await scan(3,main)).status,409));
await test('已有生产历史的批次不得删除',async()=>assert.equal((await req('/api/admin/batches/'+f.batch.id,undefined,'DELETE')).status,409));
}finally{await save('phase2')}
