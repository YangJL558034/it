$ErrorActionPreference='Stop'
$root=Split-Path $PSScriptRoot -Parent
Set-Location $root
New-Item -ItemType Directory -Force .tools,.local | Out-Null
if(!(Test-Path .tools/dotnet/dotnet.exe)){
  Invoke-WebRequest https://dot.net/v1/dotnet-install.ps1 -OutFile .tools/dotnet-install.ps1
  & .tools/dotnet-install.ps1 -Channel 10.0 -InstallDir "$root/.tools/dotnet" -NoPath
}
if(!(Test-Path .tools/pg-runtime/pgsql/bin/pg_ctl.exe)){
  if(!(Test-Path .tools/postgres.zip)){Invoke-WebRequest https://get.enterprisedb.com/postgresql/postgresql-17.11-3-windows-x64-binaries.zip -OutFile .tools/postgres.zip}
  Add-Type -AssemblyName System.IO.Compression.FileSystem
  $zip=[IO.Compression.ZipFile]::OpenRead("$root/.tools/postgres.zip")
  try{foreach($entry in $zip.Entries){if($entry.FullName -match '^pgsql/(bin|lib|share)/'){
    $target=[IO.Path]::GetFullPath((Join-Path "$root/.tools/pg-runtime" $entry.FullName))
    if(!$target.StartsWith("$root\.tools\pg-runtime\",[StringComparison]::OrdinalIgnoreCase)){throw 'Invalid archive path'}
    if($entry.Name){[IO.Directory]::CreateDirectory([IO.Path]::GetDirectoryName($target))|Out-Null;[IO.Compression.ZipFileExtensions]::ExtractToFile($entry,$target,$true)}
  }}}finally{$zip.Dispose()}
}
if(!(Test-Path .tools/vc_redist.x64.exe)){Invoke-WebRequest https://aka.ms/vc14/vc_redist.x64.exe -OutFile .tools/vc_redist.x64.exe}
if((Get-AuthenticodeSignature .tools/vc_redist.x64.exe).Status -ne 'Valid'){throw 'VC++ runtime signature invalid'}
& .tools/dotnet/dotnet.exe restore server/Mes.Api --source https://pkgs.dev.azure.com/dnceng/public/_packaging/dotnet-public/nuget/v3/index.json
if($LASTEXITCODE -ne 0){throw 'Restore failed'}
npm ci --prefix web
if($LASTEXITCODE -ne 0){throw 'npm ci failed'}
Write-Host '开发组件就绪。运行 scripts/start-dev.ps1；另开终端 npm run dev --prefix web。安装包构建另需 Inno Setup。'
