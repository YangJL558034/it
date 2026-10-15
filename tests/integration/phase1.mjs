import {assert,req,ok,login,test,save,fixture,suffix} from './helpers.mjs'
try{
await test('数据库与服务器文件读写健康检查',async()=>assert.equal((await ok('/api/health')).status,'ok'))
await test('匿名访问管理接口返回 401',async()=>assert.equal((await req('/api/admin/users',undefined,'GET','')).status,401))
await login()
let f
await test('产线、五工位、工单、批次建立真实外键关系',async()=>{f=await fixture();assert.equal(f.stations.length,5)})
await test('批次总量不能超过工单计划',async()=>assert.equal((await req('/api/admin/batches',{code:'OVER'+suffix,workOrderId:f.order.id,plan:51})).status,422))
await test('有批次的工单禁止删除或改关联',async()=>{assert.equal((await req('/api/admin/orders/'+f.order.id,undefined,'DELETE')).status,409);assert.equal((await req('/api/admin/orders/'+f.order.id,{...f.order,plan:200},'PUT')).status,409)})
await test('外键不存在不能建立工位',async()=>assert.equal((await req('/api/admin/stations',{code:'BAD'+suffix,name:'bad',lineId:crypto.randomUUID(),processStepId:f.processes[0].id})).status,409))
await test('操作员禁止管理用户和计划',async()=>{const pwd='Operator9-'+crypto.randomUUID();await ok('/api/admin/users',{account:'op'+suffix,name:'权限验收',role:'Operator',password:pwd});const r=await ok('/api/auth/login',{account:'op'+suffix,password:pwd});assert.equal((await req('/api/admin/users',undefined,'GET',r.token)).status,403);assert.equal((await req('/api/admin/lines',{code:'EVIL',name:'bad',workshop:'bad'},'POST',r.token)).status,403)})
await test('退出后令牌不可再次使用',async()=>{const r=await ok('/api/auth/login',{account:'op'+suffix,password:'wrong'});assert.equal(r.authenticated,false);await ok('/api/auth/logout',{});assert.equal((await req('/api/auth/me')).status,401)})
}finally{await save('phase1')}
