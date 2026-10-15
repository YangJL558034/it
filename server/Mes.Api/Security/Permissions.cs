using System.Security.Claims;
using Mes.Api.Data;
using Microsoft.EntityFrameworkCore;
namespace Mes.Api.Security;
public static class Permissions
{
    public static readonly string[] Names=["Manage","Office","Quality","Operate","Board","ReadProduction"];
    public static async Task<bool> Has(ClaimsPrincipal user,string permission,MesDb db)=>user.IsInRole("Admin")||await db.Set<RolePermission>().AnyAsync(x=>x.Role==user.FindFirstValue(ClaimTypes.Role)&&x.Permission==permission);
    public static async Task Seed(MesDb db){if(await db.Set<Setting>().AnyAsync(s=>s.Key=="permissionsInitialized"))return;if(!await db.Set<RolePermission>().AnyAsync())foreach(var role in new[]{"Supervisor","Quality","Operator","TV"})foreach(var permission in role switch{"Supervisor"=>new[]{"Manage","Office","Operate","Board","ReadProduction"},"Quality"=>new[]{"Office","Quality","Board","ReadProduction"},"Operator"=>new[]{"Operate","ReadProduction"},_=>new[]{"Board"}})db.Add(new RolePermission{Role=role,Permission=permission});db.Add(new Setting{Key="permissionsInitialized",Value="true"});await db.SaveChangesAsync();}
}
