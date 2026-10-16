param([Parameter(Mandatory=$true)][string]$ConfigFile,[Parameter(Mandatory=$true)][string]$Archive,[Parameter(Mandatory=$true)][string]$ProgramDir,[string]$Confirmation='')
$ErrorActionPreference='Stop'
if(!([Security.Principal.WindowsPrincipal][Security.Principal.WindowsIdentity]::GetCurrent()).IsInRole([Security.Principal.WindowsBuiltInRole]::Administrator)){throw '请以管理员身份运行离线恢复。'}
if($Confirmation -ne 'RESTORE'){throw '请先确认数据库、SOP 和附件会被整套替换，再显式传入 -Confirmation RESTORE。'}
$cfg=Get-Content -Raw -LiteralPath $ConfigFile | ConvertFrom-Json
$root=[IO.Path]::GetFullPath($cfg.Storage.Root)
$backupRoot=[IO.Path]::GetFullPath($cfg.Storage.Backups)
$stage=Join-Path $backupRoot ('offline-verify-'+[guid]::NewGuid().ToString('N'))
$pre=Join-Path $backupRoot ('offline-before-'+[guid]::NewGuid().ToString('N'))
$marker=Join-Path $root 'restore-maintenance.json'
$builder=New-Object System.Data.Common.DbConnectionStringBuilder
$builder.ConnectionString=$cfg.ConnectionStrings.Mes
$pgArgs=@('-h',[string]$builder['Host'],'-p',[string]$builder['Port'],'-U',[string]$builder['Username'],'-d',[string]$builder['Database'])
$env:PGPASSWORD=[string]$builder['Password']
function Run-Pg([string]$tool,[string[]]$extra){& (Join-Path $cfg.Backup.PgBin ($tool+'.exe')) @pgArgs @extra;if($LASTEXITCODE -ne 0){throw "$tool 执行失败"}}
function Assert-Child([string]$path,[string]$parent){$p=[IO.Path]::GetFullPath($path);$base=[IO.Path]::GetFullPath($parent).TrimEnd('\')+'\';if(!$p.StartsWith($base,[StringComparison]::OrdinalIgnoreCase)){throw "路径不在指定目录内: $p"};return $p}
function Replace-Folder([string]$source,[string]$target){
  $target=[IO.Path]::GetFullPath($target);$parent=Split-Path $target -Parent
  if(!$parent -or $target -eq $root){throw '拒绝替换数据根目录或磁盘根目录'}
  $prepared=Assert-Child ($target+'-recover-'+[guid]::NewGuid().ToString('N')) $parent
  $preserved=Assert-Child ($target+'-preserved-'+[guid]::NewGuid().ToString('N')) $parent
  $checked=Assert-Child $target $parent
  New-Item -ItemType Directory -Force $prepared | Out-Null
  if(Test-Path -LiteralPath $source){Get-ChildItem -LiteralPath $source -Force | Copy-Item -Destination $prepared -Recurse -Force}
  if(Test-Path -LiteralPath $checked){Move-Item -LiteralPath $checked -Destination $preserved}
  Move-Item -LiteralPath $prepared -Destination $checked
}
$restoreStarted=$false
try{
  Add-Type -AssemblyName System.IO.Compression.FileSystem
  New-Item -ItemType Directory -Force $stage | Out-Null
  $zip=[IO.Compression.ZipFile]::OpenRead([IO.Path]::GetFullPath($Archive))
  try{foreach($entry in $zip.Entries){$target=Assert-Child (Join-Path $stage $entry.FullName) $stage;if($entry.Name){[IO.Directory]::CreateDirectory([IO.Path]::GetDirectoryName($target))|Out-Null;[IO.Compression.ZipFileExtensions]::ExtractToFile($entry,$target)}else{[IO.Directory]::CreateDirectory($target)|Out-Null}}}finally{$zip.Dispose()}
  $manifest=Get-Content -Raw (Join-Path $stage 'manifest.json') | ConvertFrom-Json
  if($manifest.Version -ne '1.0'){throw '备份格式不兼容'}
  if((Get-FileHash (Join-Path $stage 'database.dump')).Hash -ne $manifest.DatabaseSha256){throw '数据库备份摘要不符'}
  foreach($entry in $manifest.Files.PSObject.Properties){$path=Assert-Child (Join-Path $stage $entry.Name) $stage;if((Get-FileHash -LiteralPath $path).Hash -ne $entry.Value){throw ('附件校验失败: '+$entry.Name)}}
  $known=Get-Content -Raw (Join-Path $ProgramDir 'schema-manifest.json') | ConvertFrom-Json
  foreach($migration in $manifest.Migrations){if($known -notcontains $migration){throw '备份数据库版本高于当前程序，禁止恢复'}}
  Stop-Service FactoryMES -ErrorAction Stop
  (Get-Service FactoryMES).WaitForStatus('Stopped',[TimeSpan]::FromSeconds(60))
  @{at=[DateTimeOffset]::Now;archive=[IO.Path]::GetFileName($Archive)} | ConvertTo-Json | Set-Content -Encoding UTF8 $marker
  New-Item -ItemType Directory -Force $pre | Out-Null
  Run-Pg 'pg_dump' @('--format=custom','--no-owner','--no-acl','-f',(Join-Path $pre 'database.dump'))
  Copy-Item -LiteralPath $ConfigFile -Destination (Join-Path $pre 'appsettings.Local.json')
  foreach($name in @('Sop','Attachments')){Copy-Item -LiteralPath $cfg.Storage.$name -Destination (Join-Path $pre $name) -Recurse}
  $restoreStarted=$true
  Run-Pg 'pg_restore' @('--clean','--if-exists','--single-transaction','--exit-on-error','--no-owner','--no-acl',(Join-Path $stage 'database.dump'))
  Replace-Folder (Join-Path $stage 'sop') $cfg.Storage.Sop
  Replace-Folder (Join-Path $stage 'attachments') $cfg.Storage.Attachments
  & (Join-Path $ProgramDir 'Mes.Api.exe') --migrate-only --config $ConfigFile --contentRoot $ProgramDir
  if($LASTEXITCODE -ne 0){throw '恢复后的迁移校验失败'}
  Run-Pg 'psql' @('-v','ON_ERROR_STOP=1','-c','DELETE FROM "Session"; DO $$ BEGIN IF to_regclass(''public."StationCredential"'') IS NOT NULL THEN DELETE FROM "StationCredential"; END IF; END $$;')
  foreach($entry in $manifest.Files.PSObject.Properties){$target=$null;if($entry.Name.StartsWith('sop/')){$target=Assert-Child (Join-Path $cfg.Storage.Sop $entry.Name.Substring(4)) $cfg.Storage.Sop};if($entry.Name.StartsWith('attachments/')){$target=Assert-Child (Join-Path $cfg.Storage.Attachments $entry.Name.Substring(12)) $cfg.Storage.Attachments};if($target -and (Get-FileHash -LiteralPath $target).Hash -ne $entry.Value){throw '恢复后附件校验失败'}}
  Remove-Item -LiteralPath $marker
  Start-Service FactoryMES
  Write-Host "离线恢复成功。恢复前备份保留在 $pre。请打开安装访问地址，检查健康及生产记录后再恢复生产。"
}catch{
  if($restoreStarted){try{Run-Pg 'pg_restore' @('--clean','--if-exists','--single-transaction','--exit-on-error','--no-owner','--no-acl',(Join-Path $pre 'database.dump'));Replace-Folder (Join-Path $pre 'Sop') $cfg.Storage.Sop;Replace-Folder (Join-Path $pre 'Attachments') $cfg.Storage.Attachments;Remove-Item -LiteralPath $marker -ErrorAction SilentlyContinue;Start-Service FactoryMES;Write-Warning '恢复失败，已回滚到恢复前状态。'}catch{Write-Warning '自动回滚失败，保持服务停止和维护标记，请联系维护人员。'}}
  throw
}finally{Remove-Item Env:PGPASSWORD -ErrorAction SilentlyContinue}
