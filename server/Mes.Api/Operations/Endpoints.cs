using System.Security.Claims;
using Mes.Api.Data;
using Mes.Api.Security;
using Microsoft.EntityFrameworkCore;
namespace Mes.Api.Operations;
public record RestoreInput(string Ticket,string Confirmation);
public static class Endpoints
{
    public static void MapOperations(this WebApplication app)
    {
        app.MapGet("/api/backups",async(MesDb db)=>await db.Set<BackupJob>().OrderByDescending(x=>x.At).Take(100).ToListAsync()).RequireAuthorization("Admin");
        app.MapPost("/api/backups",async(ClaimsPrincipal p,MesDb db)=>{var j=new BackupJob{UserId=p.UserId()};db.Add(j);await db.SaveChangesAsync();return Results.Accepted("/api/backups",j);}).RequireAuthorization("Admin");
        app.MapGet("/api/backups/{id:guid}/download",async(Guid id,MesDb db,BackupService service)=>{var b=await db.Set<BackupJob>().FindAsync(id);Guard.Check(b?.Status=="Completed"&&b.FileName!=null,"备份文件不可下载");return Results.File(Path.Combine(service.BackupRoot,Path.GetFileName(b!.FileName!)),"application/zip","mes-backup-"+id+".zip");}).RequireAuthorization("Admin");
        app.MapPost("/api/backups/{id:guid}/preview",async(Guid id,ClaimsPrincipal p,MesDb db,BackupService service,MaintenanceState state)=>{
            var b=await db.Set<BackupJob>().FindAsync(id);Guard.Check(b?.Status=="Completed","备份不可恢复");var v=await service.Validate(b!.FileName!);service.SafeDeleteStage(v.Stage);var ticket=Guard.Token();var hash=await BackupService.HashFile(Path.Combine(service.BackupRoot,b.FileName!));lock(state.Tickets)state.Tickets[ticket]=(p.UserId(),id,DateTimeOffset.UtcNow.AddMinutes(10),hash);
            return Results.Ok(new{ticket,manifest=v.Manifest,scope="数据库全部业务记录、用户/权限、SOP与附件；使用目标机连接配置。恢复前自动备份当前状态，恢复期间禁止写入，完成后所有会话退出。",confirmation="RESTORE "+id});
        }).RequireAuthorization("Admin");
        app.MapPost("/api/backups/{id:guid}/restore",async(Guid id,RestoreInput input,ClaimsPrincipal p,MesDb db,BackupService service,MaintenanceState state)=>{
            (Guid User,Guid Backup,DateTimeOffset Expiry,string Hash) t;lock(state.Tickets){Guard.Check(state.Tickets.TryGetValue(input.Ticket,out t),"恢复预检已失效");}
            Guard.Check(t.User==p.UserId()&&t.Backup==id&&t.Expiry>DateTimeOffset.UtcNow&&input.Confirmation=="RESTORE "+id,"必须完成预检并输入准确的恢复确认文字");var b=await db.Set<BackupJob>().FindAsync(id);Guard.Check(t.Hash==await BackupService.HashFile(Path.Combine(service.BackupRoot,b!.FileName!)),"备份已变化，请重新预检");lock(state.Tickets)state.Tickets.Remove(input.Ticket);state.Active=true;state.Status="Queued";state.RestoreId=id;return Results.Accepted("/api/maintenance",new{status="Queued"});
        }).RequireAuthorization("Admin");
        app.MapGet("/api/maintenance",(MaintenanceState state)=>new{state.Active,state.Status,state.Error}).RequireAuthorization("Admin");
    }
}
