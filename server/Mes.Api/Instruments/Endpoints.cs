using System.Security.Claims;
using System.Text.Json;
using Mes.Api.Data;
using Mes.Api.Security;
using Mes.Api.Production;
using Microsoft.EntityFrameworkCore;
using static Mes.Api.MasterData.Endpoints;
namespace Mes.Api.Instruments;

public static class Endpoints
{
    public static void MapInstruments(this WebApplication app)
    {
        app.MapGet("/api/admin/protocols",()=>new[]{new{id="generic-v1",name="MES 通用 HTTP v1",enabled=true,status="可通过模拟器验证"},new{id="vendor-pending",name="厂家 MES 协议 /HQAPI/MES/",enabled=false,status="缺少厂家文档；未注册子路径，不代表已对接"}}).RequireAuthorization("Office");
        app.MapGet("/api/admin/devices",async(MesDb db)=>await db.Set<Device>().Select(d=>new{d.Id,d.Code,d.Name,d.StationId,d.Protocol,d.ProgramVersion,d.RequiredItems,d.Active,d.LastSeen}).ToListAsync()).RequireAuthorization("Office");
        app.MapPost("/api/admin/devices",async(JsonElement j,MesDb db)=>{
            var s=await db.Set<Station>().FindAsync(Id(j,"stationId"));Guard.Check(s!=null,"工位不存在");var p=await db.Set<ProcessStep>().FindAsync(s!.ProcessStepId);Guard.Check(p!.Sequence==3,"设备必须绑定仪器测试工位");
            Guard.Check(Str(j,"protocol")=="generic-v1","厂家协议文档未提供，不能启用");var spec=j.GetProperty("items").Deserialize<Specification[]>(new JsonSerializerOptions(JsonSerializerDefaults.Web));
            Guard.Check(spec?.Length>0&&spec.Length<=100&&spec.Select(x=>x.Name).Distinct().Count()==spec.Length&&spec.All(x=>!string.IsNullOrWhiteSpace(x.Name)&&!string.IsNullOrWhiteSpace(x.Unit)&&x.Lower<=x.Upper),"测试标准项目无效");
            var key=Guard.Token();var d=new Device{Code=Str(j,"code"),Name=Str(j,"name"),StationId=s.Id,Protocol="generic-v1",ProgramVersion=Str(j,"programVersion"),RequiredItems=JsonSerializer.Serialize(spec,new JsonSerializerOptions(JsonSerializerDefaults.Web)),KeyHash=Guard.Hash(key)};db.Add(d);await db.SaveChangesAsync();return Results.Ok(new{d.Id,key,note="凭据只显示一次，请保存到仪器配置；不会再次读取明文"});
        }).RequireAuthorization("Admin");
        app.MapPost("/api/admin/devices/{id:guid}/rotate",async(Guid id,MesDb db)=>{var d=await db.Set<Device>().FindAsync(id);Guard.Check(d!=null,"设备不存在");var key=Guard.Token();d!.KeyHash=Guard.Hash(key);await db.SaveChangesAsync();return Results.Ok(new{key});}).RequireAuthorization("Admin");
        app.MapPost("/api/admin/devices/{id:guid}/disable",async(Guid id,MesDb db)=>{var d=await db.Set<Device>().FindAsync(id);Guard.Check(d!=null,"设备不存在");d!.Active=false;await db.SaveChangesAsync();return Results.Ok();}).RequireAuthorization("Admin");
        app.MapGet("/api/admin/receipts",async(MesDb db)=>await db.Set<Receipt>().OrderByDescending(x=>x.At).Take(200).ToListAsync()).RequireAuthorization("Office");
        app.MapPost("/api/instrument/v1/check",async(HttpRequest r,InstrumentService service)=>await service.ReceiveRaw(r,"check"));
        app.MapPost("/api/instrument/v1/results",async(HttpRequest r,InstrumentService service)=>await service.ReceiveRaw(r,"results"));
        app.MapPost("/api/instrument/v1/heartbeat",async(HttpRequest r,InstrumentService service,MesDb db)=>{await service.Authenticate(r);await db.SaveChangesAsync();return Results.Ok(new{status="ok"});});
        app.MapGet("/api/instrument/v1/work-orders",async(HttpRequest r,InstrumentService service,MesDb db)=>{var d=await service.Authenticate(r);var s=await db.Set<Station>().FindAsync(d.StationId);var orders=await db.Set<WorkOrder>().Where(o=>o.LineId==s!.LineId&&o.Status=="Open").ToListAsync();var ids=orders.Select(o=>o.Id).ToArray();return new{deviceCode=d.Code,stationCode=s!.Code,orders,batches=await db.Set<Batch>().Where(b=>ids.Contains(b.WorkOrderId)).ToListAsync()};});
        app.MapPost("/api/instrument/v1/user",async(HttpRequest r,JsonElement j,InstrumentService service,MesDb db)=>{var d=await service.Authenticate(r);var account=Str(j,"account");var u=await db.Users.SingleOrDefaultAsync(x=>x.Account==account&&x.Active);Guard.Check(u!=null&&await db.Set<StationGrant>().AnyAsync(g=>g.UserId==u.Id&&g.StationId==d.StationId),"该用户未获得设备工位授权","forbidden",403);return new{u!.Id,u.Account,u.Name,stationId=d.StationId};});
        app.MapGet("/api/tests",async(Guid? stationId,Guid? batchId,ClaimsPrincipal p,MesDb db,ProductionService prod)=>{
            if(!await Permissions.Has(p,"Office",db)){Guard.Check(stationId!=null&&batchId!=null,"必须指定工位与批次");await prod.Station(stationId!.Value,p);}
            return await (from t in db.Set<TestTask>() join r in db.Set<TestResult>() on t.Id equals r.TestTaskId join u in db.Set<Unit>() on t.UnitId equals u.Id where (!stationId.HasValue||t.StationId==stationId)&&(!batchId.HasValue||t.BatchId==batchId) orderby r.ReceivedAt descending select new{mainCode=u.MainCode,task=t,result=r,items=db.Set<TestItem>().Where(x=>x.TestResultId==r.Id).ToList()}).Take(100).ToListAsync();
        }).RequireAuthorization("ReadProduction");
    }
}
