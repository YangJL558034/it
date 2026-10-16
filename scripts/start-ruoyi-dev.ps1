param([int]$Port = 5081)
$ErrorActionPreference = 'Stop'
if (-not [Environment]::Is64BitOperatingSystem) { throw '仅支持 64 位 Windows。' }
$root = Split-Path $PSScriptRoot -Parent
Set-Location $root
$local = Join-Path $root '.local/ruoyi'
New-Item -ItemType Directory -Force $local | Out-Null
$configPath = Join-Path $local 'development.json'
function New-Secret {
    $bytes = New-Object byte[] 32
    $rng = [Security.Cryptography.RandomNumberGenerator]::Create()
    try { $rng.GetBytes($bytes); return [Convert]::ToBase64String($bytes) } finally { $rng.Dispose() }
}
if (!(Test-Path $configPath)) {
    @{ adminPassword=('Mes9' + (New-Secret)); redisPassword=(New-Secret); tokenSecret=(New-Secret) } |
        ConvertTo-Json | Set-Content -Encoding UTF8 $configPath
}
$config = Get-Content -Raw $configPath | ConvertFrom-Json
# Reuse only the developer PostgreSQL cluster. Always use a separate database.
$legacy = Get-Content -Raw (Join-Path $root '.local/development.json') | ConvertFrom-Json
$pg = Join-Path $root '.tools/pg-runtime/pgsql/bin'
$env:PGPASSWORD = $legacy.dbPassword
try {
    $exists = & "$pg/psql.exe" -h 127.0.0.1 -p 55432 -U mesdev -d postgres -tAc "SELECT 1 FROM pg_database WHERE datname='mes_ruoyi_development'"
    if ($LASTEXITCODE -ne 0) { throw '现有开发数据库不可用，请先启动开发 PostgreSQL。' }
    if ($exists -ne '1') {
        & "$pg/createdb.exe" -h 127.0.0.1 -p 55432 -U mesdev mes_ruoyi_development
        if ($LASTEXITCODE -ne 0) { throw '隔离数据库创建失败' }
    }
} finally { Remove-Item Env:PGPASSWORD -ErrorAction SilentlyContinue }
$cacheConfig = Join-Path $local 'garnet.conf'
@{ Address='127.0.0.1'; Port=56379; AuthenticationMode='Password'; Password=$config.redisPassword;
    LogMemorySize='128m'; IndexMemorySize='16m'; EnableLua=$true } |
    ConvertTo-Json | Set-Content -Encoding UTF8 $cacheConfig
$listener = Get-NetTCPConnection -LocalPort 56379 -State Listen -ErrorAction SilentlyContinue
if (!$listener) {
    $cache = Join-Path $root '.tools/garnet/net8.0/GarnetServer.exe'
    if (!(Test-Path $cache)) { throw '缺少 Garnet 2.2.2 net8.0 x64 开发验证组件；见迁移说明。' }
    $process = Start-Process $cache -ArgumentList @('--config-import-path', ('"' + $cacheConfig + '"'), '--lua') -WorkingDirectory $local -WindowStyle Hidden -PassThru -RedirectStandardOutput (Join-Path $local 'cache.log') -RedirectStandardError (Join-Path $local 'cache-error.log')
    $process.Id | Set-Content (Join-Path $local 'cache.pid')
}
$env:MES_DB_URL = 'jdbc:postgresql://127.0.0.1:55432/mes_ruoyi_development'
$env:MES_DB_USER = 'mesdev'
$env:MES_DB_PASSWORD = $legacy.dbPassword
$env:MES_REDIS_PORT = '56379'
$env:MES_REDIS_PASSWORD = $config.redisPassword
$env:MES_TOKEN_SECRET = $config.tokenSecret
$env:MES_DATA_DIRECTORY = (Join-Path $local 'data').Replace('\','/')
$env:MES_BOOTSTRAP_PASSWORD_FILE = Join-Path $local 'initial-admin-password'
[IO.File]::WriteAllText($env:MES_BOOTSTRAP_PASSWORD_FILE, $config.adminPassword)
$env:MES_PORT = "$Port"
Write-Host "若依隔离开发服务 http://127.0.0.1:$Port；开发凭据位于 .local/ruoyi/development.json（不提交 Git）。"
try { & java -jar (Join-Path $root 'ruoyi-server/ruoyi-admin/target/ruoyi-admin.jar') }
finally { Remove-Item -LiteralPath $env:MES_BOOTSTRAP_PASSWORD_FILE -ErrorAction SilentlyContinue }
