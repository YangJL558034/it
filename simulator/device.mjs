// Generic MES HTTP simulator only; not a vendor protocol implementation.
import fs from 'node:fs/promises'
const base=process.env.MES_URL||'http://127.0.0.1:5080',key=process.env.MES_DEVICE_KEY
if(!key||!process.argv[2]){console.error('MES_DEVICE_KEY=<device key> MES_URL=<server> node simulator/device.mjs job.json [PASS|FAIL]');process.exit(1)}
const job=JSON.parse(await fs.readFile(process.argv[2],'utf8')),verdict=process.argv[3]||'PASS'
async function send(path,body){const r=await fetch(base+'/api/instrument/v1/'+path,{method:'POST',headers:{'Content-Type':'application/json','X-Device-Key':key},body:JSON.stringify(body)});const j=await r.json();if(!r.ok||j.accepted===false)throw Error(JSON.stringify(j));return j}
const check=await send('check',job)
const payload={recordId:job.recordId||crypto.randomUUID(),taskId:check.data.taskId,mainCode:job.mainCode,deviceCode:job.deviceCode,stationCode:job.stationCode,testedAt:new Date().toISOString(),programVersion:job.programVersion,verdict,items:job.items.map((s,i)=>({...s,value:verdict==='FAIL'&&i===0?s.lower-1:(s.lower+s.upper)/2,verdict:verdict==='FAIL'&&i===0?'FAIL':'PASS'})),rawData:'SIMULATOR: not a real instrument'}
console.log(await send('results',payload));console.log('Idempotent retry:',await send('results',payload))
