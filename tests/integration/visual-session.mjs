import fs from 'node:fs/promises'
import {execFile} from 'node:child_process'
import {promisify} from 'node:util'
import {fileURLToPath} from 'node:url'
import {chromium} from '../../.tools/qa/node_modules/playwright/index.mjs'
import {assert,ok,req,login,fixture,base,suffix,test,save,secret} from './helpers.mjs'
const headers={'Content-Type':'application/json','X-MES-Station-Auth':'1'}
async function auth(path,body,extra={}){const r=await fetch(base+path,{method:'POST',headers:{...headers,...extra},body:JSON.stringify(body)});return {status:r.status,data:await r.json(),headers:r.headers}}
let context
const errors=[]
const profile=new URL('../../.local/qa-persistent-'+suffix,import.meta.url).pathname.replace(/^\/([A-Z]:)/,'$1')
const launch=()=>chromium.launchPersistentContext(profile,{executablePath:'C:/Program Files (x86)/Microsoft/Edge/Application/msedge.exe',headless:true,viewport:{width:1920,height:1080}})
try{
 await login()
 const f=await fixture(),account='remember'+suffix,password='Remember9-'+crypto.randomUUID()
 const user=await ok('/api/admin/users',{account,password,name:'持续登录验收员',role:'Operator'})
 await ok('/api/admin/grants',{userId:user.id,stationId:f.stations[0].id})
 let persistent
 await test('终端凭据与访问令牌分离，凭据不能直接调用生产 API',async()=>{
   persistent=await auth('/api/auth/login',{account,password,rememberStation:true,native:true})
   assert.equal(persistent.status,200);assert.ok(persistent.data.stationToken);assert.notEqual(persistent.data.token,persistent.data.stationToken)
   assert.equal((await req('/api/station/options',undefined,'GET',persistent.data.stationToken)).status,401)
   const resumed=await auth('/api/auth/station/resume',{stationToken:persistent.data.stationToken})
   assert.equal(resumed.data.user.id,user.id)
   assert.equal((await req('/api/station/options',undefined,'GET',resumed.data.token)).status,200)
 })
 await test('访问会话过期后凭据可签发新会话，无需再次输入密码',async()=>{
   const sql='UPDATE "Session" SET "Expires" = NOW() - INTERVAL \'1 minute\' WHERE "UserId" = \''+user.id+'\';'
   await promisify(execFile)(fileURLToPath(new URL('../../.tools/pg-runtime/pgsql/bin/psql.exe',import.meta.url)),['-h','127.0.0.1','-p','55432','-U','mesdev','-d','mes_development','-v','ON_ERROR_STOP=1','-c',sql],{env:{...process.env,PGPASSWORD:secret.dbPassword}})
   assert.equal((await req('/api/station/options',undefined,'GET',persistent.data.token)).status,401)
   const renewed=await auth('/api/auth/station/resume',{stationToken:persistent.data.stationToken})
   assert.equal(renewed.status,200);assert.equal((await req('/api/station/options',undefined,'GET',renewed.data.token)).status,200)
 })
 await test('退出服务器会话后，终端凭据及派生访问令牌同时撤销',async()=>{
   const resumed=await auth('/api/auth/station/resume',{stationToken:persistent.data.stationToken})
   assert.equal((await req('/api/auth/logout',{},'POST',resumed.data.token)).status,200)
   assert.equal((await auth('/api/auth/station/resume',{stationToken:persistent.data.stationToken})).status,401)
   assert.equal((await req('/api/station/options',undefined,'GET',persistent.data.token)).status,401)
 })
 await test('停用账号会撤销持续登录；Cookie 凭据具有 HttpOnly，缺少保护请求头被拒绝',async()=>{
   const r=await auth('/api/auth/login',{account,password,rememberStation:true,native:true})
   await ok('/api/admin/users/'+user.id,{account,name:'持续登录验收员',role:'Operator',active:false},'PUT')
   assert.equal((await auth('/api/auth/station/resume',{stationToken:r.data.stationToken})).status,401)
   await ok('/api/admin/users/'+user.id,{account,name:'持续登录验收员',role:'Operator',active:true},'PUT')
   const cookie=await auth('/api/auth/login',{account,password,rememberStation:true})
   assert.match(cookie.headers.get('set-cookie'),/httponly/i);assert.match(cookie.headers.get('set-cookie'),/path=\/api\/auth\/station/i)
   const missing=await fetch(base+'/api/auth/station/resume',{method:'POST',headers:{'Content-Type':'application/json'},body:'{}'})
   assert.equal(missing.status,403)
 })
 context=await launch();let page=await context.newPage();page.on('pageerror',e=>errors.push(e.message))
 await test('工位首次登录后关闭整个浏览器，再启动自动恢复实际身份',async()=>{
   await page.goto(base+'/station')
   await page.getByRole('button',{name:'登录',exact:true}).click()
   const dialog=page.getByRole('dialog',{name:'登录后开始扫码作业'})
   await dialog.locator('input[autocomplete=username]').fill(account);await dialog.locator('input[autocomplete=current-password]').fill(password)
   await dialog.getByRole('button',{name:'登录并继续'}).click()
   await page.getByRole('dialog',{name:'切换授权工位与工单'}).waitFor()
   await context.close();context=await launch();page=await context.newPage();page.on('pageerror',e=>errors.push(e.message))
   await page.goto(base+'/station')
   await page.getByRole('dialog',{name:'切换授权工位与工单'}).waitFor()
   assert.match(await page.locator('.topbar').innerText(),/持续登录验收员/)
   assert.equal(await page.getByRole('dialog',{name:'登录后开始扫码作业'}).count(),0)
   assert.equal(await page.getByRole('button',{name:'管理后台',exact:true}).count(),0)
   assert.ok(!(await page.evaluate(()=>document.cookie)).includes('mes.station'))
 })
 await test('退出工位并重启后不恢复旧身份，后台仍独立要求登录',async()=>{
   // Close the selection dialog through its supported selection workflow before logout.
   const chooser=page.getByRole('dialog',{name:'切换授权工位与工单'})
   const selects=chooser.locator('.el-select')
   await selects.nth(0).click();await page.getByRole('option',{name:f.stations[0].code+' · '+f.stations[0].name,exact:true}).click()
   await selects.nth(1).click();await page.getByRole('option',{name:f.order.code,exact:true}).click()
   await selects.nth(2).click();await page.getByRole('option',{name:f.batch.code,exact:true}).click()
   await chooser.getByRole('button',{name:'进入工位'}).click();await chooser.waitFor({state:'hidden'})
   await page.getByRole('button',{name:'退出',exact:true}).click()
   await page.getByRole('heading',{name:'工位工作台',exact:true}).waitFor()
   await context.close();context=await launch();page=await context.newPage()
   await page.goto(base+'/station');await page.getByRole('heading',{name:'工位工作台',exact:true}).waitFor()
   await page.goto(base+'/pc');await page.getByRole('heading',{name:'登录生产工作台'}).waitFor()
 })
 await test('后台工单分页、搜索、中文状态、系统设置卡片可用',async()=>{
   await page.locator('input[autocomplete=username]').fill('admin');await page.locator('input[autocomplete=current-password]').fill(secret.adminPassword)
   await page.getByRole('button',{name:'登录',exact:true}).click()
   await page.locator('.admin-list .el-table__row').first().waitFor()
   assert.equal(await page.locator('.admin-list .el-table__body-wrapper .el-table__row').count(),10)
   await page.getByPlaceholder('搜索生产工单编码、名称或关联信息').fill(f.order.code)
   await page.waitForFunction(()=>document.querySelectorAll('.admin-list .el-table__body-wrapper .el-table__row').length===1)
   assert.match(await page.locator('.admin-list').innerText(),/进行中/)
   await page.screenshot({path:'.local/screenshots/admin-filter-1.0.2.png',fullPage:true})
   await page.getByRole('button',{name:'备份恢复与系统配置',exact:true}).click()
   await page.getByText('自动备份策略',{exact:true}).waitFor()
   assert.equal(await page.locator('.settings-layout .panel').count(),2)
 })
 await test('TV 小时聚合来自真实过站，按产线隔离',async()=>{
   const code='MC'+(suffix+'8').padStart(12,'0')
   await ok('/api/station/scan',{requestId:crypto.randomUUID(),stationId:f.stations[0].id,batchId:f.batch.id,code})
   const snapshot=await (await fetch(base+'/api/tv?lineId='+f.line.id)).json()
   assert.equal(snapshot.hourly.length,24);assert.equal(snapshot.hourly.reduce((n,h)=>n+h.started,0),1)
   assert.equal(snapshot.started,1);assert.equal(snapshot.hourly.reduce((n,h)=>n+h.completed,0),0)
   const empty=await (await fetch(base+'/api/tv?lineId='+crypto.randomUUID())).json()
   assert.equal(empty.started,0);assert.equal(empty.tested,0);assert.equal(empty.hourly.reduce((n,h)=>n+h.started,0),0)
 })
 await test('TV 1080p/4K 图表与五道工序完整显示，无页面溢出；空范围不伪造合格率',async()=>{
   await page.goto(base+'/tv');await page.locator('.board-chart canvas').first().waitFor()
   for(const [width,height] of [[1920,1080],[3840,2160]]){
     await page.setViewportSize({width,height});await page.waitForTimeout(350)
     assert.equal(await page.locator('.board-chart canvas').count(),2);assert.equal(await page.locator('.tv-flow>div').count(),5)
     const bounds=await page.evaluate(()=>({w:document.documentElement.scrollWidth,h:document.documentElement.scrollHeight,vw:innerWidth,vh:innerHeight}))
     assert.ok(bounds.w<=bounds.vw+1&&bounds.h<=bounds.vh+1,JSON.stringify(bounds))
     await page.screenshot({path:'.local/screenshots/tv-'+width+'-1.0.2.png'})
   }
   await page.selectOption('select[aria-label="产线"]',f.line.id)
   await page.waitForFunction(()=>document.querySelector('.quality-center span')?.textContent==='等待测试数据')
   assert.match(await page.locator('.quality-center').innerText(),/—/)
 })
 await test('新版界面无未处理脚本异常',async()=>assert.deepEqual(errors,[]))
}finally{await save('visual-session');await context?.close()}
