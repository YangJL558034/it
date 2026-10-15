$ErrorActionPreference='Stop'
$root=Split-Path $PSScriptRoot -Parent
Set-Location $root
if(Get-NetTCPConnection -LocalPort 5081 -State Listen -ErrorAction SilentlyContinue){throw '发布验证专用端口 5081 已占用'}
$secret=Get-Content -Raw .local/development.json | ConvertFrom-Json
$configFile=Join-Path $root '.local/published-smoke.json'
$program=Join-Path $root 'artifacts/server'
$config=@{Urls='http://127.0.0.1:5081';ConnectionStrings=@{Mes="Host=127.0.0.1;Port=55432;Database=mes_development;Username=mesdev;Password=$($secret.dbPassword)"};Storage=@{Root=(Join-Path $root '.local/storage')};Backup=@{PgBin=(Join-Path $root '.tools/pg-runtime/pgsql/bin');IntervalHours=24}}
$config | ConvertTo-Json -Depth 5 | Set-Content -Encoding UTF8 $configFile
$priorDotnetRoot=$env:DOTNET_ROOT
$child=$null
try{
  $env:DOTNET_ROOT=Join-Path $root '.local/nonexistent-dotnet-runtime'
  $child=Start-Process -FilePath (Join-Path $program 'Mes.Api.exe') -ArgumentList @('--config',('"'+$configFile+'"'),'--contentRoot',('"'+$program+'"')) -WindowStyle Hidden -PassThru -RedirectStandardOutput .local/published-stdout.log -RedirectStandardError .local/published-stderr.log
  $health=$null
  for($n=0;$n -lt 40;$n++){try{$health=Invoke-RestMethod http://127.0.0.1:5081/api/health;if($health.status -eq 'ok'){break}}catch{};Start-Sleep -Milliseconds 500}
  if($health.status -ne 'ok'){throw '自包含程序健康检查失败'}
  $html=Invoke-WebRequest http://127.0.0.1:5081/pc
  if($html.StatusCode -ne 200 -or $html.Content -notmatch '/assets/index-'){throw '编译前端未提供'}
  $login=Invoke-RestMethod http://127.0.0.1:5081/api/auth/login -Method Post -ContentType 'application/json' -Body (@{account='admin';password=$secret.adminPassword}|ConvertTo-Json)
  if(!$login.authenticated){throw '发布程序真实登录失败'}
  Invoke-RestMethod http://127.0.0.1:5081/api/auth/logout -Method Post -Headers @{Authorization=('Bearer '+$login.token)} | Out-Null
  @{at=[DateTimeOffset]::UtcNow.ToString('O');results=@(@{name='直接启动 win-x64 自包含 EXE，数据库/文件健康、编译前端及真实登录退出通过';status='passed'})} | ConvertTo-Json -Depth 5 | Set-Content -Encoding UTF8 .local/test-results/published.json
  Write-Host 'PASS published self-contained EXE: health, frontend, login, logout'
}finally{if($child -and !$child.HasExited){Stop-Process -Id $child.Id};$env:DOTNET_ROOT=$priorDotnetRoot;Remove-Item -LiteralPath $configFile -ErrorAction SilentlyContinue}
