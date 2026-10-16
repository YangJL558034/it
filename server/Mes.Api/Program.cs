using System.Security.Claims;
using System.Text.Json;
using System.Threading.RateLimiting;
using Mes.Api.Data;
using Mes.Api.Security;
using Mes.Api.MasterData;
using Mes.Api.Production;
using Mes.Api.Instruments;
using Mes.Api.Sop;
using Mes.Api.Reporting;
using Mes.Api.Operations;
using Microsoft.AspNetCore.Authentication;
using Microsoft.AspNetCore.SignalR;
using Microsoft.EntityFrameworkCore;
using Npgsql;

var builder=WebApplication.CreateBuilder(args.Where(x=>x!="--migrate-only").ToArray());
builder.Configuration.AddJsonFile(builder.Configuration["config"]??Path.Combine(AppContext.BaseDirectory,"appsettings.Local.json"),optional:true).AddEnvironmentVariables();
builder.Host.UseWindowsService(options=>options.ServiceName="FactoryMES");
builder.Services.AddDbContext<MesDb>(o=>o.UseNpgsql(builder.Configuration.GetConnectionString("Mes") ?? throw new InvalidOperationException("缺少 ConnectionStrings__Mes")));
builder.Services.AddAuthentication("Session").AddScheme<AuthenticationSchemeOptions,SessionAuth>("Session",_=>{});
builder.Services.AddAuthorization(o=>{
    o.AddPolicy("Admin",p=>p.RequireRole("Admin"));
    foreach(var permission in Permissions.Names)o.AddPolicy(permission,p=>p.RequireAuthenticatedUser().RequireAssertion(async context=>context.Resource is HttpContext http&&await Permissions.Has(context.User,permission,http.RequestServices.GetRequiredService<MesDb>())));
});
builder.Services.AddCors(o=>o.AddDefaultPolicy(p=>p.WithOrigins(builder.Configuration.GetSection("Cors:Origins").Get<string[]>()??[]).AllowAnyHeader().AllowAnyMethod().AllowCredentials()));
builder.Services.AddRateLimiter(o=>{o.RejectionStatusCode=429;o.AddPolicy("login",c=>RateLimitPartition.GetFixedWindowLimiter(c.Connection.RemoteIpAddress?.ToString()??"unknown",_=>new FixedWindowRateLimiterOptions{PermitLimit=20,Window=TimeSpan.FromMinutes(1)}));});
builder.Services.AddSignalR();
builder.Services.AddScoped<ProductionService>();
builder.Services.AddScoped<InstrumentService>();builder.Services.AddSingleton<IInstrumentProtocol,GenericV1>();
builder.Services.AddSingleton<MaintenanceState>();builder.Services.AddSingleton<BackupService>();builder.Services.AddHostedService<Worker>();
builder.Services.Configure<Microsoft.AspNetCore.Http.Features.FormOptions>(o=>o.MultipartBodyLengthLimit=256*1024*1024);
var app=builder.Build();
Directory.CreateDirectory(Path.GetFullPath(builder.Configuration["Storage:Root"]!));
if(File.Exists(Path.Combine(Path.GetFullPath(builder.Configuration["Storage:Root"]!),"restore-maintenance.json"))){var maintenance=app.Services.GetRequiredService<MaintenanceState>();maintenance.Active=true;maintenance.Status="RecoveryRequired";maintenance.Error="上次恢复异常中断，已保持禁止写入。请按恢复手册完成离线恢复和附件校验。";}
using(var scope=app.Services.CreateScope()) {var db=scope.ServiceProvider.GetRequiredService<MesDb>();if(app.Environment.IsDevelopment()||args.Contains("--migrate-only"))await db.Database.MigrateAsync();else if((await db.Database.GetPendingMigrationsAsync()).Any())throw new InvalidOperationException("存在未执行迁移，请使用安装器升级或显式执行迁移模式");await AuthEndpoints.Seed(db,builder.Configuration);}
if(args.Contains("--migrate-only")) return;
app.UseCors();app.UseRateLimiter();app.UseAuthentication();
app.Use(async(ctx,next)=>{
    var db=ctx.RequestServices.GetRequiredService<MesDb>();
    var maintenance=ctx.RequestServices.GetRequiredService<MaintenanceState>();
    var write=ctx.Request.Path.StartsWithSegments("/api") && new[]{"POST","PUT","DELETE"}.Contains(ctx.Request.Method);
    var original=ctx.Response.Body;
    using var buffer=new MemoryStream();
    if(write) ctx.Response.Body=buffer;
    Microsoft.EntityFrameworkCore.Storage.IDbContextTransaction? tx=null;
    var auditDetail=ctx.Connection.RemoteIpAddress?.ToString()??"";
    try {
        if(write&&ctx.Request.ContentType?.StartsWith("application/json")==true&&ctx.Request.ContentLength is >0 and <=32768){ctx.Request.EnableBuffering();using var reader=new StreamReader(ctx.Request.Body,leaveOpen:true);auditDetail+=" "+AuditPayload.Sanitize(await reader.ReadToEndAsync());ctx.Request.Body.Position=0;}
        if(write){Guard.Check(!maintenance.Active,"系统正在恢复，暂时禁止写入","maintenance",503);tx=await db.Database.BeginTransactionAsync();await db.Database.ExecuteSqlRawAsync("SELECT pg_advisory_xact_lock(74215001)");Guard.Check(!maintenance.Active,"系统正在恢复，暂时禁止写入","maintenance",503);}
        if(write&&ctx.User.Identity?.IsAuthenticated==true){var sessionId=Guid.Parse(ctx.User.FindFirstValue("session")!);var uid=ctx.User.UserId();var role=ctx.User.FindFirstValue(ClaimTypes.Role);Guard.Check(await (from session in db.Set<Session>() join user in db.Users on session.UserId equals user.Id where session.Id==sessionId&&session.UserId==uid&&session.Expires>DateTimeOffset.UtcNow&&user.Active&&user.Role==role select session).AnyAsync(),"会话已撤销或权限已变更，请重新登录","session_expired",401);}
        await next();
        if(tx!=null){if(ctx.Response.StatusCode<400) await tx.CommitAsync();else await tx.RollbackAsync();}
    } catch(Exception ex) {
        if(tx!=null) await tx.RollbackAsync(); db.ChangeTracker.Clear();
        var api=ex as ApiException;
        var pg=ex is DbUpdateException du ? du.InnerException as PostgresException : ex as PostgresException;
        ctx.Response.StatusCode=api?.Status ?? (pg?.SqlState is "23505" or "23503" ? 409 : ex is JsonException or KeyNotFoundException or FormatException ? 422 : 500);
        if(ctx.Response.StatusCode==500) app.Logger.LogError(ex,"Request failed: {Path}",ctx.Request.Path);
        if(write){buffer.SetLength(0);buffer.Position=0;}
        await ctx.Response.WriteAsJsonAsync(new {code=api?.Code??(ctx.Response.StatusCode==409?"conflict":"request_error"),message=api?.Message??(ctx.Response.StatusCode==409?"数据重复或已有关联，请刷新检查":"请求失败，请检查参数或联系管理员")});
    } finally {if(tx!=null) await tx.DisposeAsync();}
    if(write) {
        db.ChangeTracker.Clear();
        if(!maintenance.Active){db.Add(new Audit{UserId=ctx.User.Identity?.IsAuthenticated==true?ctx.User.UserId():null,Action=ctx.Request.Method+" "+ctx.Request.Path,Detail=auditDetail,Outcome=ctx.Response.StatusCode.ToString()});await db.SaveChangesAsync();}
        ctx.Response.Body=original; buffer.Position=0; await buffer.CopyToAsync(original);
        if(ctx.Response.StatusCode<400){
            var notification=new {at=DateTimeOffset.UtcNow};
            await ctx.RequestServices.GetRequiredService<IHubContext<MesHub>>().Clients.All.SendAsync("changed",notification);
            await ctx.RequestServices.GetRequiredService<IHubContext<TvHub>>().Clients.All.SendAsync("changed",notification);
        }
    }
});
app.UseAuthorization();
app.MapGet("/api/health",async(MesDb db,IConfiguration config,MaintenanceState maintenance)=>{
    if(maintenance.Active)return Results.Ok(new{status="maintenance",maintenance=true,version="1.0.1"});
    await db.Database.ExecuteSqlRawAsync("SELECT 1");foreach(var folder in new[]{"Sop","Attachments","Logs","Backups"}){var path=Path.Combine(StoragePaths.Get(config,folder),"health-"+Guid.NewGuid()+".tmp");await File.WriteAllTextAsync(path,"ok");File.Delete(path);}return Results.Ok(new{status="ok",database=true,files=true,version="1.0.1",apiVersion="v1",supportedClients=new{station="1.0.x",tv="1.0.x"},at=DateTimeOffset.UtcNow});
});
app.MapAuth();app.MapMasterData();app.MapProduction();app.MapInstruments();app.MapSop();app.MapReporting();app.MapOperations();
app.MapHub<MesHub>("/hubs/mes").RequireAuthorization();
app.MapHub<TvHub>("/hubs/tv").AllowAnonymous();
app.UseDefaultFiles();app.UseStaticFiles();app.MapFallbackToFile("index.html");
await app.RunAsync();
public class MesHub : Hub {}
// Public notifications only: no callable production or management hub methods.
public class TvHub : Hub {}
public partial class Program {}
