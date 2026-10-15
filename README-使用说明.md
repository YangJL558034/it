# 智造 MES UI 源码包（r13）

这是已制作的中文 MES 浏览器交互原型，含 PC 管理后台、安卓平板工位界面、生产大屏。
源代码为 HTML、CSS、原生 JavaScript，无需 npm 安装或构建。

## 本地打开

建议通过本地 HTTP 服务访问，文件预览、校验及浏览器存储功能可正常工作。

1. 解压全部文件。
2. 安装 Python 3（如果电脑尚未安装）。
3. Windows 双击 start-windows.bat；或在解压目录运行：
   - Windows：py -3 serve.py
   - macOS / Linux：python3 serve.py
4. 浏览器打开 http://localhost:8000 。
5. 演示管理员账号：admin；密码：demo123。

其他演示账号：supervisor（生产主管）、quality（质量主管）、operator（工位操作员）；统一密码 demo123。
本地服务仅监听本机，不是供工厂多设备共享的正式后台。
如果 8000 端口被占用，可运行 python3 serve.py --port 8001（Windows 将 python3 替换为 py -3）。

## 文件结构

- dist/index.html：页面入口及脚本加载顺序。
- dist/style.css：后台、工位端、大屏和 SOP 的样式。
- dist/app.js：通用组件与基础页面。
- dist/workflow.js：五道生产工序、单入口自动扫码、仪器结果、工单数量、服务器设置。
- dist/board.js：生产大屏。
- dist/admin.js：演示登录、角色、系统配置、备份恢复。
- dist/barcodes.js：条码前缀、位数、字符校验。
- dist/sop.js：按工序管理多文件 SOP、预览和自动播放。
- dist/operations.js：产线工位、工单增改删、批次关联、生产报表与按批次 CSV 导出。
- tests/：用于本次原型的 Node.js 逻辑检查脚本；在包根目录运行 node tests/verify-operations.cjs 等。

## 当前实现范围

- 主码过站 → 主码绑定电芯 → 仪器自动上传 PASS/FAIL → 主码绑定 SN → SN 绑定包装。
- 每个扫码工位只保留一个输入框；扫码枪需配置 Enter 或 Tab 结束符。
- 后台配置条码规则，不合规条码拦截；历史记录及复测不覆盖。
- SOP 不关联产品，只关联工序，支持多文件及后台预览、自动切换。
- 产线、工位、工单和批次关联；已有历史引用的数据禁止直接删除。
- 按工单、批次、条码及测试结果查询；必须选定工单与批次才能导出。

## 数据及正式上线说明

这不是正式 MES 后台或安卓 APK。登录、权限和工序校验目前在浏览器执行。
演示数据保存在该浏览器的 localStorage，SOP 附件保存在 IndexedDB。
本源码包不包含你浏览器中已经录入的数据、SOP 附件、账号凭证或生产数据库。
原网站与本地 http://localhost:8000 属于不同来源，浏览器不会自动共享数据。
如需迁移演示记录，可先在原网站“备份与恢复”下载 JSON，再在本地恢复。
JSON 备份包含 SOP 文件索引，但不包含 SOP 附件；附件需在本地重新上传。

正式上线需要补充：服务端认证与权限、数据库事务和唯一约束、测试仪 API、服务器文件服务、
多设备实时通信、完整数据库与 SOP 文件备份、APK 打包及真实设备测试。
浏览器逻辑检查已完成，不等于工厂环境验收。

源码快照：a5a906e97331a1c7f1c60cf8e05697cba417214f。
