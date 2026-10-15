using Microsoft.EntityFrameworkCore;
using Microsoft.EntityFrameworkCore.Design;
namespace Mes.Api.Data;
public class DesignTimeDbFactory:IDesignTimeDbContextFactory<MesDb>
{
    public MesDb CreateDbContext(string[] args)=>new(new DbContextOptionsBuilder<MesDb>().UseNpgsql(Environment.GetEnvironmentVariable("ConnectionStrings__Mes")??"Host=localhost;Database=mes_design;Username=mes").Options);
}
