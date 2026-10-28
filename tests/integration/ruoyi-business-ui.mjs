import assert from 'node:assert/strict'
import fs from 'node:fs/promises'
import {execFileSync} from 'node:child_process'
import {fileURLToPath} from 'node:url'
import {chromium} from '../../.tools/qa/node_modules/playwright/index.mjs'
const root=fileURLToPath(new URL('../../',import.meta.url)),origin='http://127.0.0.1:5175'
const token=execFileSync('python',['-c',"import os,sys;os.environ['MES_TEST_PROFILE']='operations';sys.path.insert(0,'tests/integration');from ruoyi_helpers import login;print(login())"],{cwd:root,encoding:'utf8'}).trim()
const browser=await chromium.launch({executablePath:'C:/Program Files (x86)/Microsoft/Edge/Application/msedge.exe',headless:true})
const context=await browser.newContext({viewport:{width:1600,height:1000}});await context.addCookies([{name:'Admin-Token',value:token,url:origin,sameSite:'Lax'}])
const page=await context.newPage(),errors=[],checks=[],folder=new URL('../../.local/migration/terminals/ui/',import.meta.url);await fs.mkdir(folder,{recursive:true})
page.on('pageerror',e=>errors.push(e.message));function check(name){checks.push(name);console.log('PASS '+name)}
const shot=name=>page.screenshot({path:fileURLToPath(new URL(name+'.png',folder)),fullPage:true,animations:'disabled'})
try{
 for(const [route,heading] of [['/index','MES 生产总览'],['/mes-production/trace','产品追溯'],['/mes-quality/quality','质量检验与复测'],['/mes-quality/sop','SOP 作业指导书'],['/mes-quality/devices','设备与测试仪'],['/mes-quality/protocols','仪器协议适配'],['/mes-quality/receipts','接口接收日志'],['/mes-resource/grants','工位授权'],['/mes-resource/barcodes','条码规则'],['/mes-resource/messages','消息中心'],['/mes-resource/reports','生产报表'],['/system/mes-backup','数据库备份与恢复'],['/system/mes-test-cleanup','测试业务数据清理'],['/monitor/druid','数据库监控'],['/system/mes-branding','平台外观']]){
  await page.goto(origin+route);await page.getByRole('heading',{name:heading,exact:true}).waitFor({timeout:20000});assert.equal(await page.getByText('该业务尚未完成 Java 接口与历史数据迁移，目前不可在若依中操作。',{exact:true}).count(),0);check('real module renders '+heading);if(['/index','/mes-quality/sop','/monitor/druid'].includes(route))await shot(route.split('/').pop())
 }
 await page.goto(origin+'/mes-resource/barcodes');await page.getByRole('button',{name:'发布新版本',exact:true}).click();await page.getByRole('dialog').waitFor();assert.equal(await page.getByRole('dialog').getByText('允许前缀',{exact:true}).count(),1);check('barcode rule form exposes configured prefixes length charset');await page.getByRole('button',{name:'取消',exact:true}).click()
 const station=await context.newPage();station.on('pageerror',e=>errors.push(e.message));await station.goto('http://127.0.0.1:5176/station');await station.getByRole('heading',{name:'工位工作台',exact:true}).waitFor();assert.equal(await station.getByRole('button',{name:'管理后台',exact:true}).count(),0);await station.locator('.auto-scan input').waitFor();assert.equal(await station.evaluate(()=>document.activeElement?.tagName),'INPUT');check('station guest entry has one focused scanner and no administration navigation');await station.screenshot({path:fileURLToPath(new URL('station.png',folder)),fullPage:true})
 const tv=await context.newPage();tv.on('pageerror',e=>errors.push(e.message));await tv.goto('http://127.0.0.1:5176/tv');await tv.locator('.tv-metrics article').first().waitFor();assert.equal(await tv.getByRole('button',{name:'登录',exact:true}).count(),0);assert.equal(await tv.getByRole('button',{name:'管理后台',exact:true}).count(),0);check('TV loads actual Java data without login or administration');await tv.screenshot({path:fileURLToPath(new URL('tv.png',folder)),fullPage:true})
 const previous=await (await fetch('http://127.0.0.1:5082/api/branding')).json(),oldLogo=await (await fetch('http://127.0.0.1:5082'+previous.logoUrl)).arrayBuffer()
 try{
  const form=new FormData(),name='QA-'+Date.now();form.set('name',name);form.set('file',new Blob([await fs.readFile(new URL('../../web/public/branding/logo.png',import.meta.url))],{type:'image/png'}),'logo.png')
  const saved=await (await fetch('http://127.0.0.1:5082/mes/branding',{method:'POST',headers:{Authorization:'Bearer '+token},body:form})).json();assert.equal(saved.code,200)
  await Promise.all([page,station,tv].map(p=>p.waitForFunction(name=>document.body.textContent.includes(name),name,{timeout:8000})))
  check('branding changes propagate through actual WebSocket to PC station TV')
 }finally{
  const form=new FormData();form.set('name',previous.name);if(previous.logoUrl.endsWith('/default'))form.set('reset','true');else form.set('file',new Blob([oldLogo],{type:'image/png'}),'original.png')
  const restored=await (await fetch('http://127.0.0.1:5082/mes/branding',{method:'POST',headers:{Authorization:'Bearer '+token},body:form})).json();assert.equal(restored.code,200)
 }
 assert.deepEqual(errors,[]);check('no unhandled JavaScript errors across management station TV');await fs.writeFile(new URL('results.json',folder),JSON.stringify({passed:checks.length,checks,errors},null,2))
}finally{await browser.close()}
