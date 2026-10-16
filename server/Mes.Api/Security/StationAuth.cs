using Mes.Api.Data;
using Microsoft.EntityFrameworkCore;
namespace Mes.Api.Security;

public static class StationAuth
{
    const string CookieName = "mes.station";
    public record Resume(string? StationToken = null);
    public static void CheckHeader(HttpContext http) => Guard.Check(http.Request.Headers["X-MES-Station-Auth"] == "1", "缺少终端认证请求头", "station_auth", 403);
    static CookieOptions Options(HttpContext http) => new() { HttpOnly = true, Secure = http.Request.IsHttps, SameSite = http.Request.IsHttps ? SameSiteMode.None : SameSiteMode.Strict, Path = "/api/auth/station", IsEssential = true };
    public static void SetCookie(HttpContext http, string token, DateTimeOffset expires) { var options = Options(http); options.Expires = expires; http.Response.Cookies.Append(CookieName, token, options); }
    public static void Map(WebApplication app)
    {
        app.MapPost("/api/auth/station/resume", async (Resume input, HttpContext http, MesDb db) => {
            CheckHeader(http);
            var token = input.StationToken ?? http.Request.Cookies[CookieName];
            Guard.Check(!string.IsNullOrEmpty(token), "此终端尚未登录", "station_auth", 401);
            var hash = Guard.Hash(token!);
            var credential = await db.Set<StationCredential>().SingleOrDefaultAsync(c => c.TokenHash == hash && c.Expires > DateTimeOffset.UtcNow);
            var user = credential == null ? null : await db.Users.SingleOrDefaultAsync(u => u.Id == credential.UserId && u.Active);
            Guard.Check(user != null, "终端登录已撤销，请重新登录", "station_auth", 401);
            await db.Set<Session>().Where(s => s.StationCredentialId == credential!.Id && s.Expires <= DateTimeOffset.UtcNow).ExecuteDeleteAsync();
            var accessToken = Guard.Token();
            db.Add(new Session { UserId = user!.Id, StationCredentialId = credential!.Id, TokenHash = Guard.Hash(accessToken), Expires = DateTimeOffset.UtcNow.AddHours(8) });
            await db.SaveChangesAsync();
            http.Response.Headers.CacheControl = "no-store";
            return Results.Ok(new { authenticated = true, token = accessToken, user = new { user.Id, user.Account, user.Name, user.Role } });
        }).AllowAnonymous().RequireRateLimiting("login");
        app.MapPost("/api/auth/station/forget", async (Resume input, HttpContext http, MesDb db) => {
            CheckHeader(http);
            var token = input.StationToken ?? http.Request.Cookies[CookieName];
            if (!string.IsNullOrEmpty(token)) {
                var hash = Guard.Hash(token);
                var credential = await db.Set<StationCredential>().SingleOrDefaultAsync(c => c.TokenHash == hash);
                if (credential != null) {
                    await db.Set<Session>().Where(s => s.StationCredentialId == credential.Id).ExecuteDeleteAsync();
                    db.Remove(credential); await db.SaveChangesAsync();
                }
            }
            http.Response.Cookies.Delete(CookieName, Options(http));
            return Results.Ok();
        }).AllowAnonymous();
    }
}
