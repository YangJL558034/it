using System.Diagnostics;
using System.IO.Compression;
using System.Security.Cryptography;
using System.Text.Json;
using Mes.Api.Data;
using Mes.Api.Security;
using Microsoft.EntityFrameworkCore;
using Npgsql;
namespace Mes.Api.Operations;

public class MaintenanceState { public volatile bool Active; public string Status {get;set;}="Idle"; public string? Error {get;set;} public Guid? RestoreId {get;set;} public Dictionary<string,(Guid User,Guid Backup,DateTimeOffset Expiry,string Hash)> Tickets {get;}=[]; }
public record ArchiveManifest(string Version,DateTimeOffset CreatedAt,string DatabaseSha256,Dictionary<string,string> Files,string[] Migrations);
public class BackupService(IConfiguration c,IServiceScopeFactory scopes,MaintenanceState state)
{
    public string Root=>Path.GetFullPath(c["Storage:Root"]!);
    public string BackupRoot{get{var d=StoragePaths.Get(c,"Backups");Directory.CreateDirectory(d);return d;}}
    string Tool(string name)=>Path.Combine(c["Backup:PgBin"]??"",name+(OperatingSystem.IsWindows()?".exe":""));
    async Task Pg(string tool,params string[] args)
    {
        var cs=new NpgsqlConnectionStringBuilder(c.GetConnectionString("Mes"));var p=new ProcessStartInfo(Tool(tool)){UseShellExecute=false,CreateNoWindow=true,RedirectStandardError=true,RedirectStandardOutput=true};
        foreach(var a in new[]{"-h",cs.Host!,"-p",cs.Port.ToString(),"-U",cs.Username!}.Concat(args))p.ArgumentList.Add(a);p.Environment["PGPASSWORD"]=cs.Password;p.Environment["PGCONNECT_TIMEOUT"]="15";
        using var process=Process.Start(p)??throw new IOException("无法启动 PostgreSQL 工具");var error=process.StandardError.ReadToEndAsync();var output=process.StandardOutput.ReadToEndAsync();using var timeout=new CancellationTokenSource(TimeSpan.FromMinutes(30));try{await process.WaitForExitAsync(timeout.Token);}catch{process.Kill(true);throw;}await output;
        if(process.ExitCode!=0)throw new IOException(tool+" 失败："+await error);
    }
    public async Task<string> Create(Guid id)
    {
        var stage=Path.Combine(BackupRoot,"stage-"+id);Directory.CreateDirectory(stage);var dump=Path.Combine(stage,"database.dump");var cs=new NpgsqlConnectionStringBuilder(c.GetConnectionString("Mes"));
        await Pg("pg_dump","-d",cs.Database!,"--format=custom","--no-owner","--no-acl","-f",dump);
        var hashes=new Dictionary<string,string>();using var scope=scopes.CreateScope();var db=scope.ServiceProvider.GetRequiredService<MesDb>();
        foreach(var f in await db.Set<SopFile>().ToListAsync()){var source=Path.Combine(StoragePaths.Get(c,"Sop"),f.StorageName);Guard.Check(File.Exists(source),"备份失败，SOP 文件缺失："+f.Name);var hash=await HashFile(source);Guard.Check(hash==f.Sha256,"备份失败，SOP 文件摘要不匹配："+f.Name);var relative="sop/"+f.StorageName;var target=Path.Combine(stage,relative);Directory.CreateDirectory(Path.GetDirectoryName(target)!);File.Copy(source,target,true);hashes.Add(relative,hash);}
        var attachments=StoragePaths.Get(c,"Attachments");if(Directory.Exists(attachments))foreach(var source in Directory.EnumerateFiles(attachments,"*",SearchOption.AllDirectories)){var relative="attachments/"+Path.GetRelativePath(attachments,source).Replace('\\','/');var target=Path.Combine(stage,relative);Directory.CreateDirectory(Path.GetDirectoryName(target)!);File.Copy(source,target,true);hashes.Add(relative,await HashFile(source));}
        var config=Path.Combine(stage,"configuration.json");await File.WriteAllTextAsync(config,JsonSerializer.Serialize(new{version="1.0.1",settings=await db.Set<Setting>().Select(s=>new{s.Key,s.Value}).ToListAsync(),note="连接密码与设备凭据不以明文写入此配置文件；恢复使用目标机受保护配置。"}));hashes.Add("configuration.json",await HashFile(config));
        var manifest=new ArchiveManifest("1.0",DateTimeOffset.UtcNow,await HashFile(dump),hashes,(await db.Database.GetAppliedMigrationsAsync()).ToArray());await File.WriteAllTextAsync(Path.Combine(stage,"manifest.json"),JsonSerializer.Serialize(manifest));
        var file=id+".zip";var targetZip=Path.Combine(BackupRoot,file);ZipFile.CreateFromDirectory(stage,targetZip+".tmp",CompressionLevel.Fastest,false);File.Move(targetZip+".tmp",targetZip,true);SafeDeleteStage(stage);await Log("backup",id.ToString(),"Completed");return file;
    }
    public static async Task<string> HashFile(string path){await using var s=File.OpenRead(path);return Convert.ToHexString(await SHA256.HashDataAsync(s));}
    public async Task<(ArchiveManifest Manifest,string Stage)> Validate(string file)
    {
        Guard.Check(Path.GetFileName(file)==file,"备份路径无效");var stage=Path.Combine(BackupRoot,"verify-"+Guid.NewGuid().ToString("N"));Directory.CreateDirectory(stage);
        try{using(var zip=ZipFile.OpenRead(Path.Combine(BackupRoot,file))){Guard.Check(zip.Entries.Sum(e=>e.Length)<50L*1024*1024*1024,"备份展开内容超过 50 GB");foreach(var e in zip.Entries){var target=Path.GetFullPath(Path.Combine(stage,e.FullName));Guard.Check(target.StartsWith(stage+Path.DirectorySeparatorChar,StringComparison.OrdinalIgnoreCase),"备份包含非法路径");if(e.Name.Length==0){Directory.CreateDirectory(target);continue;}Directory.CreateDirectory(Path.GetDirectoryName(target)!);e.ExtractToFile(target);}}
            var manifest=JsonSerializer.Deserialize<ArchiveManifest>(await File.ReadAllTextAsync(Path.Combine(stage,"manifest.json")))!;Guard.Check(manifest.Version=="1.0","备份格式不兼容");Guard.Check(await HashFile(Path.Combine(stage,"database.dump"))==manifest.DatabaseSha256,"数据库备份摘要不符");
            foreach(var (relative,hash) in manifest.Files){var p=Path.GetFullPath(Path.Combine(stage,relative));Guard.Check(p.StartsWith(stage+Path.DirectorySeparatorChar,StringComparison.OrdinalIgnoreCase),"清单含非法路径");Guard.Check(File.Exists(p)&&await HashFile(p)==hash,"附件缺失或摘要不符："+relative);}
            using var scope=scopes.CreateScope();var db=scope.ServiceProvider.GetRequiredService<MesDb>();var known=db.Database.GetMigrations().ToHashSet();Guard.Check(manifest.Migrations.All(known.Contains),"备份数据库版本高于当前程序，禁止恢复");return(manifest,stage);
        }catch{SafeDeleteStage(stage);throw;}
    }
    public async Task Restore(Guid id)
    {
        var maintenanceFile=Path.Combine(Root,"restore-maintenance.json");await File.WriteAllTextAsync(maintenanceFile,JsonSerializer.Serialize(new{backupId=id,at=DateTimeOffset.UtcNow}));
        state.Status="Validating";state.Error=null;string? pre=null;string? sourceStage=null;
        await using var connection=new NpgsqlConnection(c.GetConnectionString("Mes"));await connection.OpenAsync();await using var l=new NpgsqlCommand("SELECT pg_advisory_lock(74215001)",connection);await l.ExecuteNonQueryAsync();
        try{
            using var scope=scopes.CreateScope();var db=scope.ServiceProvider.GetRequiredService<MesDb>();var b=await db.Set<BackupJob>().FindAsync(id);Guard.Check(b?.Status=="Completed","备份不可恢复");var checkedBackup=await Validate(b!.FileName!);sourceStage=checkedBackup.Stage;
            state.Status="BackingUpCurrent";var preId=Guid.NewGuid();pre=await Create(preId);await File.WriteAllTextAsync(Path.Combine(BackupRoot,"last-pre-restore.json"),JsonSerializer.Serialize(new{preId,file=pre,at=DateTimeOffset.UtcNow}));
            state.Status="Restoring";await Apply(sourceStage);state.Status="Verifying";await VerifyDatabaseFiles();
            using var afterScope=scopes.CreateScope();var after=afterScope.ServiceProvider.GetRequiredService<MesDb>();await after.Set<Session>().ExecuteDeleteAsync();foreach(var job in await after.Set<BackupJob>().Where(x=>x.Status=="Running").ToListAsync()){var f=job.Id+".zip";if(File.Exists(Path.Combine(BackupRoot,f))){job.FileName=f;job.Status="Completed";}else{job.Status="Failed";job.Error="恢复后发现中断的任务";}}after.Add(new BackupJob{Id=Guid.Parse(Path.GetFileNameWithoutExtension(pre!)),FileName=pre,Status="Completed"});after.Add(new Audit{Action="Restore",Detail="backup="+id+";pre="+pre,Outcome="Completed"});await after.SaveChangesAsync();
            state.Status="Completed";await Log("restore",id.ToString(),"Completed; pre="+pre);
        }catch(Exception e){state.Error=e.Message;state.Status="Failed";if(pre!=null){try{var rollback=await Validate(pre);try{await Apply(rollback.Stage);await VerifyDatabaseFiles();state.Status="RolledBack";}finally{SafeDeleteStage(rollback.Stage);}}catch(Exception recovery){state.Status="RecoveryRequired";state.Error+="; rollback: "+recovery.Message;}}await Log("restore",id.ToString(),state.Status+": "+state.Error);
        }finally{if(sourceStage!=null)SafeDeleteStage(sourceStage);if(state.Status!="RecoveryRequired"){File.Delete(maintenanceFile);state.Active=false;}state.RestoreId=null;await using var unlock=new NpgsqlCommand("SELECT pg_advisory_unlock(74215001)",connection);await unlock.ExecuteNonQueryAsync();}
    }
    async Task Apply(string stage)
    {
        var cs=new NpgsqlConnectionStringBuilder(c.GetConnectionString("Mes"));await Pg("pg_restore","-d",cs.Database!,"--clean","--if-exists","--no-owner","--no-acl","--exit-on-error","--single-transaction",Path.Combine(stage,"database.dump"));
        foreach(var folder in new[]{"sop","attachments"}){var live=StoragePaths.Get(c,folder=="sop"?"Sop":"Attachments");var preserved=live+"-before-"+Guid.NewGuid().ToString("N");var source=Path.Combine(stage,folder);var prepared=live+"-restore-"+Guid.NewGuid().ToString("N");Directory.CreateDirectory(prepared);if(Directory.Exists(source))foreach(var sourceFile in Directory.EnumerateFiles(source,"*",SearchOption.AllDirectories)){var targetFile=Path.Combine(prepared,Path.GetRelativePath(source,sourceFile));Directory.CreateDirectory(Path.GetDirectoryName(targetFile)!);File.Copy(sourceFile,targetFile);}if(Directory.Exists(live))Directory.Move(live,preserved);Directory.Move(prepared,live);}
        using var scope=scopes.CreateScope();await scope.ServiceProvider.GetRequiredService<MesDb>().Database.MigrateAsync();
    }
    async Task VerifyDatabaseFiles(){using var scope=scopes.CreateScope();var db=scope.ServiceProvider.GetRequiredService<MesDb>();await db.Database.ExecuteSqlRawAsync("SELECT 1");foreach(var f in await db.Set<SopFile>().ToListAsync())Guard.Check(await HashFile(Path.Combine(StoragePaths.Get(c,"Sop"),f.StorageName))==f.Sha256,"恢复后 SOP 与数据库不一致");}
    public void SafeDeleteStage(string path){var full=Path.GetFullPath(path);Guard.Check(full.StartsWith(BackupRoot+Path.DirectorySeparatorChar,StringComparison.OrdinalIgnoreCase)&&(Path.GetFileName(full).StartsWith("stage-")||Path.GetFileName(full).StartsWith("verify-")),"清理路径不属于备份临时目录");if(Directory.Exists(full))Directory.Delete(full,true);}
    async Task Log(string action,string id,string outcome){var dir=StoragePaths.Get(c,"Logs");Directory.CreateDirectory(dir);await File.AppendAllTextAsync(Path.Combine(dir,"backup-restore.jsonl"),JsonSerializer.Serialize(new{at=DateTimeOffset.UtcNow,action,id,outcome})+Environment.NewLine);}
}
