import {assert,req,ok,login,test,save,fixture,suffix,base,token} from './helpers.mjs'
import {pdfFixture} from './pdf-fixture.mjs'
try{
await login();const f=await fixture(),pwd='Quality9-'+crypto.randomUUID();await ok('/api/admin/users',{account:'q'+suffix,name:'审核员',role:'Quality',password:pwd});const q=(await ok('/api/auth/login',{account:'q'+suffix,password:pwd})).token;
const v=await ok('/api/sop/versions',{processStepId:f.processes[0].id,title:'主码工序 SOP'});
const upload=async(files)=>{const body=new FormData();for(const [name,bytes,type] of files)body.append('files',new Blob([bytes],{type}),name);const r=await fetch(base+`/api/sop/versions/${v.id}/files`,{method:'POST',headers:{Authorization:'Bearer '+token},body});return r.status};
await test('未审核 SOP 禁止发布',async()=>assert.equal((await req(`/api/sop/versions/${v.id}/publish`,{})).status,409));
await test('服务器拒绝 SVG/HTML 伪装附件',async()=>assert.equal(await upload([['bad.png','<svg onload="alert(1)"/>','image/png']]),422));
await test('一次上传多份实际 PNG 与 PDF 文件',async()=>assert.equal(await upload([['instruction.png',Buffer.from('iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAwMCAO+aCOsAAAAASUVORK5CYII=','base64'),'image/png'],['instruction.pdf',pdfFixture(),'application/pdf']]),200));
let files=(await ok('/api/sop/versions')).find(x=>x.version.id===v.id).files;
await test('逐份播放顺序和停留时间保存',async()=>{await ok(`/api/sop/versions/${v.id}/files`,files.reverse().map((x,i)=>({id:x.id,seconds:3+i})),'PUT');files=(await ok('/api/sop/versions')).find(x=>x.version.id===v.id).files;assert.equal(files[0].name,'instruction.pdf')});
await test('创建人不能自己审核，其他质量人员审核后可发布',async()=>{assert.equal((await req(`/api/sop/versions/${v.id}/review`,{})).status,409);assert.equal((await req(`/api/sop/versions/${v.id}/review`,{},'POST',q)).status,200);await ok(`/api/sop/versions/${v.id}/publish`,{});assert.equal((await ok(`/api/sop/current/${f.processes[0].id}`)).version.id,v.id)});
await test('发布版本禁止修改，文件必须认证读取',async()=>{assert.equal((await req(`/api/sop/versions/${v.id}/files`,files.map(x=>({id:x.id,seconds:1})),'PUT')).status,422);assert.equal((await fetch(base+'/api/sop/files/'+files[0].id)).status,401)});
await test('过站保存实际发布 SOP 版本',async()=>{const main='MC'+suffix.padStart(12,'0');const r=await ok('/api/station/scan',{requestId:crypto.randomUUID(),stationId:f.stations[0].id,batchId:f.batch.id,code:main});assert.equal(r.trace.passages[0].sopVersionId,v.id)});
await test('重要消息与重复确认幂等',async()=>{const m=await ok('/api/messages',{title:'重要工艺通知',body:'请确认收到',lineId:f.line.id,important:true});await Promise.all(Array.from({length:5},()=>ok('/api/messages/'+m.id+'/ack',{})));assert.equal((await ok('/api/messages/'+m.id+'/acks')).length,1)});
}finally{await save('phase4')}
