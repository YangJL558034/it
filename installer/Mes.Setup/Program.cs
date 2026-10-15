using System.Diagnostics;
using System.Net;
using System.Net.NetworkInformation;
using System.Security.Cryptography;
using System.Security.Principal;
using System.Text.Json;
using System.Text.RegularExpressions;
using Npgsql;

var json=new JsonSerializerOptions{PropertyNameCaseInsensitive=true,WriteIndented=true};
if(args.Length<2){Console.Error.WriteLine("Usage: Mes.Setup secure|prepare|apply|remove|validate <path>");return 2;}
try{
    if(args[0]=="secure"){Directory.CreateDirectory(args[1]);await Secure(args[1],false);return 0;}
    if(args[0]=="remove"){await Run("sc.exe",["stop","FactoryMES"],allowFailure:true);await Run("sc.exe",["delete","FactoryMES"],allowFailure:true);await Run("sc.exe",["stop","FactoryMES-PostgreSQL"],allowFailure:true);await Run("sc.exe",["delete","FactoryMES-PostgreSQL"],allowFailure:true);await Run("netsh.exe",["advfirewall","firewall","delete","rule","name=FactoryMES"],allowFailure:true);return 0;}
    var r=JsonSerializer.Deserialize<Request>(await File.ReadAllTextAsync(args[1]),json)!;
    if(args[0]=="validate"){Validate(r,false);Console.WriteLine("Request validation passed");return 0;}
    if(args[0]=="validate-database"){Validate(r,false);await CheckDatabase(r,false);Console.WriteLine("Database validation passed; no changes made");return 0;}
    Validate(r,true);
    var configPath=Path.Combine(r.DataDir,"configuration","appsettings.Local.json");var upgrade=File.Exists(configPath);
    var statePath=Path.Combine(r.DataDir,"configuration","upgrade-state.json");
    if(args[0]=="prepare"){
        if(upgrade){var prior=JsonDocument.Parse(await File.ReadAllTextAsync(configPath));var priorProgram=Path.GetFullPath(Path.Combine(prior.RootElement.GetProperty("Backup").GetProperty("PgBin").GetString()!,"..",".."));if(!priorProgram.TrimEnd('\\').Equals(Path.GetFullPath(r.ProgramDir).TrimEnd('\\'),StringComparison.OrdinalIgnoreCase))throw new Exception("升级必须选择原程序目录；目录迁移请使用备份恢复流程。");var version=FileVersionInfo.GetVersionInfo(Path.Combine(r.ProgramDir,"Mes.Api.exe")).FileVersion;if(Version.TryParse(version,out var previous)&&previous>new Version(1,0,0,0))throw new Exception("不允许使用旧安装包降级已有程序，请恢复匹配版本的整套备份。");}
        if(Directory.Exists(r.ProgramDir)&&Directory.EnumerateFileSystemEntries(r.ProgramDir).Any()&&!File.Exists(Path.Combine(r.ProgramDir,"Mes.Api.exe")))throw new Exception("程序目录非空且不属于 MES，请选择空目录。");
        if(!upgrade && Directory.Exists(r.DataDir)&&Directory.EnumerateFileSystemEntries(r.DataDir).Any())throw new Exception("数据目录非空，禁止覆盖。请选择新的独立数据目录，或选择已有 MES 的数据目录升级。");
        if(!upgrade){PortFree(r.Port);if(r.DbMode=="new")PortFree(r.DbPort);else await CheckDatabase(r,false);}
        Directory.CreateDirectory(r.DataDir);await Secure(r.DataDir,true);Directory.CreateDirectory(Path.GetDirectoryName(configPath)!);
        if(upgrade){
            await Run("sc.exe",["stop","FactoryMES"],allowFailure:true);await WaitServiceStopped("FactoryMES");
            var cfg=JsonDocument.Parse(await File.ReadAllTextAsync(configPath));var storage=cfg.RootElement.GetProperty("Storage");var connection=cfg.RootElement.GetProperty("ConnectionStrings").GetProperty("Mes").GetString()!;
            var dir=Path.Combine(r.DataDir,"upgrades",DateTime.UtcNow.ToString("yyyyMMdd-HHmmss")+"-"+Guid.NewGuid().ToString("N"));Directory.CreateDirectory(dir);
            try{await Pg(Path.Combine(r.ProgramDir,"postgres","bin"),connection,"pg_dump",["--format=custom","--no-owner","--no-acl","-f",Path.Combine(dir,"database.dump")]);
                CopyDirectory(r.ProgramDir,Path.Combine(dir,"program"));File.Copy(configPath,Path.Combine(dir,"appsettings.Local.json"));
                foreach(var name in new[]{"Sop","Attachments"})CopyDirectory(storage.GetProperty(name).GetString()!,Path.Combine(dir,name));
                await File.WriteAllTextAsync(statePath,JsonSerializer.Serialize(new{directory=dir,connection},json));
            }catch{await Run("sc.exe",["start","FactoryMES"],allowFailure:true);throw;}
        }
        return 0;
    }
    if(args[0]!="apply")throw new Exception("未知操作");
    Directory.CreateDirectory(r.LogsDir);var log=Path.Combine(r.LogsDir,"installation.log");
    try{
        if(!upgrade){
            foreach(var dir in new[]{r.SopDir,r.AttachmentsDir,r.LogsDir,r.BackupsDir}){Directory.CreateDirectory(dir);await Secure(dir,true);}
            if(r.DbMode=="new"){
                r.DbHost="127.0.0.1";r.DbUser="mes_owner";r.DbPassword=Convert.ToHexString(RandomNumberGenerator.GetBytes(32));
                var pgdata=Path.Combine(r.DataDir,"postgresql");if(Directory.Exists(pgdata))throw new Exception("PostgreSQL 数据目录已存在，禁止覆盖。");
                var pw=Path.Combine(r.DataDir,"configuration","pg-init.password");await File.WriteAllTextAsync(pw,r.DbPassword);
                try{await Run(Path.Combine(r.ProgramDir,"postgres","bin","initdb.exe"),["-D",pgdata,"-U",r.DbUser,"--auth=scram-sha-256","--pwfile="+pw,"--encoding=UTF8","--locale=C"]);}finally{File.Delete(pw);}
                await Secure(pgdata,true);
                await Run(Path.Combine(r.ProgramDir,"postgres","bin","pg_ctl.exe"),["register","-N","FactoryMES-PostgreSQL","-D",pgdata,"-S","auto","-U","NT AUTHORITY\\NetworkService","-P","","-o","-p "+r.DbPort+" -h 127.0.0.1"]);
                await Run("sc.exe",["start","FactoryMES-PostgreSQL"]);await WaitDatabase(r);
            }
            await CheckDatabase(r,true);
            var cfg=new{Urls="http://0.0.0.0:"+r.Port,ConnectionStrings=new{Mes=Connection(r)},Storage=new{Root=r.DataDir,Sop=r.SopDir,Attachments=r.AttachmentsDir,Logs=r.LogsDir,Backups=r.BackupsDir},Backup=new{PgBin=Path.Combine(r.ProgramDir,"postgres","bin"),IntervalHours=24,RetentionDays=30},Cors=new{Origins=new[]{"https://localhost","http://localhost"}}};
            await File.WriteAllTextAsync(configPath,JsonSerializer.Serialize(cfg,json));await Secure(Path.GetDirectoryName(configPath)!,true);
        }
        var environment=new Dictionary<string,string>{{"ASPNETCORE_ENVIRONMENT","Production"},{"Bootstrap__Password",r.AdminPassword}};
        await Run(Path.Combine(r.ProgramDir,"Mes.Api.exe"),["--migrate-only","--config",configPath,"--contentRoot",r.ProgramDir],environment);
        var bin="\""+Path.Combine(r.ProgramDir,"Mes.Api.exe")+"\" --config \""+configPath+"\" --contentRoot \""+r.ProgramDir+"\"";
        if(upgrade)await Run("sc.exe",["config","FactoryMES","binPath=",bin,"start=","auto"]);
        else await Run("sc.exe",["create","FactoryMES","binPath=",bin,"start=","auto","obj=","NT AUTHORITY\\LocalService","DisplayName=","Factory MES Server"]);
        await Run("sc.exe",["failure","FactoryMES","reset=","86400","actions=","restart/5000/restart/10000/restart/30000"]);
        var effective=JsonDocument.Parse(await File.ReadAllTextAsync(configPath));var url=effective.RootElement.GetProperty("Urls").GetString()!;var port=new Uri(url).Port;
        if(r.Firewall){await Run("netsh.exe",["advfirewall","firewall","delete","rule","name=FactoryMES"],allowFailure:true);await Run("netsh.exe",["advfirewall","firewall","add","rule","name=FactoryMES","dir=in","action=allow","protocol=TCP","localport="+port,"profile=private,domain"]);}
        await Run("sc.exe",["start","FactoryMES"]);using var http=new HttpClient{Timeout=TimeSpan.FromSeconds(5)};var healthy=false;for(int i=0;i<30;i++){try{var health=JsonDocument.Parse(await http.GetStringAsync("http://127.0.0.1:"+port+"/api/health"));if(health.RootElement.GetProperty("status").GetString()=="ok"){healthy=true;break;}}catch{}await Task.Delay(2000);}if(!healthy)throw new Exception("服务启动后数据库、文件或 HTTP 健康检查未通过。");
        var addresses=Dns.GetHostAddresses(Dns.GetHostName()).Where(x=>x.AddressFamily==System.Net.Sockets.AddressFamily.InterNetwork&&!IPAddress.IsLoopback(x)).Select(x=>"http://"+x+":"+port).ToArray();
        await File.WriteAllTextAsync(Path.Combine(r.DataDir,"访问地址.txt"),"本机: http://localhost:"+port+"/pc\n"+string.Join('\n',addresses.Select(a=>"后台: "+a+"/pc\n工位: "+a+"/station\n大屏: "+a+"/tv")));
        if(File.Exists(statePath))File.Delete(statePath);await File.AppendAllTextAsync(log,DateTimeOffset.Now+" INSTALL/UPGRADE OK; health verified\n");return 0;
    }catch(Exception error){
        await File.AppendAllTextAsync(log,DateTimeOffset.Now+" FAILED: "+error.Message+"\n");
        if(upgrade&&File.Exists(statePath)){
            try{await Run("sc.exe",["stop","FactoryMES"],allowFailure:true);await WaitServiceStopped("FactoryMES");var state=JsonDocument.Parse(await File.ReadAllTextAsync(statePath));var dir=state.RootElement.GetProperty("directory").GetString()!;var connection=state.RootElement.GetProperty("connection").GetString()!;
                await Pg(Path.Combine(dir,"program","postgres","bin"),connection,"pg_restore",["--clean","--if-exists","--no-owner","--no-acl","--single-transaction","--exit-on-error",Path.Combine(dir,"database.dump")]);
                CopyDirectory(Path.Combine(dir,"program"),r.ProgramDir);File.Copy(Path.Combine(dir,"appsettings.Local.json"),configPath,true);var cfg=JsonDocument.Parse(await File.ReadAllTextAsync(configPath));foreach(var name in new[]{"Sop","Attachments"})CopyDirectory(Path.Combine(dir,name),cfg.RootElement.GetProperty("Storage").GetProperty(name).GetString()!);
                await Run("sc.exe",["start","FactoryMES"]);await File.AppendAllTextAsync(log,"Rollback restored prior program, database and attachments.\n");
            }catch(Exception recovery){await File.AppendAllTextAsync(log,"ROLLBACK FAILED: "+recovery.Message+"\n");}
        }throw;
    }finally{if(File.Exists(args[1]))File.Delete(args[1]);}
}catch(Exception e){Console.Error.WriteLine(e.Message);if(args.Length>1&&Path.GetExtension(args[1])==".json")await File.WriteAllTextAsync(args[1]+".error.txt",e.Message);return 1;}

