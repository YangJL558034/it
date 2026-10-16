import fs from 'node:fs/promises'
import {chromium} from '../../.tools/qa/node_modules/playwright/index.mjs'
import signalr from '../../web/node_modules/@microsoft/signalr/dist/cjs/index.js'
import {assert,req,ok,login,fixture,base,secret,suffix,test,save} from './helpers.mjs'

const browser=await chromium.launch({executablePath:'C:/Program Files (x86)/Microsoft/Edge/Application/msedge.exe',headless:true})
const context=await browser.newContext({viewport:{width:1920,height:1080}}),page=await context.newPage()
page.setDefaultTimeout(20000)
const errors=[],scans=[];page.on('pageerror',e=>errors.push(e.message));page.on('request',r=>{if(r.url().endsWith('/api/station/scan'))scans.push(JSON.parse(r.postData()))})
let hub,originalRule,ruleChanged=false
const pause=ms=>new Promise(r=>setTimeout(r,ms))
const shot=async name=>{await fs.mkdir(new URL('../../.local/screenshots/',import.meta.url),{recursive:true});await page.screenshot({path:new URL('../../.local/screenshots/'+name+'.png',import.meta.url).pathname.replace(/^\/([A-Z]:)/,'$1'),fullPage:true})}
try{
  await login();const f=await fixture(),main='MC'+(suffix+'01').padStart(12,'0'),cell='CELL'+(suffix+'01').padStart(12,'0')
  originalRule=(await ok('/api/station/barcode-rules')).find(r=>r.kind==='main')
  await test('TV 匿名只读数据与规则可读，管理、追溯、导出及过站仍须身份',async()=>{
    const tv=await req('/api/tv?lineId='+f.line.id,undefined,'GET','');assert.equal(tv.status,200);assert.equal(tv.data.processes.length,5);assert.deepEqual(tv.data.orders.map(o=>o.id),[f.order.id])
    for(const path of ['/api/catalog','/api/trace?code=x','/api/exports','/api/tests'])assert.equal((await req(path,undefined,'GET','')).status,401)
    assert.equal((await req('/api/admin/lines',{code:'BAD',name:'BAD',workshop:'BAD'},'POST','')).status,401)
    assert.equal((await req('/api/station/scan',{requestId:crypto.randomUUID(),stationId:f.stations[0].id,batchId:f.batch.id,code:main},'POST','')).status,401)
    assert.equal((await req('/hubs/mes/negotiate?negotiateVersion=1',{},'POST','')).status,401)
    const rules=await req('/api/station/barcode-rules',undefined,'GET','');assert.equal(rules.status,200);assert.equal(rules.data.length,4)
  })
  await test('匿名 TV SignalR 仅接收更新通知',async()=>{
    let events=0;hub=new signalr.HubConnectionBuilder().withUrl(base+'/hubs/tv').build();hub.on('changed',data=>{assert.deepEqual(Object.keys(data),['at']);events++});await hub.start()
    await ok('/api/admin/products',{code:'PUB'+suffix,name:'大屏通知验证'})
    for(let i=0;i<30&&events===0;i++)await pause(50);assert.ok(events>0);await hub.stop();hub=null
  })
  await test('TV 直接打开无登录框，品牌正确并显示真实五工序与连接状态',async()=>{
    await page.goto(base+'/tv');await page.locator('.tv-flow>div').nth(4).waitFor();assert.equal(await page.title(),'聚星数据平台-制造MES')
    assert.equal(await page.locator('input[autocomplete="username"]').count(),0);assert.equal(await page.getByRole('button',{name:'管理后台',exact:true}).count(),0)
    await page.getByText('● 实时连接正常',{exact:false}).waitFor();assert.ok((await page.locator('.board-brand').innerText()).includes('聚星数据平台-制造MES'));await shot('juxing-tv')
  })
  async function choose(index){
    const chooser=page.getByRole('dialog',{name:'切换授权工位与工单'})
    await chooser.waitFor();await chooser.locator('.el-select').nth(0).click();await page.getByRole('option',{name:f.stations[index].code+' · '+f.stations[index].name,exact:true}).click()
    await chooser.locator('.el-select').nth(1).click();await page.getByRole('option',{name:f.order.code,exact:true}).click()
    await chooser.locator('.el-select').nth(2).click();await page.getByRole('option',{name:f.batch.code,exact:true}).click()
    await chooser.getByRole('button',{name:'进入工位',exact:true}).click();await chooser.waitFor({state:'hidden'})
  }
  await test('工位达到长度自动提示登录，管理员登录后也不显示跨端管理入口',async()=>{
    await page.goto(base+'/station');const input=page.getByPlaceholder('请扫描条码，登录后开始作业');await input.waitFor()
    await page.locator('.scan-length-hint').waitFor();await input.fill(main.slice(0,-1));await pause(250);assert.equal(await page.getByRole('dialog').count(),0)
    await input.fill(main) // No Enter or Tab.
    const auth=page.getByRole('dialog',{name:'登录后开始扫码作业'});await auth.waitFor()
    await auth.locator('input[autocomplete="username"]').fill('admin');await auth.locator('input[autocomplete="current-password"]').fill(secret.adminPassword)
    await auth.getByRole('button',{name:'登录并继续',exact:true}).click();await auth.waitFor({state:'hidden'});await choose(0)
    await page.getByRole('heading',{name:'主码过站成功',exact:true}).waitFor();assert.equal(scans.length,1)
    for(const name of ['管理后台','工位终端','生产大屏'])assert.equal(await page.getByRole('button',{name,exact:true}).count(),0)
    assert.ok((await page.locator('.topbar').innerText()).includes('聚星数据平台-制造MES'));await shot('juxing-station')
  })
  await test('超长枪码不截断自动放行，回车提交返回明确长度错误',async()=>{
    const input=page.getByPlaceholder('请扫描主码',{exact:true});const count=scans.length
    await input.pressSequentially(main+'9',{delay:2});await pause(250);assert.equal(scans.length,count)
    await input.press('Enter');await page.getByRole('heading',{name:/条码要求 14 位，实际 15 位/}).waitFor();assert.equal(scans.length,count+1)
  })
  await test('连续主码和电芯码按各自长度自动绑定，扫码枪追加回车不重复提交',async()=>{
    await page.getByRole('button',{name:'⇄ 切换工位',exact:true}).click();await choose(1)
    await page.getByPlaceholder('请扫描主码',{exact:true}).fill(main)
    const input=page.getByPlaceholder('请扫描电芯码',{exact:true});await input.waitFor();const count=scans.length
    await input.fill(cell.slice(0,-1));await pause(250);assert.equal(scans.length,count)
    await input.fill(cell);await page.getByRole('heading',{name:'绑定成功',exact:true}).waitFor()
    await page.getByPlaceholder('请扫描主码',{exact:true}).press('Enter');await pause(200);assert.equal(scans.length,count+1)
    assert.equal((await ok('/api/station/summary?stationId='+f.stations[1].id+'&batchId='+f.batch.id)).done,1)
  })
  await test('后台发布新条码长度后终端实时采用，无需改代码',async()=>{
    await page.getByRole('button',{name:'⇄ 切换工位',exact:true}).click();await choose(0)
    await ok('/api/admin/rules',{...originalRule,length:15});ruleChanged=true
    await page.locator('.scan-length-hint').filter({hasText:'15 位自动提交'}).waitFor()
    const code='MC'+(suffix+'99').padStart(13,'0');await page.getByPlaceholder('请扫描主码',{exact:true}).fill(code)
    await page.getByRole('heading',{name:'主码过站成功',exact:true}).waitFor();assert.equal(scans.at(-1).code.length,15)
  })
  await test('终端页面无未处理脚本错误',async()=>assert.deepEqual(errors,[]))
}finally{
  if(ruleChanged&&originalRule)await ok('/api/admin/rules',originalRule)
  if(hub)await hub.stop();await browser.close();await save('terminal-updates')
}
