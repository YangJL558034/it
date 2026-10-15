import {assert,req,ok,login,test,save,fixture,suffix,secret} from './helpers.mjs'
import {execFile} from 'node:child_process'
import {promisify} from 'node:util'
import {fileURLToPath} from 'node:url'
try{
await login();const f=await fixture();
const main=n=>'MC'+(suffix+n).padStart(12,'0'),cell='CELL'+suffix.padStart(12,'0');
const scan=(n,code,auth,extra={})=>req('/api/station/scan',{requestId:crypto.randomUUID(),stationId:f.stations[n-1].id,batchId:f.batch.id,code,...extra},'POST',auth);
const users=[];for(let n=0;n<2;n++){const password='Op9-'+crypto.randomUUID();const user=await ok('/api/admin/users',{account:'race'+suffix+n,name:'并发工位'+n,role:'Operator',password});await ok('/api/admin/grants',{userId:user.id,stationId:f.stations[1].id});const auth=(await ok('/api/auth/login',{account:'race'+suffix+n,password})).token;users.push({user,auth});await scan(1,main(n));}
await test('不同用户/不同请求并发争用同一电芯，只有一个绑定成功',async()=>{for(let n=0;n<2;n++)assert.equal((await scan(2,main(n),users[n].auth)).status,200);const rs=await Promise.all(users.map(u=>scan(2,cell,u.auth)));assert.deepEqual(rs.map(r=>r.status).sort(),[200,409]);assert.equal((await ok(`/api/station/summary?stationId=${f.stations[1].id}&batchId=${f.batch.id}`)).done,1)});
await test('操作员不能访问未授权工位或工序 SOP',async()=>{assert.equal((await scan(1,main(0),users[0].auth)).status,403);assert.equal((await req('/api/sop/current/'+f.processes[0].id,undefined,'GET',users[0].auth)).status,403)});
await test('过期或未加载的 SOP 版本禁止过站',async()=>assert.equal((await scan(1,main(9),undefined,{sopVersionId:crypto.randomUUID()})).status,409));
await test('角色权限由数据库配置，撤销后现有会话立即失权',async()=>{const permission=(await ok('/api/admin/permissions')).find(x=>x.role==='Operator'&&x.permission==='Operate');await ok('/api/admin/permissions/'+permission.id,undefined,'DELETE');try{assert.equal((await req('/api/station/options',undefined,'GET',users[0].auth)).status,403)}finally{await ok('/api/admin/permissions',{role:'Operator',permission:'Operate'})}});
await test('工位授权撤销后不能继续已有扫码上下文',async()=>{const grants=await ok('/api/admin/grants');const g=grants.find(g=>g.userId===users[0].user.id);await ok('/api/admin/grants/'+g.id,undefined,'DELETE');assert.equal((await scan(2,cell,users[0].auth)).status,403)});
await test('等待写事务期间账户被停用，已通过初次认证的排队请求仍被拒绝',async()=>{
  const psql=fileURLToPath(new URL('../../.tools/pg-runtime/pgsql/bin/psql.exe',import.meta.url));
  const args=['-h','127.0.0.1','-p','55432','-U','mesdev','-d','mes_development','-tAc'];
  const run=sql=>promisify(execFile)(psql,[...args,sql],{env:{...process.env,PGPASSWORD:secret.dbPassword}});
  const holding=run('SELECT pg_advisory_lock(74215001); SELECT pg_sleep(4);');
  for(let n=0;n<20;n++){if((await run("SELECT count(*) FROM pg_locks WHERE locktype='advisory' AND objid=74215001 AND granted")).stdout.trim()!=='0')break;await new Promise(r=>setTimeout(r,100));}
  const revoked=ok('/api/admin/users/'+users[1].user.id,{name:'已停用并发测试账户',role:'Operator',active:false},'PUT');
  await new Promise(r=>setTimeout(r,200));
  const pending=req('/api/station/heartbeat',{stationId:f.stations[1].id},'POST',users[1].auth);
  await holding;await revoked;assert.equal((await pending).status,401);
});
}finally{await save('hardening')}
