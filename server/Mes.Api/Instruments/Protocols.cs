using System.Text.Json;
namespace Mes.Api.Instruments;

// Vendor adapters implement only protocol translation. Domain checks are shared and cannot be bypassed.
public interface IInstrumentProtocol
{
    string Id { get; }
    Upload NormalizeResult(JsonElement payload);
    Check NormalizeCheck(JsonElement payload);
}
public sealed class GenericV1 : IInstrumentProtocol
{
    public string Id=>"generic-v1";
    static readonly JsonSerializerOptions Json=new(JsonSerializerDefaults.Web);
    public Upload NormalizeResult(JsonElement p)=>p.Deserialize<Upload>(Json)??throw new JsonException("缺少测试结果");
    public Check NormalizeCheck(JsonElement p)=>p.Deserialize<Check>(Json)??throw new JsonException("缺少工艺检查参数");
}
public record Specification { public required string Name {get;init;} public required string Unit {get;init;} public required decimal Lower {get;init;} public required decimal Upper {get;init;} }
public record Measurement : Specification { public required decimal Value {get;init;} public required string Verdict {get;init;} }
public record Check { public required string ExternalTaskId {get;init;} public required string MainCode {get;init;} public required Guid WorkOrderId {get;init;} public required Guid BatchId {get;init;} public required string DeviceCode {get;init;} public required string StationCode {get;init;} public required string ProgramVersion {get;init;} }
public record Upload { public required string RecordId {get;init;} public required Guid TaskId {get;init;} public required string MainCode {get;init;} public required string DeviceCode {get;init;} public required string StationCode {get;init;} public required DateTimeOffset TestedAt {get;init;} public required string ProgramVersion {get;init;} public required string Verdict {get;init;} public required Measurement[] Items {get;init;} public string? RawData {get;init;} public string? RawFileReference {get;init;} }
