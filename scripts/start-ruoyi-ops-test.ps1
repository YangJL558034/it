param([switch]$Resume)
$ErrorActionPreference='Stop'
$root=Split-Path $PSScriptRoot -Parent
Set-Location $root
foreach($port in 5082,56380){if(Get-NetTCPConnection -LocalPort $port -State Listen -ErrorAction SilentlyContinue){throw "测试端口 $port 已占用，不停止未知进程"}}
$profile=Join-Path $root '.local/ruoyi-ops-test'
if(!$Resume){
$runId=(Get-Date -Format 'yyyyMMddHHmmss')
$run=Join-Path $profile $runId
New-Item -ItemType Directory -Force $run | Out-Null
$pg=Join-Path $root '.tools/pg-runtime/pgsql/bin'
$legacy=Get-Content -Raw (Join-Path $root '.local/development.json') | ConvertFrom-Json
$source=Get-Content -Raw (Join-Path $root '.local/ruoyi/development.json') | ConvertFrom-Json
function Secret { $bytes=New-Object byte[] 32; $rng=[Security.Cryptography.RandomNumberGenerator]::Create(); try{$rng.GetBytes($bytes);return [Convert]::ToBase64String($bytes)}finally{$rng.Dispose()} }
$database='mes_ruoyi_ops_test_'+$runId
$config=@{adminPassword=$source.adminPassword;redisPassword=(Secret);tokenSecret=(Secret);database=$database;dataDirectory=(Join-Path $run 'data');runDirectory=$run}
$config | ConvertTo-Json | Set-Content -Encoding utf8 (Join-Path $profile 'development.json')
$env:PGPASSWORD=$legacy.dbPassword
try{
 & "$pg/createdb.exe" -h 127.0.0.1 -p 55432 -U mesdev $database
 if($LASTEXITCODE -ne 0){throw '隔离测试数据库创建失败'}
 & "$pg/pg_restore.exe" -h 127.0.0.1 -p 55432 -U mesdev -d $database --no-owner --no-privileges --exit-on-error (Join-Path $root '.local/migration/terminals/before-terminals.dump')
 if($LASTEXITCODE -ne 0){throw '测试快照导入失败；不删除失败数据库'}
}finally{Remove-Item Env:PGPASSWORD -ErrorAction SilentlyContinue}
}else{
 $config=Get-Content -Raw -Encoding utf8 (Join-Path $profile 'development.json') | ConvertFrom-Json
 if(!$config.database.StartsWith('mes_ruoyi_ops_test_')){throw 'Not an isolated operations database'}
 $run=$config.runDirectory;$database=$config.database
 $pg=Join-Path $root '.tools/pg-runtime/pgsql/bin'
 $legacy=Get-Content -Raw -Encoding utf8 (Join-Path $root '.local/development.json') | ConvertFrom-Json
}
$cacheConfig=Join-Path $run 'cache.conf'
@{Address='127.0.0.1';Port=56380;AuthenticationMode='Password';Password=$config.redisPassword;LogMemorySize='128m';IndexMemorySize='16m';EnableLua=$true}|ConvertTo-Json|Set-Content -Encoding utf8 $cacheConfig
$cache=Start-Process (Join-Path $root '.tools/garnet/net8.0/GarnetServer.exe') -ArgumentList @('--config-import-path',('"'+$cacheConfig+'"'),'--lua') -WorkingDirectory $run -WindowStyle Hidden -PassThru -RedirectStandardOutput (Join-Path $run 'cache.log') -RedirectStandardError (Join-Path $run 'cache-error.log')
$env:MES_DB_URL="jdbc:postgresql://127.0.0.1:55432/$database"
$env:MES_DB_USER='mesdev';$env:MES_DB_PASSWORD=$legacy.dbPassword
$env:MES_REDIS_PORT='56380';$env:MES_REDIS_PASSWORD=$config.redisPassword;$env:MES_TOKEN_SECRET=$config.tokenSecret
$env:MES_DATA_DIRECTORY=$config.dataDirectory;$env:MES_PG_BIN=$pg;$env:MES_PORT='5082';$env:MES_BOOTSTRAP_PASSWORD_FILE=''
$jar=Join-Path $root 'ruoyi-server/ruoyi-admin/target/ruoyi-admin.jar'
$runtime=Join-Path $run 'ruoyi-admin.jar';Copy-Item -LiteralPath $jar -Destination $runtime
Write-Host "隔离运维测试 http://127.0.0.1:5082；数据库 $database，不写入当前开发库或安装版数据库。"
try{& java -jar $runtime}finally{if(!$cache.HasExited){Stop-Process -Id $cache.Id};Write-Host '测试数据库与数据目录已保留用于核验。'}
