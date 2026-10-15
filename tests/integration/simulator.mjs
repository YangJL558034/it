import fs from 'node:fs/promises'
import {execFile} from 'node:child_process'
import {promisify} from 'node:util'
import {fileURLToPath} from 'node:url'
import {assert,ok,login,test,save,fixture,suffix,base} from './helpers.mjs'
const jobFile=fileURLToPath(new URL('../../.local/simulator-job.json',import.meta.url))
try{
await login();const f=await fixture(),main='MC'+suffix.padStart(12,'0'),cell='CELL'+suffix.padStart(12,'0');
for(const [n,code] of [[0,main],[1,main],[1,cell]])await ok('/api/station/scan',{requestId:crypto.randomUUID(),stationId:f.stations[n].id,batchId:f.batch.id,code});
const items=[{name:'Voltage',unit:'V',lower:4.8,upper:5.2}],deviceCode='SIM'+suffix;
const d=await ok('/api/admin/devices',{code:deviceCode,name:'CLI 设备模拟器验收',stationId:f.stations[2].id,protocol:'generic-v1',programVersion:'SIM-1',items});
await test('独立设备模拟器实际运行 FAIL/PASS 复测及重试去重',async()=>{
  for(const verdict of ['FAIL','PASS']){
    await fs.writeFile(jobFile,JSON.stringify({externalTaskId:crypto.randomUUID(),mainCode:main,deviceCode,stationCode:f.stations[2].code,workOrderId:f.order.id,batchId:f.batch.id,programVersion:'SIM-1',items}));
    const result=await promisify(execFile)(process.execPath,['simulator/device.mjs',jobFile,verdict],{env:{...process.env,MES_URL:base,MES_DEVICE_KEY:d.key}});
    assert.match(result.stdout,/duplicate: true/);
  }
  const trace=await ok('/api/trace?code='+main);assert.equal(trace.tests.length,2);assert.deepEqual(trace.tests.map(x=>x.result.verdict),['FAIL','PASS']);
});
}finally{await fs.rm(jobFile,{force:true});await save('simulator')}
