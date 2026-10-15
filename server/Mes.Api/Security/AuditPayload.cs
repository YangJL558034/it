using System.Text.Json.Nodes;
namespace Mes.Api.Security;
public static class AuditPayload
{
    public static string Sanitize(string input){try{var node=JsonNode.Parse(input);Scrub(node);return node?.ToJsonString()??"";}catch{return "[invalid JSON]";}}
    static void Scrub(JsonNode? node){if(node is JsonObject obj)foreach(var key in obj.Select(p=>p.Key).ToArray()){if(new[]{"password","secret","token","key","rawdata","rawfilereference"}.Any(s=>key.Contains(s,StringComparison.OrdinalIgnoreCase)))obj[key]="[redacted]";else Scrub(obj[key]);}else if(node is JsonArray array)foreach(var child in array)Scrub(child);}
}
