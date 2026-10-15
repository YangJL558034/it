namespace Mes.Api.Operations;
public static class StoragePaths
{
    public static string Get(IConfiguration c,string name){var root=Path.GetFullPath(c["Storage:Root"]!);var path=c["Storage:"+name];var result=Path.GetFullPath(string.IsNullOrWhiteSpace(path)?Path.Combine(root,name.ToLowerInvariant()):path);Directory.CreateDirectory(result);return result;}
}
