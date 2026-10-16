using System.Security.Claims;
using System.Security.Cryptography;
using System.Text;
using System.Text.Encodings.Web;
using Mes.Api.Data;
using Microsoft.AspNetCore.Authentication;
using Microsoft.AspNetCore.Identity;
using Microsoft.EntityFrameworkCore;
using Microsoft.Extensions.Options;

namespace Mes.Api.Security;
public class ApiException(int status, string code, string message) : Exception(message) { public int Status => status; public string Code => code; }
public static class Guard
{
    public static void Check(bool ok, string message, string code = "validation", int status = 422) { if (!ok) throw new ApiException(status, code, message); }
    public static Guid UserId(this ClaimsPrincipal p) => Guid.Parse(p.FindFirstValue(ClaimTypes.NameIdentifier)!);
    public static string Hash(string s) => Convert.ToHexString(SHA256.HashData(Encoding.UTF8.GetBytes(s)));
    public static string Token() => Convert.ToHexString(RandomNumberGenerator.GetBytes(32));
    public static void Password(string s) => Check(s.Length >= 12 && s.Length <= 128 && s.Any(char.IsLetter) && s.Any(char.IsDigit), "密码须为 12–128 位且包含字母和数字");
}
public class SessionAuth(IOptionsMonitor<AuthenticationSchemeOptions> o, ILoggerFactory l, UrlEncoder e, MesDb db) : AuthenticationHandler<AuthenticationSchemeOptions>(o, l, e)
{
    protected override async Task<AuthenticateResult> HandleAuthenticateAsync()
    {
        var raw = Request.Headers.Authorization.ToString();
        var token = raw.StartsWith("Bearer ") ? raw[7..] : Request.Path.StartsWithSegments("/hubs/mes") ? Request.Query["access_token"].ToString() : "";
        if (string.IsNullOrEmpty(token)) return AuthenticateResult.NoResult();
        var hash = Guard.Hash(token);
        var s = await db.Set<Session>().AsNoTracking().FirstOrDefaultAsync(x => x.TokenHash == hash && x.Expires > DateTimeOffset.UtcNow && (x.StationCredentialId == null || db.Set<StationCredential>().Any(c => c.Id == x.StationCredentialId && c.Expires > DateTimeOffset.UtcNow)));
        var u = s == null ? null : await db.Users.AsNoTracking().FirstOrDefaultAsync(x => x.Id == s.UserId && x.Active);
        if (u == null) return AuthenticateResult.Fail("会话已失效");
        var claims = new[] { new Claim(ClaimTypes.NameIdentifier, u.Id.ToString()), new Claim(ClaimTypes.Name, u.Name), new Claim(ClaimTypes.Role, u.Role), new Claim("session", s!.Id.ToString()) };
        return AuthenticateResult.Success(new AuthenticationTicket(new ClaimsPrincipal(new ClaimsIdentity(claims, Scheme.Name)), Scheme.Name));
    }
}
public static class AuthEndpoints
{
    public record Login(string Account, string Password, bool RememberStation = false, bool Native = false);
    public static void MapAuth(this WebApplication app)
    {
        StationAuth.Map(app);
        async Task<IResult> LoginUser(Login input, MesDb db, HttpContext http) {
            var stationLogin = http.Request.Path == "/api/auth/station/login";
            if (stationLogin) { StationAuth.CheckHeader(http); Guard.Check(input.RememberStation, "工位登录必须使用终端登录流程"); }
            var u = await db.Users.FirstOrDefaultAsync(x => x.Account == input.Account && x.Active);
            if (!stationLogin && u?.LockedUntil > DateTimeOffset.UtcNow) return Results.Json(new { code = "locked", message = "登录失败次数过多，请稍后重试" }, statusCode: 429);
            var hasher = new PasswordHasher<User>();
            if (u == null || hasher.VerifyHashedPassword(u, u.PasswordHash, input.Password) == PasswordVerificationResult.Failed) {
                if (!stationLogin && u != null) { u.FailedLogins++; if (u.FailedLogins >= 5) u.LockedUntil = DateTimeOffset.UtcNow.AddMinutes(15); await db.SaveChangesAsync(); }
                // Return 200 with explicit failure so lockout counters commit in the write transaction.
                return Results.Ok(new { authenticated = false, message = "账号或密码错误" });
            }
            if (!stationLogin) { u.FailedLogins = 0; u.LockedUntil = null; }
            StationCredential? credential = null; string? stationToken = null;
            if (input.RememberStation) {
                StationAuth.CheckHeader(http);
                stationToken = Guard.Token();
                credential = new StationCredential { UserId = u.Id, TokenHash = Guard.Hash(stationToken), Expires = DateTimeOffset.UtcNow.AddYears(10) };
                db.Add(credential);
                if (!input.Native) StationAuth.SetCookie(http, stationToken, credential.Expires);
            }
            var token = Guard.Token(); db.Add(new Session { UserId = u.Id, StationCredentialId = credential?.Id, TokenHash = Guard.Hash(token), Expires = credential==null&&u.Role=="TV"?DateTimeOffset.UtcNow.AddDays(30):DateTimeOffset.UtcNow.AddHours(8) }); await db.SaveChangesAsync();
            return Results.Ok(new { authenticated = true, token, stationToken = input.Native ? stationToken : null, user = new { u.Id, u.Account, u.Name, u.Role } });
        }
        app.MapPost("/api/auth/login", LoginUser).RequireRateLimiting("login");
        // Station operators may retry passwords without an attempt limit or timed lockout.
        app.MapPost("/api/auth/station/login", LoginUser);
        app.MapGet("/api/auth/me", async (ClaimsPrincipal p, MesDb db) => await db.Users.Where(x => x.Id == p.UserId()).Select(u => new { u.Id, u.Account, u.Name, u.Role }).SingleAsync()).RequireAuthorization();
        app.MapPost("/api/auth/logout", async (ClaimsPrincipal p, MesDb db) => { var id = Guid.Parse(p.FindFirstValue("session")!); var session = await db.Set<Session>().FindAsync(id); if (session?.StationCredentialId is Guid credentialId) { await db.Set<Session>().Where(x => x.StationCredentialId == credentialId).ExecuteDeleteAsync(); await db.Set<StationCredential>().Where(x => x.Id == credentialId).ExecuteDeleteAsync(); } else await db.Set<Session>().Where(x => x.Id == id).ExecuteDeleteAsync(); return Results.Ok(); }).RequireAuthorization();
    }
    public static async Task Seed(MesDb db, IConfiguration config)
    {
        if (!await db.Users.AnyAsync()) {
            var pwd = config["Bootstrap:Password"] ?? throw new InvalidOperationException("首次启动必须配置 Bootstrap__Password"); Guard.Password(pwd);
            var u = new User { Account = config["Bootstrap:Account"] ?? "admin", Name = "系统管理员", Role = "Admin" };
            u.PasswordHash = new PasswordHasher<User>().HashPassword(u, pwd); db.Add(u);
        }
        if (!await db.Set<ProcessStep>().AnyAsync()) {
            string[] names = ["主码过站", "电芯绑定", "仪器测试", "SN 绑定", "包装绑定"];
            for (var i = 0; i < 5; i++) db.Add(new ProcessStep { Sequence = i + 1, Name = names[i] });
        }
        if (!await db.Set<BarcodeRule>().AnyAsync()) {
            foreach (var (kind, prefix, len) in new[] { ("main", "MC", 14), ("cell", "CELL", 16), ("sn", "SN", 14), ("pack", "PK", 14) }) db.Add(new BarcodeRule { Kind = kind, Prefixes = [prefix], Length = len, Version = 1 });
        }
        await db.SaveChangesAsync();
        await Permissions.Seed(db);
    }
}