static void Validate(Request r,bool machine){
    if(machine&&(!OperatingSystem.IsWindowsVersionAtLeast(10)||!Environment.Is64BitOperatingSystem))throw new Exception("需要 64 位 Windows 10 / Server 2019 或更新版本。");
    if(machine&&!new WindowsPrincipal(WindowsIdentity.GetCurrent()).IsInRole(WindowsBuiltInRole.Administrator))throw new Exception("请以管理员身份运行安装程序。");
    if(r.Port is <1024 or >65535||r.DbPort is <1024 or >65535||r.Port==r.DbPort)throw new Exception("服务/数据库端口须为不同的 1024–65535 端口。");
    var program=Path.GetFullPath(r.ProgramDir).TrimEnd(Path.DirectorySeparatorChar);var data=Path.GetFullPath(r.DataDir).TrimEnd(Path.DirectorySeparatorChar);
    if(program.Equals(data,StringComparison.OrdinalIgnoreCase)||data.StartsWith(program+Path.DirectorySeparatorChar,StringComparison.OrdinalIgnoreCase)||program.StartsWith(data+Path.DirectorySeparatorChar,StringComparison.OrdinalIgnoreCase))throw new Exception("程序与业务数据目录必须独立，不能互相包含。");
    if(!File.Exists(Path.Combine(data,"configuration","appsettings.Local.json"))&&(r.AdminPassword.Length<12||!r.AdminPassword.Any(char.IsDigit)||!r.AdminPassword.Any(char.IsLetter)))throw new Exception("初始管理员密码至少 12 位，包含字母和数字。");
    if(!Regex.IsMatch(r.DbName,"^[A-Za-z][A-Za-z0-9_]{0,62}$"))throw new Exception("数据库名只能由英文字母、数字、下划线组成，字母开头。");
    var paths=new[]{r.SopDir,r.AttachmentsDir,r.LogsDir,r.BackupsDir}.Select(Path.GetFullPath).Select(p=>p.TrimEnd(Path.DirectorySeparatorChar)).ToArray();
    if(paths.Distinct(StringComparer.OrdinalIgnoreCase).Count()!=4)throw new Exception("SOP、附件、日志和备份目录必须各自独立。");
    foreach(var path in paths){if(path.Equals(data,StringComparison.OrdinalIgnoreCase)||data.StartsWith(path+Path.DirectorySeparatorChar,StringComparison.OrdinalIgnoreCase))throw new Exception("业务子目录不能是数据根目录或其上级目录。");if(path.Equals(program,StringComparison.OrdinalIgnoreCase)||path.StartsWith(program+Path.DirectorySeparatorChar,StringComparison.OrdinalIgnoreCase)||program.StartsWith(path+Path.DirectorySeparatorChar,StringComparison.OrdinalIgnoreCase))throw new Exception("业务文件目录与程序目录不能互相包含。");foreach(var other in paths)if(other!=path&&path.StartsWith(other+Path.DirectorySeparatorChar,StringComparison.OrdinalIgnoreCase))throw new Exception("各业务目录不能互相嵌套。");}
    if(r.DbMode is not ("new" or "existing"))throw new Exception("数据库模式无效。");
    if(machine)foreach(var drive in new[]{program,data}.Concat(paths).Select(Path.GetPathRoot).Distinct())if(new DriveInfo(drive!).AvailableFreeSpace<3L*1024*1024*1024)throw new Exception("安装与数据磁盘至少需要 3 GB 可用空间。");
}
static string Connection(Request r)=>new NpgsqlConnectionStringBuilder{Host=r.DbHost,Port=r.DbPort,Database=r.DbName,Username=r.DbUser,Password=r.DbPassword}.ConnectionString;
static async Task CheckDatabase(Request r,bool create){var b=new NpgsqlConnectionStringBuilder(Connection(r)){Database="postgres"};await using var c=new NpgsqlConnection(b.ConnectionString);await c.OpenAsync();await using var cmd=new NpgsqlCommand("SELECT EXISTS(SELECT 1 FROM pg_database WHERE datname=@name)",c);cmd.Parameters.AddWithValue("name",r.DbName);if(!(bool)(await cmd.ExecuteScalarAsync())!){if(!create)return;await using var make=new NpgsqlCommand("CREATE DATABASE \""+r.DbName+"\"",c);await make.ExecuteNonQueryAsync();}else{await using var target=new NpgsqlConnection(Connection(r));await target.OpenAsync();await using var tables=new NpgsqlCommand("SELECT count(*) FROM information_schema.tables WHERE table_schema NOT IN ('pg_catalog','information_schema')",target);if(Convert.ToInt64(await tables.ExecuteScalarAsync())>0)throw new Exception("所选数据库已有表，禁止首次安装覆盖。请选择空数据库或新数据库名。");}}
static void PortFree(int port){if(IPGlobalProperties.GetIPGlobalProperties().GetActiveTcpListeners().Any(x=>x.Port==port))throw new Exception("端口 "+port+" 已被占用，请修改。");}
static async Task WaitDatabase(Request r){for(int i=0;i<30;i++){try{var b=new NpgsqlConnectionStringBuilder(Connection(r)){Database="postgres",Timeout=2};await using var c=new NpgsqlConnection(b.ConnectionString);await c.OpenAsync();return;}catch{await Task.Delay(1000);}}throw new Exception("PostgreSQL 服务启动超时。");}
static async Task WaitServiceStopped(string name){for(int i=0;i<30;i++){var output=await Run("sc.exe",["queryex",name],allowFailure:true);if(output.Contains("STOPPED")||output.Contains("1060"))return;await Task.Delay(1000);}throw new Exception("无法停止旧版服务，请检查服务状态。");}
static async Task Secure(string path,bool services){var args=new List<string>{path,"/inheritance:r","/grant:r","*S-1-5-18:(OI)(CI)F","*S-1-5-32-544:(OI)(CI)F","*"+WindowsIdentity.GetCurrent().User!.Value+":(OI)(CI)F"};if(services)args.AddRange(["*S-1-5-19:(OI)(CI)M","*S-1-5-20:(OI)(CI)M"]);await Run("icacls.exe",args);}
static async Task Pg(string bin,string connection,string tool,IEnumerable<string> args){var b=new NpgsqlConnectionStringBuilder(connection);await Run(Path.Combine(bin,tool+".exe"),new[]{"-h",b.Host!,"-p",b.Port.ToString(),"-U",b.Username!,"-d",b.Database!}.Concat(args),new Dictionary<string,string>{{"PGPASSWORD",b.Password!}});}
static async Task<string> Run(string exe,IEnumerable<string> args,Dictionary<string,string>? env=null,bool allowFailure=false){var p=new ProcessStartInfo(exe){UseShellExecute=false,CreateNoWindow=true,RedirectStandardError=true,RedirectStandardOutput=true};foreach(var a in args)p.ArgumentList.Add(a);if(env!=null)foreach(var pair in env)p.Environment[pair.Key]=pair.Value;using var child=Process.Start(p)??throw new Exception("无法启动 "+Path.GetFileName(exe));var stdout=child.StandardOutput.ReadToEndAsync();var stderr=child.StandardError.ReadToEndAsync();using var timeout=new CancellationTokenSource(TimeSpan.FromMinutes(30));try{await child.WaitForExitAsync(timeout.Token);}catch{child.Kill(true);throw;}var output=await stdout;var error=await stderr;if(child.ExitCode!=0&&!allowFailure)throw new Exception(Path.GetFileName(exe)+" 失败："+output+error);return output+error;}
static void CopyDirectory(string source,string target){Directory.CreateDirectory(target);if(!Directory.Exists(source))return;foreach(var file in Directory.EnumerateFiles(source,"*",SearchOption.AllDirectories)){var output=Path.Combine(target,Path.GetRelativePath(source,file));Directory.CreateDirectory(Path.GetDirectoryName(output)!);File.Copy(file,output,true);}}
class Request{public string ProgramDir{get;set;}="";public string DataDir{get;set;}="";public int Port{get;set;}=8080;public string SopDir{get;set;}="";public string AttachmentsDir{get;set;}="";public string LogsDir{get;set;}="";public string BackupsDir{get;set;}="";public string DbMode{get;set;}="new";public string DbHost{get;set;}="127.0.0.1";public int DbPort{get;set;}=55433;public string DbName{get;set;}="factory_mes";public string DbUser{get;set;}="";public string DbPassword{get;set;}="";public string AdminPassword{get;set;}="";public bool Firewall{get;set;}=true;}
