param([string]$Dotnet='', [string]$Iscc='', [string]$PostgresRuntime='')
$ErrorActionPreference='Stop'
$root=Split-Path $PSScriptRoot -Parent
Set-Location $root
if(!$Dotnet){$Dotnet=Join-Path $root '.tools/dotnet/dotnet.exe'}
if(!$Iscc){$Iscc=Join-Path $root '.tools/inno/ISCC.exe'}
if(!$PostgresRuntime){$PostgresRuntime=Join-Path $root '.tools/pg-runtime/pgsql'}
foreach($file in @($Dotnet,$Iscc,(Join-Path $PostgresRuntime 'bin/pg_dump.exe'))){if(!(Test-Path $file)){throw "缺少构建组件: $file"}}
npm run build --prefix web
if($LASTEXITCODE -ne 0){throw 'Frontend build failed'}
New-Item -ItemType Directory -Force server/Mes.Api/wwwroot,artifacts/server,artifacts/setup-helper,artifacts/postgres,artifacts/prerequisites | Out-Null
if(!(Test-Path .tools/vc_redist.x64.exe)){throw '缺少离线 VC++ x64 运行组件，请运行 bootstrap-dev.ps1'}
if((Get-AuthenticodeSignature .tools/vc_redist.x64.exe).Status -ne 'Valid'){throw 'VC++ 运行组件数字签名校验失败'}
Copy-Item .tools/vc_redist.x64.exe artifacts/prerequisites -Force
Copy-Item web/build/* server/Mes.Api/wwwroot -Recurse -Force
& $Dotnet publish server/Mes.Api -c Release -r win-x64 --self-contained true -o artifacts/server -p:PublishSingleFile=false
if($LASTEXITCODE -ne 0){throw 'Server publish failed'}
@(Get-ChildItem server/Mes.Api/Data/Migrations -Filter '*.cs' | Where-Object {$_.BaseName -match '^\d{14}_' -and $_.BaseName -notlike '*.Designer'} | ForEach-Object {$_.BaseName}) | ConvertTo-Json | Set-Content -Encoding UTF8 artifacts/server/schema-manifest.json
& $Dotnet publish installer/Mes.Setup -c Release -r win-x64 --self-contained true -o artifacts/setup-helper
if($LASTEXITCODE -ne 0){throw 'Installer helper publish failed'}
foreach($folder in @('bin','lib','share')){Copy-Item (Join-Path $PostgresRuntime $folder) artifacts/postgres -Recurse -Force}
& $Iscc /Qp ('/DBuildRoot='+ (Join-Path $root 'artifacts')) installer/FactoryMES.iss
if($LASTEXITCODE -ne 0){throw 'Installer compilation failed'}
Get-FileHash artifacts/installer/*.exe -Algorithm SHA256 | Format-List
