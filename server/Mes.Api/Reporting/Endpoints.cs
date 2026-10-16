using System.Security.Claims;
using System.Text;
using System.Text.Json;
using Mes.Api.Data;
using Mes.Api.Security;
using Microsoft.EntityFrameworkCore;
namespace Mes.Api.Reporting;
public record ReportFilter(Guid WorkOrderId,Guid BatchId,string? MainCode=null,string? CellCode=null,string? SnCode=null,string? PackCode=null,string? Verdict=null);
public record ExportInput(string Kind,ReportFilter Filter);
public static class Endpoints
{
    public static async Task Validate(MesDb db,ReportFilter f){Guard.Check(f.WorkOrderId!=Guid.Empty&&f.BatchId!=Guid.Empty,"必须选择具体工单和批次","export_scope");Guard.Check(await db.Set<Batch>().AnyAsync(b=>b.Id==f.BatchId&&b.WorkOrderId==f.WorkOrderId),"工单与批次不匹配","export_scope");Guard.Check(string.IsNullOrEmpty(f.Verdict)||f.Verdict is "PASS" or "FAIL","测试结果筛选无效");}
    public static IQueryable<Unit> Query(MesDb db,ReportFilter f)=>db.Set<Unit>().Where(u=>u.BatchId==f.BatchId&&(f.MainCode==null||u.MainCode.Contains(f.MainCode))&&(f.CellCode==null||(u.CellCode!=null&&u.CellCode.Contains(f.CellCode)))&&(f.SnCode==null||(u.SnCode!=null&&u.SnCode.Contains(f.SnCode)))&&(f.PackCode==null||(u.PackCode!=null&&u.PackCode.Contains(f.PackCode)))&&(f.Verdict==null||(from t in db.Set<TestTask>() join r in db.Set<TestResult>() on t.Id equals r.TestTaskId where t.UnitId==u.Id&&r.Verdict==f.Verdict select r).Any()));
    public static void MapReporting(this WebApplication app)
    {
        app.MapPost("/api/reports/search",async(ReportFilter f,MesDb db)=>{await Validate(db,f);var q=Query(db,f);return new{total=await q.CountAsync(),items=await q.OrderByDescending(u=>u.CreatedAt).Take(200).ToListAsync()};}).RequireAuthorization("Office");
        app.MapPost("/api/exports",async(ExportInput i,ClaimsPrincipal p,MesDb db)=>{await Validate(db,i.Filter);Guard.Check(new[]{"products","passages","tests"}.Contains(i.Kind),"导出类型无效");var job=new ExportJob{UserId=p.UserId(),WorkOrderId=i.Filter.WorkOrderId,BatchId=i.Filter.BatchId,Kind=i.Kind,Filter=JsonSerializer.Serialize(i.Filter)};db.Add(job);await db.SaveChangesAsync();return Results.Accepted("/api/exports",job);}).RequireAuthorization("Office");
        app.MapGet("/api/exports",async(ClaimsPrincipal p,MesDb db)=>await db.Set<ExportJob>().Where(x=>p.IsInRole("Admin")||x.UserId==p.UserId()).OrderByDescending(x=>x.At).Take(100).ToListAsync()).RequireAuthorization("Office");
        app.MapGet("/api/exports/{id:guid}/download",async(Guid id,ClaimsPrincipal p,MesDb db,IConfiguration c)=>{var j=await db.Set<ExportJob>().FindAsync(id);Guard.Check(j!=null&&(p.IsInRole("Admin")||j.UserId==p.UserId()),"无权下载此导出","forbidden",403);Guard.Check(j!.Status=="Completed","导出尚未完成");return Results.File(Path.Combine(Path.GetFullPath(c["Storage:Root"]!),"exports",j.FileName!),"text/csv; charset=utf-8",$"{j.Kind}-{id}.csv");}).RequireAuthorization("Office");
        app.MapGet("/api/tv",async(Guid? lineId,string? workshop,MesDb db)=>{
            var lines=await db.Set<Line>().ToListAsync();var selected=lines.Where(l=>(!lineId.HasValue||l.Id==lineId)&&(string.IsNullOrEmpty(workshop)||l.Workshop==workshop)).Select(l=>l.Id).ToArray();
            var orders=db.Set<WorkOrder>().Where(o=>selected.Contains(o.LineId));var units=from u in db.Set<Unit>() join b in db.Set<Batch>() on u.BatchId equals b.Id join o in orders on b.WorkOrderId equals o.Id select u;
            var tasks=from t in db.Set<TestTask>() join u in units on t.UnitId equals u.Id select t;
            var latest=from t in tasks join r in db.Set<TestResult>() on t.Id equals r.TestTaskId where !db.Set<TestTask>().Any(n=>n.UnitId==t.UnitId&&n.Sequence>t.Sequence) select r;
            var passed=await latest.CountAsync(x=>x.Verdict=="PASS");var tested=await latest.CountAsync();var now=DateTimeOffset.UtcNow;
            return new{lines,at=now,started=await units.CountAsync(),completed=await units.CountAsync(u=>u.PackCode!=null),passed,tested,yield=tested==0?0:Math.Round(100.0*passed/tested,2),
                orders=await orders.OrderByDescending(o=>o.Code).Take(20).Select(o=>new{o.Id,o.Code,o.Plan,o.LineId,completed=(from u in db.Set<Unit>() join b in db.Set<Batch>() on u.BatchId equals b.Id where b.WorkOrderId==o.Id&&u.PackCode!=null select u).Count()}).ToListAsync(),
                processes=await db.Set<ProcessStep>().OrderBy(s=>s.Sequence).Select(s=>new{s.Id,s.Name,s.Sequence,completed=(from pass in db.Set<Passage>() join u in units on pass.UnitId equals u.Id where pass.ProcessStepId==s.Id select pass).Count()}).ToListAsync(),
                stations=await db.Set<Station>().Where(s=>selected.Contains(s.LineId)).Select(s=>new{s.Code,s.Name,online=s.LastSeen>now.AddSeconds(-90)}).ToListAsync(),
                tests=await (from t in tasks join r in db.Set<TestResult>() on t.Id equals r.TestTaskId join u in units on t.UnitId equals u.Id orderby r.ReceivedAt descending select new{u.MainCode,r.Verdict,r.ReceivedAt}).Take(12).ToListAsync(),
                exceptions=await (from r in db.Set<Receipt>() join d in db.Set<Device>() on r.DeviceId equals d.Id join s in db.Set<Station>() on d.StationId equals s.Id where selected.Contains(s.LineId)&&r.Status=="Rejected" orderby r.At descending select new{r.At,r.Reason,device=d.Code}).Take(5).ToListAsync()};
        }).AllowAnonymous();
    }
    public static async Task ExecuteExport(MesDb db,ExportJob job,IConfiguration c)
    {
        var f=JsonSerializer.Deserialize<ReportFilter>(job.Filter)!;await Validate(db,f);var units=Query(db,f);var directory=Path.Combine(Path.GetFullPath(c["Storage:Root"]!),"exports");Directory.CreateDirectory(directory);job.FileName=job.Id+".csv";
        var temp=Path.Combine(directory,job.FileName+".tmp");await using(var writer=new StreamWriter(temp,false,new UTF8Encoding(true))){
            if(job.Kind=="products"){await writer.WriteLineAsync("工单,批次,主码,电芯码,SN,包装码,建立时间");await foreach(var u in units.AsAsyncEnumerable())await Row(writer,f.WorkOrderId,f.BatchId,u.MainCode,u.CellCode,u.SnCode,u.PackCode,u.CreatedAt);}
            else if(job.Kind=="passages"){await writer.WriteLineAsync("工单,批次,主码,工序,工位,操作人,SOP版本ID,条码规则ID,时间");var q=from u in units join p in db.Set<Passage>() on u.Id equals p.UnitId join s in db.Set<Station>() on p.StationId equals s.Id join step in db.Set<ProcessStep>() on p.ProcessStepId equals step.Id select new{u,p,s,step};await foreach(var x in q.AsAsyncEnumerable())await Row(writer,f.WorkOrderId,f.BatchId,x.u.MainCode,x.step.Name,x.s.Code,x.p.UserId,x.p.SopVersionId,x.p.BarcodeRuleId,x.p.At);}
            else{await writer.WriteLineAsync("工单,批次,主码,电芯码,SN,包装码,任务ID,结果编号,设备,测试时间,程序版本,总结果,项目,数值,单位,下限,上限,项目结果,原报文");var q=from u in units join t in db.Set<TestTask>() on u.Id equals t.UnitId join r in db.Set<TestResult>() on t.Id equals r.TestTaskId join d in db.Set<Device>() on t.DeviceId equals d.Id join i in db.Set<TestItem>() on r.Id equals i.TestResultId orderby t.Sequence select new{u,t,r,d,i};await foreach(var x in q.AsAsyncEnumerable())await Row(writer,f.WorkOrderId,f.BatchId,x.u.MainCode,x.u.CellCode,x.u.SnCode,x.u.PackCode,x.t.Id,x.r.RecordId,x.d.Code,x.r.TestedAt,x.t.ProgramVersion,x.r.Verdict,x.i.Name,x.i.Value,x.i.Unit,x.i.Lower,x.i.Upper,x.i.Verdict,x.r.Raw);}
        }File.Move(temp,Path.Combine(directory,job.FileName),true);job.Status="Completed";await db.SaveChangesAsync();
    }
    static Task Row(StreamWriter w,params object?[] values)=>w.WriteLineAsync(string.Join(',',values.Select(v=>{var s=v is DateTimeOffset d?d.ToString("O"):Convert.ToString(v,System.Globalization.CultureInfo.InvariantCulture)??"";if(s.Length>0&&"=+-@\t\r".Contains(s[0]))s="'"+s;return "\""+s.Replace("\"","\"\"")+"\"";})));
}
