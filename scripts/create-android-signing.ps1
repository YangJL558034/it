param([string]$JavaHome='C:\Program Files\Android\Android Studio\jbr')
$ErrorActionPreference='Stop'
$root=Split-Path $PSScriptRoot -Parent
$dir=Join-Path $root '.local/signing'
if(Test-Path "$dir/mes-release.jks"){throw '签名密钥已存在，不会覆盖。请使用已有密钥。'}
New-Item -ItemType Directory -Force $dir | Out-Null
$sid=[Security.Principal.WindowsIdentity]::GetCurrent().User.Value
& icacls.exe $dir /inheritance:r /grant:r "*${sid}:(OI)(CI)F" '*S-1-5-18:(OI)(CI)F' '*S-1-5-32-544:(OI)(CI)F' | Out-Null
if($LASTEXITCODE -ne 0){throw '无法限制签名密钥目录权限'}
$bytes=New-Object byte[] 32;[Security.Cryptography.RandomNumberGenerator]::Create().GetBytes($bytes)
$env:MES_KEYSTORE_PASSWORD=[Convert]::ToBase64String($bytes)
& "$JavaHome/bin/keytool.exe" -genkeypair -keystore "$dir/mes-release.jks" -alias mes -keyalg RSA -keysize 3072 -validity 10000 -dname 'CN=Factory MES Local Release, O=Factory MES' -storepass:env MES_KEYSTORE_PASSWORD -keypass:env MES_KEYSTORE_PASSWORD -noprompt
if($LASTEXITCODE -ne 0){throw '创建签名密钥失败'}
@{keystore="$dir/mes-release.jks";alias='mes';password=$env:MES_KEYSTORE_PASSWORD} | ConvertTo-Json | Set-Content -Encoding UTF8 "$dir/signing.json"
Remove-Item Env:MES_KEYSTORE_PASSWORD
Write-Host '签名密钥已保存到 .local/signing。请单独加密备份此目录；后续升级必须使用同一密钥。'
