# Windows PowerShell 5.1 compatible. No Node.js, Python or developer SDK required.
[CmdletBinding()]
param()
$ErrorActionPreference = 'Stop'
$policy = Get-Content -LiteralPath (Join-Path $PSScriptRoot 'platform-policy.json') -Raw | ConvertFrom-Json
if (-not [Environment]::Is64BitOperatingSystem) {
    throw '聚星数据平台-制造MES 仅提供 64 位安装包，不支持 32 位 Windows。'
}
$os = Get-CimInstance Win32_OperatingSystem
$cpu = Get-CimInstance Win32_Processor | Select-Object -First 1
if ($cpu.Architecture -ne 9) { throw '当前安装组件目标为 AMD64/x64，其他处理器架构尚未验收。' }
if ([int]$os.BuildNumber -lt $policy.targetWindowsBuildMinimum) {
    throw '当前 Windows 版本低于本安装包的目标范围，请使用 Windows 10 1607 / Server 2016 或更新的 x64 系统。'
}
[PSCustomObject]@{
    OperatingSystem = $os.Caption
    Build = $os.BuildNumber
    Architecture = 'x64'
    PlatformPrecheck = 'Passed'
    InstallationAcceptance = 'Not implied by precheck'
}
