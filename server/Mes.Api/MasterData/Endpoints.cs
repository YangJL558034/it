using System.Security.Claims;
using System.Text.Json;
using Mes.Api.Data;
using Mes.Api.Security;
using Microsoft.AspNetCore.Identity;
using Microsoft.EntityFrameworkCore;
namespace Mes.Api.MasterData;

public static class Endpoints
{
    public static readonly string[] Roles = ["Admin", "Supervisor", "Quality", "Operator", "TV"];
    public static void MapMasterData(this WebApplication app)
    {
        app.MapGet("/api/catalog", async (MesDb db) => new {
            lines = await db.Set<Line>().OrderBy(x => x.Code).ToListAsync(), stations = await db.Set<Station>().OrderBy(x => x.Code).ToListAsync(),
            processes = await db.Set<ProcessStep>().OrderBy(x => x.Sequence).ToListAsync(), products = await db.Set<Product>().ToListAsync(),
            orders = await db.Set<WorkOrder>().OrderBy(x => x.Code).ToListAsync(), batches = await db.Set<Batch>().ToListAsync()
        }).RequireAuthorization("Office");
        Crud<Line>(app, "lines", (x, j) => { x.Code = Str(j,"code"); x.Name = Str(j,"name"); x.Workshop = Str(j,"workshop"); });
        Crud<Product>(app, "products", (x, j) => { x.Code = Str(j,"code"); x.Name = Str(j,"name"); });
        Crud<Station>(app, "stations", (x, j) => { x.Code = Str(j,"code"); x.Name = Str(j,"name"); x.LineId = Id(j,"lineId"); x.ProcessStepId = Id(j,"processStepId"); });
        Crud<WorkOrder>(app, "orders", (x, j) => { x.Code = Str(j,"code"); x.LineId = Id(j,"lineId"); x.ProductId = Id(j,"productId"); x.Plan = Num(j,"plan"); x.Status = Str(j,"status"); Guard.Check(new[]{"Open","Closed"}.Contains(x.Status), "工单状态无效"); });
        Crud<Batch>(app, "batches", (x, j) => { x.Code = Str(j,"code"); x.WorkOrderId = Id(j,"workOrderId"); x.Plan = Num(j,"plan"); });
        app.MapGet("/api/admin/users", async (MesDb db) => await db.Users.Select(x => new { x.Id, x.Account, x.Name, x.Role, x.Active }).ToListAsync()).RequireAuthorization("Admin");
        app.MapGet("/api/admin/roles", () => Roles).RequireAuthorization("Admin");
        app.MapGet("/api/admin/grants", async (MesDb db) => await db.Set<StationGrant>().ToListAsync()).RequireAuthorization("Admin");
        app.MapPost("/api/admin/users", async (JsonElement j, MesDb db) => {
            var u = new User { Account = Str(j,"account"), Name = Str(j,"name"), Role = Str(j,"role") };
            Guard.Check(Roles.Contains(u.Role), "角色无效"); var pwd = Str(j,"password"); Guard.Password(pwd); u.PasswordHash = new PasswordHasher<User>().HashPassword(u,pwd); db.Add(u); await db.SaveChangesAsync(); return Results.Ok(new { u.Id });
        }).RequireAuthorization("Admin");
        app.MapPut("/api/admin/users/{id:guid}", async (Guid id, JsonElement j, ClaimsPrincipal p, MesDb db) => {
            var u = await db.Users.SingleAsync(x => x.Id == id); var role = Str(j,"role"); Guard.Check(Roles.Contains(role), "角色无效");
            var active = j.GetProperty("active").GetBoolean(); Guard.Check(id != p.UserId() || (active && role == "Admin"), "不能禁用自己或撤销自己的管理员角色");
            u.Name = Str(j,"name"); u.Role = role; u.Active = active;
            if (j.TryGetProperty("password",out var pw) && !string.IsNullOrEmpty(pw.GetString())) { Guard.Password(pw.GetString()!); u.PasswordHash = new PasswordHasher<User>().HashPassword(u,pw.GetString()!); u.FailedLogins=0; u.LockedUntil=null; }
            await db.Set<Session>().Where(x=>x.UserId==id).ExecuteDeleteAsync(); await db.SaveChangesAsync(); return Results.Ok();
        }).RequireAuthorization("Admin");
        app.MapPost("/api/admin/grants", async (JsonElement j, MesDb db) => { db.Add(new StationGrant { UserId=Id(j,"userId"),StationId=Id(j,"stationId") }); await db.SaveChangesAsync(); return Results.Ok(); }).RequireAuthorization("Admin");
        app.MapDelete("/api/admin/grants/{id:guid}", async (Guid id, MesDb db) => { await db.Set<StationGrant>().Where(x=>x.Id==id).ExecuteDeleteAsync(); return Results.Ok(); }).RequireAuthorization("Admin");
        app.MapGet("/api/admin/audit", async (MesDb db) => await db.Set<Audit>().OrderByDescending(x=>x.At).Take(500).ToListAsync()).RequireAuthorization("Office");
        app.MapGet("/api/admin/rules", async (MesDb db) => await db.Set<BarcodeRule>().OrderByDescending(x=>x.Version).ToListAsync()).RequireAuthorization("Office");
        app.MapPost("/api/admin/rules", async (BarcodeRule r, MesDb db) => {
            Guard.Check(new[]{"main","cell","sn","pack"}.Contains(r.Kind) && r.Length is >=1 and <=128 && new[]{"alnum","numeric","printable"}.Contains(r.Charset), "条码类型、长度或字符类型无效");
            Guard.Check(r.Prefixes.Length <= 20 && r.Prefixes.All(p=>p.Length>0 && p.Length<=r.Length && System.Text.RegularExpressions.Regex.IsMatch(p, r.Charset=="numeric" ? "^[0-9]+$" : r.Charset=="alnum" ? "^[A-Za-z0-9]+$" : "^[!-~]+$")), "前缀与长度或字符类型不兼容");
            r.Id=Guid.NewGuid(); r.Version=(await db.Set<BarcodeRule>().Where(x=>x.Kind==r.Kind).MaxAsync(x=>(int?)x.Version)??0)+1; r.PublishedAt=DateTimeOffset.UtcNow; db.Add(r); await db.Set<ScanContext>().ExecuteDeleteAsync(); await db.SaveChangesAsync(); return Results.Ok(r);
        }).RequireAuthorization("Admin");
        app.MapGet("/api/admin/settings", async (MesDb db) => await db.Set<Setting>().ToListAsync()).RequireAuthorization("Admin");
        app.MapPut("/api/admin/settings", async (JsonElement j, MesDb db) => {
            foreach(var prop in j.EnumerateObject()) { Guard.Check(new[]{"factory","backupIntervalHours","backupRetentionDays"}.Contains(prop.Name), "配置项不允许"); var val=prop.Value.ToString();
                if(prop.Name!="factory") Guard.Check(int.TryParse(val,out var n) && n>=1 && n<=3650,"数值须为 1–3650");
                var s=await db.Set<Setting>().SingleOrDefaultAsync(x=>x.Key==prop.Name); if(s==null){s=new Setting{Key=prop.Name};db.Add(s);} s.Value=val;
            } await db.SaveChangesAsync(); return Results.Ok();
        }).RequireAuthorization("Admin");
    }
    static void Crud<T>(WebApplication app, string path, Action<T,JsonElement> assign) where T:Row,new()
    {
        app.MapGet("/api/admin/"+path, async (MesDb db)=>await db.Set<T>().ToListAsync()).RequireAuthorization("Office");
        app.MapPost("/api/admin/"+path, async (JsonElement j, MesDb db)=> {var x=new T(); assign(x,j); await Validate(db,x); db.Add(x); await db.SaveChangesAsync(); return Results.Ok(x);}).RequireAuthorization("Manage");
        app.MapPut("/api/admin/"+path+"/{id:guid}", async (Guid id,JsonElement j,MesDb db)=> {
            var x=await db.Set<T>().SingleOrDefaultAsync(x=>x.Id==id); Guard.Check(x!=null,"记录不存在", "not_found",404);
            var before=JsonSerializer.Serialize(x); assign(x!,j);
            if(before!=JsonSerializer.Serialize(x)) await Protect(db,x!);
            await Validate(db,x!); await db.SaveChangesAsync(); return Results.Ok(x);
        }).RequireAuthorization("Manage");
        app.MapDelete("/api/admin/"+path+"/{id:guid}", async(Guid id,MesDb db)=> {var x=await db.Set<T>().FindAsync(id); Guard.Check(x!=null,"记录不存在","not_found",404); await Protect(db,x!); db.Remove(x!); await db.SaveChangesAsync(); return Results.Ok();}).RequireAuthorization("Manage");
    }
    static async Task Protect<T>(MesDb db,T row) where T:Row
    {
        var used=row switch {
            WorkOrder => await db.Set<Batch>().AnyAsync(x=>x.WorkOrderId==row.Id),
            Batch => await db.Set<Unit>().AnyAsync(x=>x.BatchId==row.Id),
            Station => await db.Set<Passage>().AnyAsync(x=>x.StationId==row.Id) || await db.Set<Device>().AnyAsync(x=>x.StationId==row.Id),
            Line => await db.Set<WorkOrder>().AnyAsync(x=>x.LineId==row.Id) || await db.Set<Station>().AnyAsync(x=>x.LineId==row.Id),
            Product => await db.Set<WorkOrder>().AnyAsync(x=>x.ProductId==row.Id), _=>false
        }; Guard.Check(!used,"已有下游关联或生产历史，禁止修改/删除；请新建版本或新工单","referenced",409);
    }
    static async Task Validate<T>(MesDb db,T row) where T:Row
    {
        if(row is WorkOrder o) Guard.Check(o.Plan>0,"计划数量必须大于 0");
        if(row is Batch b){var o2=await db.Set<WorkOrder>().FindAsync(b.WorkOrderId); Guard.Check(o2!=null,"工单不存在"); var allocated=await db.Set<Batch>().Where(x=>x.WorkOrderId==b.WorkOrderId && x.Id!=b.Id).SumAsync(x=>x.Plan);Guard.Check(b.Plan>0 && allocated+b.Plan<=o2!.Plan,"批次数量合计不得超过工单计划");}
    }
    public static string Str(JsonElement j,string k) {var s=j.GetProperty(k).GetString()??""; Guard.Check(s.Length>0 && s.Length<=1024,$"{k} 必填且不得超过 1024 字符");return s;}
    public static Guid Id(JsonElement j,string k)=>j.GetProperty(k).GetGuid();
    public static int Num(JsonElement j,string k)=>j.GetProperty(k).GetInt32();
}
