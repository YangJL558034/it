#ifndef BuildRoot
  #define BuildRoot "..\artifacts"
#endif
[Setup]
AppId={{9D67ED51-9F61-4BCD-A5C4-8245A748D1B7}
AppName=智造 MES 服务器
AppVersion=1.0.0
DefaultDirName={autopf}\FactoryMES
DefaultGroupName=智造 MES
OutputDir={#BuildRoot}\installer
OutputBaseFilename=FactoryMES-Setup-1.0.0-win-x64
ArchitecturesAllowed=x64compatible
ArchitecturesInstallIn64BitMode=x64compatible
PrivilegesRequired=admin
MinVersion=10.0
WizardStyle=modern
WizardSizePercent=120
Compression=lzma2/fast
SolidCompression=yes
DiskSpanning=no
SetupLogging=yes
DisableProgramGroupPage=yes
UninstallDisplayIcon={app}\Mes.Api.exe
CloseApplications=yes
ExtraDiskSpaceRequired=3221225472

[Messages]
ButtonNext=下一步(&N) >
ButtonBack=< 上一步(&B)
ButtonInstall=安装(&I)
ButtonCancel=取消
ButtonFinish=完成(&F)
WelcomeLabel1=欢迎安装智造 MES 服务器
WelcomeLabel2=本安装包包含后台、前端、PostgreSQL 和迁移工具，可离线安装。%n%n程序与业务数据分开保存。首次安装不会覆盖已有数据库。升级前将自动备份旧版本和数据。%n%n继续前请确认本机为测试或已批准的正式服务器。
FinishedHeadingLabel=智造 MES 安装完成
FinishedLabel=数据库、附件目录和 HTTP 健康检查已通过。访问地址显示在下方，并保存于数据目录的“访问地址.txt”。

[Files]
Source: "{#BuildRoot}\setup-helper\Mes.Setup.exe"; Flags: dontcopy
Source: "{#BuildRoot}\prerequisites\vc_redist.x64.exe"; DestDir: "{tmp}"; Flags: deleteafterinstall
Source: "{#BuildRoot}\server\*"; DestDir: "{app}"; Flags: ignoreversion recursesubdirs createallsubdirs
Source: "{#BuildRoot}\postgres\*"; DestDir: "{app}\postgres"; Flags: ignoreversion recursesubdirs createallsubdirs
Source: "{#BuildRoot}\setup-helper\Mes.Setup.exe"; DestDir: "{app}\installer"; Flags: ignoreversion
Source: "..\docs\*"; DestDir: "{app}\docs"; Flags: ignoreversion recursesubdirs
Source: "..\scripts\recover-windows.ps1"; DestDir: "{app}\maintenance"; Flags: ignoreversion

[Icons]
Name: "{group}\管理后台"; Filename: "{code:AdminUrl}"
Name: "{group}\数据目录与访问地址"; Filename: "{code:DataDir}"

[UninstallRun]
Filename: "{app}\installer\Mes.Setup.exe"; Parameters: "remove services"; Flags: runhidden waituntilterminated; RunOnceId: "StopMesServices"

[Code]
var
  DataPage: TInputDirWizardPage;
  StoragePage: TInputDirWizardPage;
  ServerPage: TInputQueryWizardPage;
  DatabaseModePage: TInputOptionWizardPage;
  DatabasePage: TInputQueryWizardPage;
  PasswordPage: TInputQueryWizardPage;
  FirewallPage: TInputOptionWizardPage;
  RequestFile, HelperPath, OldRoot: String;

function Q(Value: String): String;
begin
  StringChangeEx(Value, '\', '\\', True);
  StringChangeEx(Value, '"', '\"', True);
  StringChangeEx(Value, #13, '\r', True);
  StringChangeEx(Value, #10, '\n', True);
  Result := '"' + Value + '"';
end;

function DataDir(Param: String): String;
begin Result := DataPage.Values[0]; end;

function AdminUrl(Param: String): String;
begin Result := 'http://localhost:' + ServerPage.Values[0] + '/pc'; end;

procedure InitializeWizard();
begin
  DataPage := CreateInputDirPage(wpSelectDir, '独立业务数据目录', '数据库、配置和附件必须与程序分开', '升级时请选择原 MES 数据目录；卸载将保留业务数据。', False, '');
  DataPage.Add('业务数据目录'); DataPage.Values[0] := ExpandConstant('{commonappdata}\FactoryMES');
  StoragePage := CreateInputDirPage(DataPage.ID, '文件保存目录', '可分别配置 SOP、附件、日志和备份', '请选择业务数据目录下的独立子目录，也可选择其他数据磁盘。', False, '');
  StoragePage.Add('SOP 文件'); StoragePage.Add('测试附件'); StoragePage.Add('日志'); StoragePage.Add('备份');
  StoragePage.Values[0] := DataPage.Values[0]+'\sop'; StoragePage.Values[1] := DataPage.Values[0]+'\attachments'; StoragePage.Values[2] := DataPage.Values[0]+'\logs'; StoragePage.Values[3] := DataPage.Values[0]+'\backups';
  OldRoot := DataPage.Values[0];
  ServerPage := CreateInputQueryPage(StoragePage.ID, 'MES 服务端口', '局域网终端通过此端口连接', '安装器会检查端口占用；升级保留原配置。');
  ServerPage.Add('HTTP 服务端口（1024–65535）',False); ServerPage.Values[0] := '8080';
  DatabaseModePage := CreateInputOptionPage(ServerPage.ID, 'PostgreSQL 数据库', '新建专用实例或使用已有 PostgreSQL', '首次安装仅使用空数据库；不会覆盖已有业务表。升级保留原数据库连接。',True,False);
  DatabaseModePage.Add('安装内置 PostgreSQL（离线，新建专用实例）'); DatabaseModePage.Add('连接已有 PostgreSQL（新数据库或空数据库）'); DatabaseModePage.SelectedValueIndex := 0;
  DatabasePage := CreateInputQueryPage(DatabaseModePage.ID, '数据库连接', '填写数据库地址及名称', '新建模式仅使用数据库端口和名称，内部账户及密码自动随机生成。已有模式填写有建库/迁移权限的账户。');
  DatabasePage.Add('服务器',False); DatabasePage.Add('端口',False); DatabasePage.Add('数据库名称',False); DatabasePage.Add('账户（已有模式）',False); DatabasePage.Add('密码（已有模式）',True);
  DatabasePage.Values[0]:='127.0.0.1'; DatabasePage.Values[1]:='55433'; DatabasePage.Values[2]:='factory_mes';
  PasswordPage := CreateInputQueryPage(DatabasePage.ID, '初始管理员', '设置 admin 账户密码', '至少 12 位，包含字母和数字。升级不会重置已有管理员密码。');
  PasswordPage.Add('初始管理员密码',True); PasswordPage.Add('再次输入密码',True);
  FirewallPage := CreateInputOptionPage(PasswordPage.ID, '局域网访问', '是否配置 Windows 防火墙', '只开放所选 MES TCP 端口，限专用和域网络。数据库不向局域网开放。',False,False);
  FirewallPage.Add('允许局域网终端访问 MES 服务'); FirewallPage.Values[0]:=True;
end;

function NextButtonClick(CurPageID: Integer): Boolean;
begin
  Result := True;
  if (CurPageID=DataPage.ID) and (OldRoot<>DataPage.Values[0]) then begin
    StoragePage.Values[0]:=DataPage.Values[0]+'\sop'; StoragePage.Values[1]:=DataPage.Values[0]+'\attachments'; StoragePage.Values[2]:=DataPage.Values[0]+'\logs'; StoragePage.Values[3]:=DataPage.Values[0]+'\backups'; OldRoot:=DataPage.Values[0];
  end;
  if CurPageID=PasswordPage.ID then begin
    if PasswordPage.Values[0]<>PasswordPage.Values[1] then begin MsgBox('两次密码不一致。',mbError,MB_OK); Result:=False; end;
  end;
end;

procedure WriteRequest();
var Text, Mode, Firewall: String;
begin
  if DatabaseModePage.SelectedValueIndex=0 then Mode:='new' else Mode:='existing';
  if FirewallPage.Values[0] then Firewall:='true' else Firewall:='false';
  Text := '{"ProgramDir":'+Q(ExpandConstant('{app}'))+',"DataDir":'+Q(DataPage.Values[0])+',"Port":'+ServerPage.Values[0]+',"SopDir":'+Q(StoragePage.Values[0])+',"AttachmentsDir":'+Q(StoragePage.Values[1])+',"LogsDir":'+Q(StoragePage.Values[2])+',"BackupsDir":'+Q(StoragePage.Values[3])+',"DbMode":'+Q(Mode)+',"DbHost":'+Q(DatabasePage.Values[0])+',"DbPort":'+DatabasePage.Values[1]+',"DbName":'+Q(DatabasePage.Values[2])+',"DbUser":'+Q(DatabasePage.Values[3])+',"DbPassword":'+Q(DatabasePage.Values[4])+',"AdminPassword":'+Q(PasswordPage.Values[0])+',"Firewall":'+Firewall+'}';
  if not SaveStringToFile(RequestFile,UTF8Encode(Text),False) then RaiseException('无法写入受保护安装配置。');
end;

function PrepareToInstall(var NeedsRestart: Boolean): String;
var Code: Integer; Error: AnsiString;
begin
  Result:='';
  ExtractTemporaryFile('Mes.Setup.exe'); HelperPath:=ExpandConstant('{tmp}\Mes.Setup.exe');
  RequestFile:=ExpandConstant('{tmp}\mes-private\request.json');
  if not Exec(HelperPath,'secure '+AddQuotes(ExpandConstant('{tmp}\mes-private')),'',SW_HIDE,ewWaitUntilTerminated,Code) or (Code<>0) then begin Result:='无法创建受保护安装配置目录。'; Exit; end;
  WriteRequest();
  WizardForm.StatusLabel.Caption:='检查系统、端口、数据库和磁盘；升级时备份旧版本…';
  if not Exec(HelperPath,'prepare '+AddQuotes(RequestFile),'',SW_HIDE,ewWaitUntilTerminated,Code) or (Code<>0) then begin
    LoadStringFromFile(RequestFile+'.error.txt',Error); Result:='安装预检失败：'+UTF8Decode(Error); DeleteFile(RequestFile);
  end;
end;

procedure CurStepChanged(CurStep: TSetupStep);
var Code: Integer; Error, Addresses: AnsiString;
begin
  if CurStep=ssPostInstall then begin
    WizardForm.StatusLabel.Caption:='安装离线 Visual C++ 运行组件…';
    if not Exec(ExpandConstant('{tmp}\vc_redist.x64.exe'),'/install /quiet /norestart','',SW_HIDE,ewWaitUntilTerminated,Code) or ((Code<>0) and (Code<>3010) and (Code<>1638)) then RaiseException('Visual C++ 运行组件安装失败。');
    WriteRequest(); WizardForm.StatusLabel.Caption:='初始化数据库、注册服务并执行健康检查，请稍候…';
    if not Exec(HelperPath,'apply '+AddQuotes(RequestFile),'',SW_HIDE,ewWaitUntilTerminated,Code) or (Code<>0) then begin
      LoadStringFromFile(RequestFile+'.error.txt',Error); RaiseException('服务配置失败：'+UTF8Decode(Error)+#13#10+'请查看所选日志目录中的 installation.log。已有数据保留。');
    end;
    if LoadStringFromFile(DataPage.Values[0]+'\访问地址.txt',Addresses) then WizardForm.FinishedLabel.Caption:=UTF8Decode(Addresses);
  end;
end;

procedure DeinitializeSetup();
begin if RequestFile<>'' then DeleteFile(RequestFile); end;
