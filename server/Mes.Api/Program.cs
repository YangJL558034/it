using System.Security.Claims;
using System.Text.Json;
using System.Threading.RateLimiting;
using Mes.Api.Data;
using Mes.Api.Security;
using Mes.Api.MasterData;
using Mes.Api.Production;
using Mes.Api.Instruments;
using Microsoft.AspNetCore.Authentication;
using Microsoft.AspNetCore.SignalR;
using Microsoft.EntityFrameworkCore;
using Npgsql;

var builder=WebApplication.CreateBuilder(args);
builder.Configuration.AddJsonFile(Path.Combine(AppContext.BaseDirectory,"appsettings.Local.json"),optional:true).AddEnvironmentVariables();
builder.Host.UseWindowsService(options=>options.ServiceName="FactoryMES");
builder.Services.AddDbContext<MesDb>(o=>o.UseNpgsql(builder.Configuration.GetConnectionString("Mes") ?? throw new InvalidOperationException("缺少 ConnectionStrings__Mes")));
builder.Services.AddAuthentication("Session").AddScheme<AuthenticationSchemeOptions,SessionAuth>("Session",_=>{});
builder.Services.AddAuthorization(o=>{
    o.AddPolicy("Admin",p=>p.RequireRole("Admin")); o.AddPolicy("Manage",p=>p.RequireRole("Admin","Supervisor"));
    o.AddPolicy("Office",p=>p.RequireRole("Admin","Supervisor","Quality")); o.AddPolicy("Quality",p=>p.RequireRole("Admin","Quality"));
    o.AddPolicy("Operate",p=>p.RequireRole("Admin","Operator","Supervisor")); o.AddPolicy("Board",p=>p.RequireRole("Admin","Supervisor","Quality","TV"));
});
builder.Services.AddCors(o=>o.AddDefaultPolicy(p=>p.WithOrigins(builder.Configuration.GetSection("Cors:Origins").Get<string[]>()??[]).AllowAnyHeader().AllowAnyMethod().AllowCredentials()));
builder.Services.AddRateLimiter(o=>{o.RejectionStatusCode=429;o.AddPolicy("login",c=>RateLimitPartition.GetFixedWindowLimiter(c.Connection.RemoteIpAddress?.ToString()??"unknown",_=>new FixedWindowRateLimiterOptions{PermitLimit=20,Window=TimeSpan.FromMinutes(1)}));});
builder.Services.AddSignalR();
builder.Services.AddScoped<ProductionService>();
builder.Services.AddScoped<InstrumentService>();builder.Services.AddSingleton<IInstrumentProtocol,GenericV1>();
builder.Services.Configure<Microsoft.AspNetCore.Http.Features.FormOptions>(o=>o.MultipartBodyLengthLimit=256*1024*1024);
var app=builder.Build();
Directory.CreateDirectory(Path.GetFullPath(builder.Configuration["Storage:Root"]!));
using(var scope=app.Services.CreateScope()) {var db=scope.ServiceProvider.GetRequiredService<MesDb>();await db.Database.MigrateAsync();await AuthEndpoints.Seed(db,builder.Configuration);}
if(args.Contains("--migrate-only")) return;
app.UseCors();app.UseRateLimiter();app.UseAuthentication();app.UseAuthorization();
app.Use(async(ctx,next)=>{
    var db=ctx.RequestServices.GetRequiredService<MesDb>();
    var write=ctx.Request.Path.StartsWithSegments("/api") && new[]{"POST","PUT","DELETE"}.Contains(ctx.Request.Method);
    var original=ctx.Response.Body;
    using var buffer=new MemoryStream();
    if(write) ctx.Response.Body=buffer;
    Microsoft.EntityFrameworkCore.Storage.IDbContextTransaction? tx=null;
    try {
        if(write){tx=await db.Database.BeginTransactionAsync();await db.Database.ExecuteSqlRawAsync("SELECT pg_advisory_xact_lock(74215001)");}
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
        db.Add(new Audit{UserId=ctx.User.Identity?.IsAuthenticated==true?ctx.User.UserId():null,Action=ctx.Request.Method+" "+ctx.Request.Path,Detail=ctx.Connection.RemoteIpAddress?.ToString()??"",Outcome=ctx.Response.StatusCode.ToString()});
        await db.SaveChangesAsync();
        ctx.Response.Body=original; buffer.Position=0; await buffer.CopyToAsync(original);
        if(ctx.Response.StatusCode<400) await ctx.RequestServices.GetRequiredService<IHubContext<MesHub>>().Clients.All.SendAsync("changed", new {at=DateTimeOffset.UtcNow});
    }
});
app.MapGet("/api/health",async(MesDb db,IConfiguration config)=>{
    await db.Database.ExecuteSqlRawAsync("SELECT 1");var path=Path.Combine(Path.GetFullPath(config["Storage:Root"]!),"health-"+Guid.NewGuid()+".tmp");await File.WriteAllTextAsync(path,"ok");File.Delete(path);return Results.Ok(new{status="ok",database=true,files=true,version="1.0.0",at=DateTimeOffset.UtcNow});
});
app.MapAuth();app.MapMasterData();app.MapProduction();app.MapInstruments();
app.MapHub<MesHub>("/hubs/mes").RequireAuthorization();
app.UseDefaultFiles();app.UseStaticFiles();app.MapFallbackToFile("index.html");
await app.RunAsync();
public class MesHub : Hub {}
public partial class Program {}
