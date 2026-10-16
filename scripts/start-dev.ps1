param([int]$Port=5080,[string]$AdminPreviewUrl='',[string]$ListenAddress='127.0.0.1')
$ErrorActionPreference='Stop'
$root=Split-Path $PSScriptRoot -Parent
$dotnet=Join-Path $root '.tools/dotnet/dotnet.exe'
if(!(Test-Path $dotnet)){$dotnet='dotnet'}
$pg=Join-Path $root '.tools/pg-runtime/pgsql/bin'
$local=Join-Path $root '.local'
New-Item -ItemType Directory -Force $local | Out-Null
$secrets=Join-Path $local 'development.json'
if(!(Test-Path $secrets)){
  $rng=[System.Security.Cryptography.RandomNumberGenerator]::Create();$bytes=New-Object byte[] 24;$rng.GetBytes($bytes);$dbPassword=[Convert]::ToBase64String($bytes)
  $rng.GetBytes($bytes);$adminPassword='Mes9'+[Convert]::ToBase64String($bytes)
  @{dbPassword=$dbPassword;adminPassword=$adminPassword} | ConvertTo-Json | Set-Content -Encoding UTF8 $secrets
}
$secret=Get-Content -Raw $secrets | ConvertFrom-Json
$env:PGPASSWORD=$secret.dbPassword
$data=Join-Path $local 'pgdata-dev'
if(!(Test-Path (Join-Path $data 'PG_VERSION'))){
  $pw=Join-Path $local 'pg-init-password';[IO.File]::WriteAllText($pw,$secret.dbPassword)
  # Only development initialization skips the initial full-directory fsync. PostgreSQL runtime fsync remains enabled.
  & "$pg/initdb.exe" -D $data -U mesdev -A scram-sha-256 --pwfile=$pw -E UTF8 --locale=C --no-sync
  if($LASTEXITCODE -ne 0){throw 'initdb failed'}
  Remove-Item -LiteralPath $pw
}
& "$pg/pg_ctl.exe" status -D $data *> $null
if($LASTEXITCODE -ne 0){& "$pg/pg_ctl.exe" start -D $data -l (Join-Path $local 'postgres.log') -o '-p 55432 -h 127.0.0.1' -w}
$exists=& "$pg/psql.exe" -h 127.0.0.1 -p 55432 -U mesdev -d postgres -tAc "SELECT 1 FROM pg_database WHERE datname='mes_development'"
if($exists -ne '1'){& "$pg/createdb.exe" -h 127.0.0.1 -p 55432 -U mesdev mes_development}
$env:ConnectionStrings__Mes="Host=127.0.0.1;Port=55432;Database=mes_development;Username=mesdev;Password=$($secret.dbPassword)"
$env:Bootstrap__Password=$secret.adminPassword
$env:Storage__Root=Join-Path $local 'storage'
$env:Backup__PgBin=$pg
$env:ASPNETCORE_URLS="http://${ListenAddress}:$Port"
$env:ASPNETCORE_ENVIRONMENT='Development'
$env:Preview__AdminUrl=$AdminPreviewUrl
Write-Host "开发服务监听 http://${ListenAddress}:$Port；开发管理员凭据保存于 .local/development.json（不提交仓库）"
if($AdminPreviewUrl){Write-Host "本机管理入口转向若依预览：$AdminPreviewUrl"}
& $dotnet run --project (Join-Path $root 'server/Mes.Api') --no-launch-profile
