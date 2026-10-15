param([string]$JavaHome='C:\Program Files\Android\Android Studio\jbr',[string]$AndroidSdk="$env:LOCALAPPDATA\Android\Sdk",[switch]$Release)
$ErrorActionPreference='Stop'
$root=Split-Path $PSScriptRoot -Parent
Set-Location "$root/web"
$env:JAVA_HOME=$JavaHome;$env:ANDROID_HOME=$AndroidSdk
$env:PATH="$JavaHome/bin;"+$env:PATH
npm run build
if($LASTEXITCODE -ne 0){throw 'Frontend build failed'}
& ./node_modules/.bin/cap.cmd sync android
if($LASTEXITCODE -ne 0){throw 'Capacitor sync failed'}
$variant='Debug'
if($Release){
  $signing=Get-Content -Raw "$root/.local/signing/signing.json" | ConvertFrom-Json
  $env:MES_KEYSTORE=$signing.keystore;$env:MES_KEYSTORE_PASSWORD=$signing.password;$env:MES_KEY_ALIAS=$signing.alias;$variant='Release'
}
& ./android/gradlew.bat -p android "assembleStation$variant" "assembleTv$variant" --console=plain --init-script "$root/scripts/android-mirrors.init.gradle"
if($LASTEXITCODE -ne 0){throw 'Android build failed'}
New-Item -ItemType Directory -Force "$root/artifacts/android" | Out-Null
$folder=$variant.ToLowerInvariant()
Copy-Item "android/app/build/outputs/apk/station/$folder/*.apk" "$root/artifacts/android/mes-station-1.0.0-$folder.apk" -Force
Copy-Item "android/app/build/outputs/apk/tv/$folder/*.apk" "$root/artifacts/android/mes-tv-1.0.0-$folder.apk" -Force
if($Release){Remove-Item Env:MES_KEYSTORE_PASSWORD -ErrorAction SilentlyContinue}
Get-FileHash "$root/artifacts/android/*.apk" -Algorithm SHA256
