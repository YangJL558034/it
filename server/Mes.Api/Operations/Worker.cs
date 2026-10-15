using Mes.Api.Data;
using Microsoft.EntityFrameworkCore;
namespace Mes.Api.Operations;
public class Worker(IServiceScopeFactory scopes,IConfiguration config,BackupService backups,MaintenanceState state,ILogger<Worker> logger):BackgroundService
{
    readonly DateTimeOffset started=DateTimeOffset.UtcNow;
    protected override async Task ExecuteAsync(CancellationToken stop)
    {
        if(!state.Active){using var recoveryScope=scopes.CreateScope();var recovery=recoveryScope.ServiceProvider.GetRequiredService<MesDb>();await recovery.Set<ExportJob>().Where(x=>x.Status=="Running").ExecuteUpdateAsync(x=>x.SetProperty(j=>j.Status,"Queued").SetProperty(j=>j.Error,"服务重启后重新执行"),stop);await recovery.Set<BackupJob>().Where(x=>x.Status=="Running").ExecuteUpdateAsync(x=>x.SetProperty(j=>j.Status,"Failed").SetProperty(j=>j.Error,"服务中断，请重新备份"),stop);}
        while(!stop.IsCancellationRequested){try{
            if(state.RestoreId is Guid restore){await backups.Restore(restore);continue;}
            if(!state.Active){using var scope=scopes.CreateScope();var db=scope.ServiceProvider.GetRequiredService<MesDb>();
                var export=await db.Set<ExportJob>().Where(x=>x.Status=="Queued").OrderBy(x=>x.At).FirstOrDefaultAsync(stop);
                if(export!=null){export.Status="Running";await db.SaveChangesAsync(stop);try{await using var snapshot=await db.Database.BeginTransactionAsync(System.Data.IsolationLevel.RepeatableRead,stop);await Reporting.Endpoints.ExecuteExport(db,export,config);await snapshot.CommitAsync(stop);}catch(Exception e){db.ChangeTracker.Clear();var job=await db.Set<ExportJob>().FindAsync(export.Id);job!.Status="Failed";job.Error=e.Message;await db.SaveChangesAsync(stop);}continue;}
                var settings=await db.Set<Setting>().ToDictionaryAsync(x=>x.Key,x=>x.Value,stop);var interval=settings.TryGetValue("backupIntervalHours",out var h)?int.Parse(h):config.GetValue("Backup:IntervalHours",24);var retention=settings.TryGetValue("backupRetentionDays",out var d)?int.Parse(d):config.GetValue("Backup:RetentionDays",30);
                var last=await db.Set<BackupJob>().Where(x=>x.Status=="Completed").MaxAsync(x=>(DateTimeOffset?)x.At,stop)??started;
                var backup=await db.Set<BackupJob>().Where(x=>x.Status=="Queued").OrderBy(x=>x.At).FirstOrDefaultAsync(stop);
                if(backup==null&&DateTimeOffset.UtcNow-last>TimeSpan.FromHours(interval)){backup=new BackupJob();db.Add(backup);await db.SaveChangesAsync(stop);}
                if(backup!=null){backup.Status="Running";await db.SaveChangesAsync(stop);await using var tx=await db.Database.BeginTransactionAsync(stop);await db.Database.ExecuteSqlRawAsync("SELECT pg_advisory_xact_lock(74215001)",stop);try{backup.FileName=await backups.Create(backup.Id);backup.Status="Completed";}catch(Exception e){backup.Status="Failed";backup.Error=e.Message;logger.LogError(e,"Backup failed");}await db.SaveChangesAsync(stop);await tx.CommitAsync(stop);}
                var expired=await db.Set<BackupJob>().Where(x=>x.Status=="Completed"&&x.At<DateTimeOffset.UtcNow.AddDays(-retention)).ToListAsync(stop);
                foreach(var job in expired){if(job.FileName!=null)File.Delete(Path.Combine(backups.BackupRoot,Path.GetFileName(job.FileName)));job.Status="Expired";}if(expired.Count>0)await db.SaveChangesAsync(stop);
            }
        }catch(Exception e){logger.LogError(e,"Background operation failed");}try{await Task.Delay(2000,stop);}catch(OperationCanceledException){break;}}
    }
}
