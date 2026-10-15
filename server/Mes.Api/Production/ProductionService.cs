using System.Security.Claims;
using System.Text.Json;
using System.Text.RegularExpressions;
using Mes.Api.Data;
using Mes.Api.Security;
using Microsoft.EntityFrameworkCore;
namespace Mes.Api.Production;

public class ProductionService(MesDb db)
{
    public async Task<Station> Station(Guid id, ClaimsPrincipal user)
    {
        var s=await db.Set<Station>().FindAsync(id); Guard.Check(s!=null,"工位不存在","not_found",404);
        Guard.Check(user.IsInRole("Admin")||await db.Set<StationGrant>().AnyAsync(x=>x.UserId==user.UserId()&&x.StationId==id),"未授权操作此工位","forbidden",403);return s!;
    }
    public async Task<WorkOrder> Scope(Station station,Guid batchId)
    {
        var b=await db.Set<Batch>().FindAsync(batchId);Guard.Check(b!=null,"生产批次不存在");var o=await db.Set<WorkOrder>().FindAsync(b!.WorkOrderId);
        Guard.Check(o!.LineId==station.LineId,"工单不属于当前产线","cross_line",409);Guard.Check(o.Status=="Open","工单已关闭","order_closed",409);return o;
    }
    public async Task<BarcodeRule> Barcode(string kind,string code)
    {
        var r=await db.Set<BarcodeRule>().Where(x=>x.Kind==kind).OrderByDescending(x=>x.Version).FirstAsync();
        Guard.Check(code.Length==r.Length,$"{kind} 条码要求 {r.Length} 位，实际 {code.Length} 位","barcode_length");
        Guard.Check(r.Prefixes.Length==0||r.Prefixes.Any(code.StartsWith),$"{kind} 条码前缀须为 {string.Join(" / ",r.Prefixes)}","barcode_prefix");
        Guard.Check(Regex.IsMatch(code,r.Charset=="numeric"?"^[0-9]+$":r.Charset=="alnum"?"^[A-Za-z0-9]+$":"^[!-~]+$"),"条码字符类型不符合已发布规则","barcode_charset");return r;
    }
    public async Task FreeCode(string code)=>Guard.Check(!await db.Set<Unit>().AnyAsync(x=>x.MainCode==code||x.CellCode==code||x.SnCode==code||x.PackCode==code),"条码已被使用，禁止重复绑定","duplicate_code",409);
    public async Task<bool> LatestPass(Guid unitId)
    {
        if(await db.Set<TestTask>().AnyAsync(x=>x.UnitId==unitId&&x.Status=="Pending"))return false;
        var result=await (from t in db.Set<TestTask>() join r in db.Set<TestResult>() on t.Id equals r.TestTaskId where t.UnitId==unitId && t.Status=="Completed" orderby t.Sequence descending select r.Verdict).FirstOrDefaultAsync();return result=="PASS";
    }
    public async Task Prerequisite(Unit u,int sequence)
    {
        if(sequence>=2)Guard.Check(await db.Set<Passage>().AnyAsync(x=>x.UnitId==u.Id),"主码未过站","previous_step",409);
        if(sequence>=3)Guard.Check(u.CellCode!=null,"电芯未绑定，禁止跳站","previous_step",409);
        if(sequence>=4)Guard.Check(await LatestPass(u.Id),"最新有效测试未 PASS 或存在待完成测试，禁止放行","test_not_pass",409);
        if(sequence>=5)Guard.Check(u.SnCode!=null,"SN 未绑定，禁止包装","previous_step",409);
    }
    public async Task AddPassage(Unit u,Station s,Guid? userId,Guid? ruleId=null,Guid? sopSnapshot=null,bool useSnapshot=false)
    {
        if(await db.Set<Passage>().AnyAsync(x=>x.UnitId==u.Id&&x.ProcessStepId==s.ProcessStepId))return;
        var sop=useSnapshot?sopSnapshot:await db.Set<SopVersion>().Where(x=>x.ProcessStepId==s.ProcessStepId&&x.Status=="Published").Select(x=>(Guid?)x.Id).FirstOrDefaultAsync();
        db.Add(new Passage{UnitId=u.Id,StationId=s.Id,ProcessStepId=s.ProcessStepId,UserId=userId,SopVersionId=sop,BarcodeRuleId=ruleId});
    }
    public async Task<object> Trace(Unit u)=>new{unit=u, batch=await db.Set<Batch>().FindAsync(u.BatchId),passages=await db.Set<Passage>().Where(x=>x.UnitId==u.Id).OrderBy(x=>x.At).ToListAsync(), tests=await (from t in db.Set<TestTask>() join r in db.Set<TestResult>() on t.Id equals r.TestTaskId where t.UnitId==u.Id orderby t.Sequence select new{task=t,result=r,items=db.Set<TestItem>().Where(i=>i.TestResultId==r.Id).ToList()}).ToListAsync()};
}
public record ScanInput(Guid RequestId,Guid StationId,Guid BatchId,string Code,Guid? SopVersionId=null);
public static class Endpoints
{
    public static void MapProduction(this WebApplication app)
    {
        app.MapGet("/api/station/options",async(ClaimsPrincipal p,MesDb db)=>{
            var uid=p.UserId();var stations=await db.Set<Station>().Where(s=>p.IsInRole("Admin")||db.Set<StationGrant>().Any(g=>g.UserId==uid&&g.StationId==s.Id)).ToListAsync();var lines=stations.Select(s=>s.LineId).ToArray();
            var orders=await db.Set<WorkOrder>().Where(o=>lines.Contains(o.LineId)&&o.Status=="Open").ToListAsync();var ids=orders.Select(o=>o.Id).ToArray();
            return new{stations,orders,batches=await db.Set<Batch>().Where(b=>ids.Contains(b.WorkOrderId)).ToListAsync(),processes=await db.Set<ProcessStep>().OrderBy(p=>p.Sequence).ToListAsync()};
        }).RequireAuthorization("Operate");
        app.MapPost("/api/station/scan",async(ScanInput input,ClaimsPrincipal p,MesDb db,ProductionService prod)=>{
            Guard.Check(input.RequestId!=Guid.Empty&&input.Code.Length is >0 and <=128,"扫码请求编号或条码无效");
            var uid=p.UserId();var s=await prod.Station(input.StationId,p);await prod.Scope(s,input.BatchId);
            var hash=Guard.Hash(JsonSerializer.Serialize(input));var previous=await db.Set<ScanRequest>().SingleOrDefaultAsync(x=>x.UserId==uid&&x.RequestId==input.RequestId);
            if(previous!=null){Guard.Check(previous.Hash==hash,"相同请求编号不能用于不同扫码内容","idempotency_conflict",409);return Results.Content(previous.Response,"application/json");}
            var activeSop=await db.Set<SopVersion>().Where(v=>v.ProcessStepId==s.ProcessStepId&&v.Status=="Published").Select(v=>(Guid?)v.Id).FirstOrDefaultAsync();
            Guard.Check(input.SopVersionId==activeSop,"SOP 版本已变化或尚未加载，请等待最新版 SOP 加载后重新扫码","sop_version",409);
            var step=await db.Set<ProcessStep>().FindAsync(s.ProcessStepId);var n=step!.Sequence;Guard.Check(n!=3,"测试工位由仪器自动上传，不接受人工扫码","test_station",409);
            var ctx=await db.Set<ScanContext>().SingleOrDefaultAsync(x=>x.UserId==uid&&x.StationId==s.Id);
            if(ctx==null){ctx=new ScanContext{UserId=uid,StationId=s.Id,BatchId=input.BatchId};db.Add(ctx);}
            if(ctx.BatchId!=input.BatchId||ctx.Expires<DateTimeOffset.UtcNow){ctx.UnitId=null;ctx.BatchId=input.BatchId;}
            ctx.Expires=DateTimeOffset.UtcNow.AddMinutes(10);
            Unit? u=null;string message;string waiting=n==5?"SN":"主码";
            if(n==1){
                var rule=await prod.Barcode("main",input.Code);u=await db.Set<Unit>().SingleOrDefaultAsync(x=>x.MainCode==input.Code);
                if(u!=null){Guard.Check(u.BatchId==input.BatchId,"主码已属于其他工单或批次","cross_order",409);message="已过站，未重复计数";}
                else {await prod.FreeCode(input.Code);var batch=await db.Set<Batch>().FindAsync(input.BatchId);Guard.Check(await db.Set<Unit>().CountAsync(x=>x.BatchId==input.BatchId)<batch!.Plan,"批次计划数量已完成","plan_complete",409);u=new Unit{BatchId=input.BatchId,MainCode=input.Code};db.Add(u);await prod.AddPassage(u,s,uid,rule.Id);message="主码过站成功";}
            } else if(ctx.UnitId==null) {
                await prod.Barcode(n==5?"sn":"main",input.Code);
                u=await db.Set<Unit>().SingleOrDefaultAsync(x=>n==5?x.SnCode==input.Code:x.MainCode==input.Code);Guard.Check(u!=null,"未找到产品，请先完成主码过站","not_found",404);
                Guard.Check(u!.BatchId==input.BatchId,"产品不属于当前工单/批次","cross_order",409);await prod.Prerequisite(u,n);
                Guard.Check(n==2?u.CellCode==null:n==4?u.SnCode==null:u.PackCode==null,"本工序已经完成，禁止重复绑定","already_completed",409);
                ctx.UnitId=u.Id;waiting=n==2?"电芯码":n==4?"SN":"包装码";message="已识别产品，请继续扫描"+waiting;
            } else {
                u=await db.Set<Unit>().SingleAsync(x=>x.Id==ctx.UnitId);Guard.Check(u.BatchId==input.BatchId,"扫码上下文与批次不一致","cross_order",409);await prod.Prerequisite(u,n);
                Guard.Check(n==2?u.CellCode==null:n==4?u.SnCode==null:u.PackCode==null,"产品已被其他终端绑定，请重新扫描","already_completed",409);
                var kind=n==2?"cell":n==4?"sn":"pack";var rule=await prod.Barcode(kind,input.Code);await prod.FreeCode(input.Code);
                if(n==2)u.CellCode=input.Code;else if(n==4)u.SnCode=input.Code;else u.PackCode=input.Code;
                await prod.AddPassage(u,s,uid,rule.Id);ctx.UnitId=null;message="绑定成功";
            }
            s.LastSeen=DateTimeOffset.UtcNow;await db.SaveChangesAsync();
            var response=JsonSerializer.Serialize(new{message,waiting,unit=u,trace=await prod.Trace(u!),pending=ctx.UnitId!=null},new JsonSerializerOptions(JsonSerializerDefaults.Web));
            db.Add(new ScanRequest{UserId=uid,RequestId=input.RequestId,Hash=hash,Response=response});await db.SaveChangesAsync();return Results.Content(response,"application/json");
        }).RequireAuthorization("Operate");
        app.MapPost("/api/station/reset",async(ScanInput i,ClaimsPrincipal p,MesDb db,ProductionService prod)=>{await prod.Station(i.StationId,p);await db.Set<ScanContext>().Where(x=>x.UserId==p.UserId()&&x.StationId==i.StationId).ExecuteDeleteAsync();return Results.Ok();}).RequireAuthorization("Operate");
        app.MapPost("/api/station/heartbeat",async(ScanInput i,ClaimsPrincipal p,MesDb db,ProductionService prod)=>{var s=await prod.Station(i.StationId,p);s.LastSeen=DateTimeOffset.UtcNow;await db.SaveChangesAsync();return Results.Ok();}).RequireAuthorization("Operate");
        app.MapGet("/api/station/summary",async(Guid stationId,Guid batchId,ClaimsPrincipal p,MesDb db,ProductionService prod)=>{
            var s=await prod.Station(stationId,p);var order=await prod.Scope(s,batchId);var batch=await db.Set<Batch>().FindAsync(batchId);
            var q=from pass in db.Set<Passage>() join u in db.Set<Unit>() on pass.UnitId equals u.Id where pass.StationId==stationId&&u.BatchId==batchId select new{pass,u};var done=await q.CountAsync();
            return new{station=s,order,batch,done,remaining=batch!.Plan-done,recent=await q.OrderByDescending(x=>x.pass.At).Take(30).ToListAsync(),context=await db.Set<ScanContext>().SingleOrDefaultAsync(x=>x.UserId==p.UserId()&&x.StationId==stationId)};
        }).RequireAuthorization("Operate");
        app.MapGet("/api/trace",async(string code,MesDb db,ProductionService prod)=>{var u=await db.Set<Unit>().SingleOrDefaultAsync(x=>x.MainCode==code||x.CellCode==code||x.SnCode==code||x.PackCode==code);Guard.Check(u!=null,"没有找到该条码","not_found",404);return await prod.Trace(u!);}).RequireAuthorization("Office");
    }
}
