using System.Text.Json;
using Mes.Api.Data;
using Mes.Api.Security;
using Mes.Api.Production;
using Microsoft.EntityFrameworkCore;
namespace Mes.Api.Instruments;

public class InstrumentService(MesDb db,ProductionService production,IEnumerable<IInstrumentProtocol> protocols)
{
    public async Task<Device> Authenticate(HttpRequest request,string protocol="generic-v1")
    {
        var key=request.Headers["X-Device-Key"].ToString();Guard.Check(key.Length>=32,"设备身份认证失败","device_unauthorized",401);var hash=Guard.Hash(key);
        var d=await db.Set<Device>().SingleOrDefaultAsync(x=>x.KeyHash==hash&&x.Active);Guard.Check(d!=null,"设备身份认证失败","device_unauthorized",401);
        Guard.Check(d!.Protocol==protocol,"设备协议与端点不匹配","wrong_protocol",403);d.LastSeen=DateTimeOffset.UtcNow;return d;
    }
    public async Task<object> Receive(Device d,string operation,JsonElement json)
    {
        var raw=json.GetRawText();var receipt=new Receipt{DeviceId=d.Id,Operation=operation,Raw=raw};db.Add(receipt);
        try {
            Guard.Check(raw.Length<=1024*1024,"报文超过 1 MB");var protocol=protocols.SingleOrDefault(p=>p.Id==d.Protocol);Guard.Check(protocol!=null,"协议适配器未启用","unsupported_protocol",409);
            object response;
            if(operation=="check"){var input=protocol!.NormalizeCheck(json);receipt.RecordId=input.ExternalTaskId;response=await Check(d,input);}
            else {var input=protocol!.NormalizeResult(json);receipt.RecordId=input.RecordId;response=await Upload(d,input,raw);}
            receipt.Status="Accepted";await db.SaveChangesAsync();return new{accepted=true,data=response};
        } catch(Exception e) when(e is ApiException or JsonException or FormatException) {
            receipt.Status="Rejected";receipt.Reason=e.Message;await db.SaveChangesAsync();return new{accepted=false,code=(e as ApiException)?.Code??"invalid_payload",message=e.Message,receiptId=receipt.Id};
        }
    }
    async Task<object> Check(Device d,Check i)
    {
        Guard.Check(!string.IsNullOrWhiteSpace(i.ExternalTaskId)&&i.ExternalTaskId.Length<=128,"测试任务编号必填且最多 128 位");
        var s=await db.Set<Station>().FindAsync(d.StationId);Guard.Check(s!.Code==i.StationCode&&d.Code==i.DeviceCode,"设备/工位编号不匹配","device_station",409);
        var step=await db.Set<ProcessStep>().FindAsync(s.ProcessStepId);Guard.Check(step!.Sequence==3,"设备必须绑定仪器测试工位");
        var order=await production.Scope(s,i.BatchId);Guard.Check(order.Id==i.WorkOrderId,"工单与批次不一致","cross_order",409);
        Guard.Check(i.ProgramVersion==d.ProgramVersion,"测试程序版本与设备受控标准不一致","program_version",409);
        await production.Barcode("main",i.MainCode);var u=await db.Set<Unit>().SingleOrDefaultAsync(x=>x.MainCode==i.MainCode);Guard.Check(u!=null,"主码未过站","previous_step",409);
        Guard.Check(u!.BatchId==i.BatchId,"主码不属于当前工单/批次","cross_order",409);await production.Prerequisite(u,3);
        var old=await db.Set<TestTask>().SingleOrDefaultAsync(x=>x.DeviceId==d.Id&&x.ExternalId==i.ExternalTaskId);
        if(old!=null){Guard.Check(old.UnitId==u.Id&&old.BatchId==i.BatchId&&old.ProgramVersion==i.ProgramVersion,"重复任务编号内容不一致","task_conflict",409);return new{taskId=old.Id,status=old.Status,duplicate=true};}
        Guard.Check(u.SnCode==null&&u.PackCode==null,"产品已流转后段，新测试需异常处理，不自动覆盖","already_released",409);
        Guard.Check(!await db.Set<TestTask>().AnyAsync(x=>x.UnitId==u.Id&&x.Status=="Pending"),"已有未完成测试任务，请使用原任务编号重试","pending_task",409);
        var specs=JsonSerializer.Deserialize<Specification[]>(d.RequiredItems,new JsonSerializerOptions(JsonSerializerDefaults.Web));Guard.Check(specs?.Length>0,"设备未配置测试标准项目");
        var task=new TestTask{UnitId=u.Id,DeviceId=d.Id,StationId=s.Id,BatchId=i.BatchId,ExternalId=i.ExternalTaskId,ProgramVersion=d.ProgramVersion,Specification=d.RequiredItems};db.Add(task);await db.SaveChangesAsync();return new{taskId=task.Id,status=task.Status,duplicate=false};
    }
    async Task<object> Upload(Device d,Upload i,string raw)
    {
        Guard.Check(!string.IsNullOrWhiteSpace(i.RecordId)&&i.RecordId.Length<=128,"唯一上传记录编号必填且最多 128 位");
        var hash=Guard.Hash(JsonSerializer.Serialize(i));var old=await db.Set<TestResult>().SingleOrDefaultAsync(x=>x.DeviceId==d.Id&&x.RecordId==i.RecordId);
        if(old!=null){Guard.Check(old.PayloadHash==hash,"同一上传编号的内容发生变化","record_conflict",409);return new{resultId=old.Id,duplicate=true,verdict=old.Verdict};}
        var s=await db.Set<Station>().FindAsync(d.StationId);Guard.Check(s!.Code==i.StationCode&&d.Code==i.DeviceCode,"设备/工位编号不匹配");
        var t=await db.Set<TestTask>().SingleOrDefaultAsync(x=>x.Id==i.TaskId&&x.DeviceId==d.Id);Guard.Check(t!=null,"无法关联测试任务","unknown_task",409);
        Guard.Check(t!.Status=="Pending","任务已完成；复测须新建任务","task_completed",409);
        var u=await db.Set<Unit>().FindAsync(t.UnitId);Guard.Check(u!.MainCode==i.MainCode,"主码与任务不一致","task_main_mismatch",409);await production.Scope(s,t.BatchId);await production.Prerequisite(u,3);
        Guard.Check(u.SnCode==null&&u.PackCode==null,"产品已流转，测试仅保留为异常接收","already_released",409);
        Guard.Check(i.ProgramVersion==t.ProgramVersion,"上传程序版本与任务不一致");
        Guard.Check(i.TestedAt>=t.CreatedAt.AddMinutes(-5)&&i.TestedAt<=DateTimeOffset.UtcNow.AddMinutes(5),"测试时间不在任务有效范围");
        var spec=JsonSerializer.Deserialize<Specification[]>(t.Specification,new JsonSerializerOptions(JsonSerializerDefaults.Web))!;
        Guard.Check(i.Items!=null&&i.Items.Length==spec.Length&&i.Items.Select(x=>x.Name).Distinct().Count()==spec.Length,"测试参数缺失、重复或项目数量不匹配");
        foreach(var rule in spec){var v=i.Items!.SingleOrDefault(x=>x.Name==rule.Name);Guard.Check(v!=null&&v.Unit==rule.Unit&&v.Lower==rule.Lower&&v.Upper==rule.Upper,"参数名称、单位或上下限不符合任务标准");var pass=v!.Value>=rule.Lower&&v.Value<=rule.Upper;Guard.Check(v.Verdict==(pass?"PASS":"FAIL"),"项目判定与数值上下限矛盾");}
        var verdict=i.Items!.All(x=>x.Verdict=="PASS")?"PASS":"FAIL";Guard.Check(i.Verdict==verdict,"总判定与参数判定矛盾");
        var r=new TestResult{TestTaskId=t.Id,DeviceId=d.Id,RecordId=i.RecordId,TestedAt=i.TestedAt,Verdict=verdict,PayloadHash=hash,Raw=raw};db.Add(r);
        foreach(var m in i.Items!)db.Add(new TestItem{TestResultId=r.Id,Name=m.Name,Value=m.Value,Unit=m.Unit,Lower=m.Lower,Upper=m.Upper,Verdict=m.Verdict});
        t.Status="Completed";if(verdict=="PASS")await production.AddPassage(u,s,null);await db.SaveChangesAsync();return new{resultId=r.Id,duplicate=false,verdict};
    }
}
