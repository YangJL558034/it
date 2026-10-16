using Microsoft.EntityFrameworkCore;
namespace Mes.Api.Data;

public class MesDb(DbContextOptions<MesDb> options) : DbContext(options)
{
    public DbSet<User> Users => Set<User>();
    protected override void OnModelCreating(ModelBuilder b)
    {
        foreach (var t in typeof(Row).Assembly.GetTypes().Where(t => t.IsSubclassOf(typeof(Row)) && !t.IsAbstract)) b.Entity(t).HasKey("Id");
        b.Entity<User>().HasIndex(x => x.Account).IsUnique();
        b.Entity<StationCredential>().HasIndex(x => x.TokenHash).IsUnique();
        Link<StationCredential, User>(b, "UserId"); Link<Session, StationCredential>(b, "StationCredentialId");
        b.Entity<Session>().HasIndex(x => x.TokenHash).IsUnique();
        b.Entity<Line>().HasIndex(x => x.Code).IsUnique();
        b.Entity<Station>().HasIndex(x => x.Code).IsUnique();
        b.Entity<Product>().HasIndex(x => x.Code).IsUnique();
        b.Entity<WorkOrder>().HasIndex(x => x.Code).IsUnique();
        b.Entity<Batch>().HasIndex(x => x.Code).IsUnique();
        b.Entity<ProcessStep>().HasIndex(x => x.Sequence).IsUnique();
        b.Entity<StationGrant>().HasIndex(x => new { x.UserId, x.StationId }).IsUnique();
        b.Entity<RolePermission>().HasIndex(x=>new{x.Role,x.Permission}).IsUnique();
        b.Entity<Unit>().HasIndex(x => x.MainCode).IsUnique();
        b.Entity<Unit>().HasIndex(x => x.CellCode).IsUnique();
        b.Entity<Unit>().HasIndex(x => x.SnCode).IsUnique();
        b.Entity<Unit>().HasIndex(x => x.PackCode).IsUnique();
        b.Entity<Passage>().HasIndex(x => new { x.UnitId, x.ProcessStepId }).IsUnique();
        b.Entity<ScanContext>().HasIndex(x => new { x.UserId, x.StationId }).IsUnique();
        b.Entity<ScanRequest>().HasIndex(x => new { x.UserId, x.RequestId }).IsUnique();
        b.Entity<BarcodeRule>().HasIndex(x => new { x.Kind, x.Version }).IsUnique();
        b.Entity<Device>().HasIndex(x => x.Code).IsUnique();
        b.Entity<Device>().HasIndex(x => x.KeyHash).IsUnique();
        b.Entity<TestTask>().Property(x => x.Sequence).ValueGeneratedOnAdd();
        b.Entity<TestTask>().HasIndex(x => new { x.DeviceId, x.ExternalId }).IsUnique();
        b.Entity<TestResult>().HasIndex(x => new { x.DeviceId, x.RecordId }).IsUnique();
        b.Entity<TestResult>().HasIndex(x => x.TestTaskId).IsUnique();
        b.Entity<SopVersion>().HasIndex(x => new { x.ProcessStepId, x.Version }).IsUnique();
        b.Entity<MessageAck>().HasIndex(x => new { x.MessageId, x.UserId }).IsUnique();
        b.Entity<Setting>().HasIndex(x => x.Key).IsUnique();
        Link<Session, User>(b, "UserId"); Link<Station, Line>(b, "LineId"); Link<Station, ProcessStep>(b, "ProcessStepId");
        Link<StationGrant, User>(b, "UserId"); Link<StationGrant, Station>(b, "StationId");
        Link<WorkOrder, Product>(b, "ProductId"); Link<WorkOrder, Line>(b, "LineId"); Link<Batch, WorkOrder>(b, "WorkOrderId"); Link<Unit, Batch>(b, "BatchId");
        Link<Passage, Unit>(b, "UnitId"); Link<Passage, Station>(b, "StationId"); Link<Passage, ProcessStep>(b, "ProcessStepId"); Link<Passage, User>(b, "UserId"); Link<Passage, SopVersion>(b, "SopVersionId"); Link<Passage, BarcodeRule>(b, "BarcodeRuleId");
        Link<ScanContext, User>(b, "UserId"); Link<ScanContext, Station>(b, "StationId"); Link<ScanContext, Batch>(b, "BatchId"); Link<ScanContext, Unit>(b, "UnitId"); Link<ScanRequest, User>(b, "UserId");
        Link<Device, Station>(b, "StationId"); Link<TestTask, Unit>(b, "UnitId"); Link<TestTask, Device>(b, "DeviceId"); Link<TestTask, Station>(b, "StationId"); Link<TestTask, Batch>(b, "BatchId");
        Link<TestTask, SopVersion>(b, "SopVersionId"); Link<TestResult, TestTask>(b, "TestTaskId"); Link<TestResult, Device>(b, "DeviceId"); Link<TestItem, TestResult>(b, "TestResultId"); Link<Receipt, Device>(b, "DeviceId");
        Link<SopVersion, ProcessStep>(b, "ProcessStepId"); Link<SopVersion, User>(b, "CreatedBy"); Link<SopVersion, User>(b, "ReviewedBy"); Link<SopFile, SopVersion>(b, "SopVersionId");
        Link<Message, Line>(b, "LineId"); Link<Message, User>(b, "CreatedBy"); Link<MessageAck, Message>(b, "MessageId"); Link<MessageAck, User>(b, "UserId");
        Link<Audit, User>(b, "UserId"); Link<ExportJob, User>(b, "UserId"); Link<ExportJob, Batch>(b, "BatchId"); Link<ExportJob, WorkOrder>(b, "WorkOrderId"); Link<BackupJob, User>(b, "UserId");
    }
    static void Link<T, U>(ModelBuilder b, string fk) where T : Row where U : Row => b.Entity<T>().HasOne<U>().WithMany().HasForeignKey(fk).OnDelete(DeleteBehavior.Restrict);
}
