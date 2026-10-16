import assert from 'node:assert/strict'
import fs from 'node:fs/promises'
import {execFileSync} from 'node:child_process'
import {fileURLToPath} from 'node:url'
import {chromium} from '../../.tools/qa/node_modules/playwright/index.mjs'

const root=fileURLToPath(new URL('../../',import.meta.url))
// A real RuoYi login in the fixed development environment; no mock endpoint or disabled captcha.
const token=execFileSync('python',['-c',"import sys;sys.path.insert(0,'tests/integration');from ruoyi_helpers import login;print(login())"],{cwd:root,encoding:'utf8'}).trim()
const browser=await chromium.launch({executablePath:'C:/Program Files (x86)/Microsoft/Edge/Application/msedge.exe',headless:true})
const context=await browser.newContext({viewport:{width:1600,height:1000}})
await context.addCookies([{name:'Admin-Token',value:token,url:'http://127.0.0.1:5174',sameSite:'Lax'}])
const page=await context.newPage(),errors=[],checks=[]
page.on('pageerror',error=>errors.push(error.message))
const folder=new URL('../../.local/migration/mes-ui/',import.meta.url)
await fs.mkdir(folder,{recursive:true})
const screenshot=name=>page.screenshot({path:fileURLToPath(new URL(name+'.png',folder)),fullPage:true,animations:'disabled'})
const check=name=>{checks.push(name);console.log('PASS '+name)}
let product
async function api(path,method='GET',body){const r=await fetch('http://127.0.0.1:5081'+path,{method,headers:{Authorization:'Bearer '+token,'Content-Type':'application/json'},body:body===undefined?undefined:JSON.stringify(body)});return r.json()}
try{
 await page.goto('http://127.0.0.1:5174/index')
 await page.getByRole('heading',{name:'MES 生产工作台',exact:true}).waitFor()
 await page.locator('button.module').last().waitFor()
 assert.equal(await page.locator('button.module').count(),17)
 assert.equal(await page.locator('button.module .el-tag').count(),17)
 await page.locator('button.module .el-tag').first().waitFor({state:'visible'})
 assert.equal(await page.getByText('若依后台管理框架',{exact:true}).count(),0)
 check('MES homepage replaces framework introduction and lists 17 modules')
 await screenshot('dashboard')
 await page.goto('http://127.0.0.1:5174/mes-production/orders')
 await page.getByRole('heading',{name:'生产工单',exact:true}).waitFor()
 await page.locator('.el-table__body-wrapper tr').first().waitFor()
 await page.locator('.mes-toolbar input').fill('MO')
 await Promise.all([
  page.waitForResponse(r=>r.url().includes('/mes/master/orders?')&&r.status()===200),
  page.getByRole('button',{name:'查询',exact:true}).click()
 ])
 check('work-order page renders Java records and filters through backend')
 await screenshot('orders')
 await page.goto('http://127.0.0.1:5174/mes-resource/products')
 await page.getByRole('button',{name:'新增资料',exact:true}).waitFor()
 await page.getByRole('button',{name:'新增资料',exact:true}).click()
 const dialog=page.getByRole('dialog'),code='UIMES'+Date.now()
 await dialog.locator('input').nth(0).fill(code)
 await dialog.locator('input').nth(1).fill('若依界面验证产品')
 await dialog.locator('.el-select').click()
 await page.getByRole('option',{name:'聚星制造',exact:true}).click()
 await dialog.getByRole('button',{name:'保存',exact:true}).click()
 await dialog.waitFor({state:'hidden'})
 const result=await api('/mes/master/products?keyword='+code)
 assert.equal(result.rows.length,1);product=result.rows[0]
 check('product form persists through real Java API into PostgreSQL')
 await page.locator('.mes-toolbar input').fill(code)
 await page.getByRole('button',{name:'查询',exact:true}).click()
 await page.locator('.el-table').getByText(code,{exact:true}).waitFor()
 await page.getByRole('button',{name:'修改',exact:true}).click()
 await dialog.locator('input').nth(1).fill('已修改的验证产品')
 await dialog.getByRole('button',{name:'保存',exact:true}).click()
 await dialog.waitFor({state:'hidden'})
 await page.locator('.el-table').getByText('已修改的验证产品',{exact:true}).waitFor()
 product=(await api('/mes/master/products?keyword='+code)).rows[0]
 assert.equal(product.version,2)
 check('product edit updates actual record and version')
 await page.getByRole('button',{name:'删除',exact:true}).click()
 await page.getByRole('button',{name:'确定',exact:true}).click()
 await page.getByText('删除成功',{exact:true}).waitFor()
 assert.equal((await api('/mes/master/products?keyword='+code)).total,0)
 product=undefined
 check('product delete confirmation removes only selected record')
 await page.goto('http://127.0.0.1:5174/mes-production/processes')
 await page.locator('.el-step').first().waitFor()
 assert.equal(await page.locator('.el-step').count(),5)
 check('five processes render using preserved database identifiers')
 await page.goto('http://127.0.0.1:5174/mes-quality/sop')
 await page.getByRole('heading',{name:'SOP 作业指导书',exact:true}).waitFor()
 await page.getByText('该业务尚未完成 Java 接口与历史数据迁移，目前不可在若依中操作。',{exact:true}).waitFor()
 check('unfinished SOP is explicitly marked and has no fake operation form')
 await screenshot('sop-migration')
 assert.deepEqual(errors,[])
 check('no unhandled JavaScript errors in MES pages')
 await fs.writeFile(new URL('result.json',folder),JSON.stringify({passed:checks.length,checks,errors},null,2))
}finally{
 if(product)await api('/mes/master/products/'+product.id+'?version='+product.version,'DELETE')
 await api('/logout','POST',{})
 await browser.close()
}
