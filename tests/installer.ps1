$ErrorActionPreference='Stop'
$root=Split-Path $PSScriptRoot -Parent
Set-Location $root
$helper=Join-Path $root 'artifacts/setup-helper/Mes.Setup.exe'
$requestFile=Join-Path $root '.local/installer-validation.json'
$results=@()
function Check([string]$name,[hashtable]$changes,[int]$exit,[string]$verb='validate'){
  $request=@{ProgramDir=(Join-Path $root '.local/installer-test-program');DataDir=(Join-Path $root '.local/installer-test-data');Port=58080;DbPort=55433;DbName='mes_test';DbMode='new';AdminPassword='TestOnly9-'+[guid]::NewGuid().ToString('N');SopDir=(Join-Path $root '.local/installer-test-data/sop');AttachmentsDir=(Join-Path $root '.local/installer-test-data/attachments');LogsDir=(Join-Path $root '.local/installer-test-data/logs');BackupsDir=(Join-Path $root '.local/installer-test-data/backups')}
  foreach($key in $changes.Keys){$request[$key]=$changes[$key]}
  $request | ConvertTo-Json | Set-Content -Encoding UTF8 $requestFile
  & $helper $verb $requestFile *> $null
  if($LASTEXITCODE -ne $exit){throw "$name failed (exit $LASTEXITCODE)"}
  $script:results+=@{name=$name;status='passed'};Write-Host "PASS $name"
}
try{
  Check '有效独立路径配置通过只读预检' @{} 0
  Check '程序与数据互相包含被拒绝' @{DataDir=(Join-Path $root '.local/installer-test-program/data')} 1
  Check '业务目录等于数据根目录被拒绝' @{SopDir=(Join-Path $root '.local/installer-test-data')} 1
  Check '业务目录互相包含被拒绝' @{BackupsDir=(Join-Path $root '.local/installer-test-data/sop/backup')} 1
  Check '弱初始密码被拒绝' @{AdminPassword='demo123'} 1
  Check '服务与数据库端口冲突被拒绝' @{Port=55433} 1
  $secret=Get-Content -Raw .local/development.json | ConvertFrom-Json
  Check '已有非空 PostgreSQL 数据库禁止首次安装覆盖（真实连接只读检查）' @{DbMode='existing';DbHost='127.0.0.1';DbPort=55432;DbName='mes_development';DbUser='mesdev';DbPassword=$secret.dbPassword} 1 'validate-database'
  @{at=[DateTimeOffset]::UtcNow.ToString('O');results=$results} | ConvertTo-Json -Depth 5 | Set-Content -Encoding UTF8 .local/test-results/installer.json
}finally{Remove-Item -LiteralPath $requestFile -ErrorAction SilentlyContinue;Remove-Item -LiteralPath ($requestFile+'.error.txt') -ErrorAction SilentlyContinue}
