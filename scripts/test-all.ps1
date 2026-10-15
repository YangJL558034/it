$ErrorActionPreference='Stop'
$root=Split-Path $PSScriptRoot -Parent
Set-Location $root
foreach($phase in @('phase1','phase2','phase3','phase4','phase5','hardening','simulator')){
  node "tests/integration/$phase.mjs"
  if($LASTEXITCODE -ne 0){throw "$phase failed"}
}
npm run build --prefix web
if($LASTEXITCODE -ne 0){throw 'Frontend build failed'}
Write-Host '集成检查通过。浏览器测试另运行 node tests/integration/ui.mjs。所有测试仅允许隔离开发数据库。'
