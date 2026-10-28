package com.ruoyi.mes.maintenance;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.ruoyi.common.exception.ServiceException;
import com.ruoyi.common.utils.SecurityUtils;
import com.ruoyi.mes.production.MesRecords;
import com.ruoyi.mes.realtime.MesChanges;
import com.ruoyi.mes.service.MesMasterService;
import com.ruoyi.mes.storage.MesDataPaths;
import java.io.*;
import java.nio.file.*;
import java.time.Instant;
import java.util.*;
import javax.sql.DataSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

@Service
public class MesTestCleanupService {

  public static final String PHRASE = "清理选中的测试工单";
  private final NamedParameterJdbcTemplate db;
  private final MesMasterService master;
  private final MesBackupService backups;
  private final MesMaintenanceJobs jobs;
  private final MesMaintenanceState state;
  private final MesDataPaths paths;
  private final ObjectMapper json;
  private final TransactionTemplate transaction;
  private final MesChanges changes;

  private record Slice(String key, String table, String where, String order) {}

  private static final String BATCHES =
    "select id from mes_batch where work_order_id in (:ids)", UNITS =
    "select id from mes_unit where batch_id in (" + BATCHES + ")", TASKS =
    "select id from mes_test_task where batch_id in (" +
    BATCHES +
    ")", RESULTS =
    "select id from mes_test_result where test_task_id in (" + TASKS + ")";
  private static final List<Slice> SLICES = List.of(
    new Slice(
      "items",
      "mes_test_item",
      "test_result_id in (" + RESULTS + ")",
      "id"
    ),
    new Slice(
      "results",
      "mes_test_result",
      "test_task_id in (" + TASKS + ")",
      "id"
    ),
    new Slice("tasks", "mes_test_task", "batch_id in (" + BATCHES + ")", "id"),
    new Slice("passages", "mes_passage", "unit_id in (" + UNITS + ")", "id"),
    new Slice("barcodes", "mes_barcode", "unit_id in (" + UNITS + ")", "code"),
    new Slice(
      "scanRequests",
      "mes_scan_request",
      "batch_id in (" + BATCHES + ")",
      "user_id,request_id"
    ),
    new Slice(
      "scanContexts",
      "mes_scan_context",
      "batch_id in (" + BATCHES + ")",
      "id"
    ),
    new Slice("exports", "mes_export_job", "work_order_id in (:ids)", "id"),
    new Slice("units", "mes_unit", "batch_id in (" + BATCHES + ")", "id"),
    new Slice(
      "legacyGuards",
      "mes_legacy_guard",
      "(resource='batches' and row_id in (" +
      BATCHES +
      ")) or (resource='orders' and row_id in (:ids))",
      "resource,row_id"
    ),
    new Slice(
      "marks",
      "mes_test_order_mark",
      "work_order_id in (:ids)",
      "work_order_id"
    ),
    new Slice("batches", "mes_batch", "work_order_id in (:ids)", "id"),
    new Slice("orders", "mes_work_order", "id in (:ids)", "id")
  );

  public MesTestCleanupService(
    NamedParameterJdbcTemplate db,
    MesMasterService master,
    MesBackupService backups,
    MesMaintenanceJobs jobs,
    MesMaintenanceState state,
    MesDataPaths paths,
    ObjectMapper json,
    PlatformTransactionManager manager,
    MesChanges changes
  ) {
    this.db = db;
    this.master = master;
    this.backups = backups;
    this.jobs = jobs;
    this.state = state;
    this.paths = paths;
    this.json = json;
    this.transaction = new TransactionTemplate(manager);
    this.changes = changes;
  }

  private List<UUID> ids(List<String> values) {
    if (
      values == null || values.isEmpty() || values.size() > 1000
    ) throw new ServiceException("必须明确选择 1–1000 个工单", 422);
    return values.stream().map(MesRecords::id).distinct().sorted().toList();
  }

  private void visible(List<UUID> ids) {
    for (UUID id : ids) master.visibleRow("orders", id);
  }

  public List<Map<String, Object>> orders(String keyword) {
    var p = new HashMap<String, Object>();
    p.put("departments", master.visibleDepartments());
    p.put("q", "%" + (keyword == null ? "" : keyword) + "%");
    return db
      .queryForList(
        "select o.*, (select count(*) from mes_batch b where b.work_order_id=o.id) as batches,(select count(*) from mes_unit u join mes_batch b on u.batch_id=b.id where b.work_order_id=o.id) as units from mes_work_order o where o.dept_id in (:departments) and o.code ilike :q order by o.updated_at desc limit 1000",
        p
      )
      .stream()
      .map(MesRecords::external)
      .toList();
  }

  @Transactional
  public void mark(
    List<String> selected,
    String reason,
    String suppliedPassword,
    String phrase
  ) {
    backups.verifyPassword(suppliedPassword);
    if (!"标记为测试工单".equals(phrase)) throw new ServiceException(
      "请确认标记测试工单的范围",
      422
    );
    if (
      reason == null || reason.isBlank() || reason.length() > 256
    ) throw new ServiceException("请填写测试工单标记原因，最多 256 字符", 422);
    var ids = ids(selected);
    db.getJdbcTemplate().execute("select pg_advisory_xact_lock(74215003)");
    state.assertWritable();
    visible(ids);
    for (UUID id : ids) {
      db.update(
        "update mes_work_order set is_test=true,version=version+1,updated_at=now() where id=:id",
        Map.of("id", id)
      );
      db.update(
        "insert into mes_test_order_mark(work_order_id,marked_by,reason) values(:id,:user,:reason) on conflict(work_order_id) do update set marked_by=excluded.marked_by,reason=excluded.reason,at=now()",
        Map.of("id", id, "user", SecurityUtils.getUserId(), "reason", reason)
      );
    }
    changes.publish();
  }

  private Map<String, Object> scope(List<UUID> ids) throws Exception {
    var p = Map.of("ids", ids);
    if (
      db.queryForObject(
        "select count(*) from mes_work_order where id in (:ids) and is_test=true",
        p,
        Long.class
      ) !=
      ids.size()
    ) throw new ServiceException(
      "只能清理明确标记的测试工单，未标记或已删除的工单被拒绝",
      422
    );
    var counts = new LinkedHashMap<String, Object>();
    var digest = java.security.MessageDigest.getInstance("SHA-256");
    for (var slice : SLICES) {
      String from = " from " + slice.table + " t where " + slice.where;
      counts.put(
        slice.key,
        db.queryForObject("select count(*)" + from, p, Long.class)
      );
      digest.update(
        slice.key.getBytes(java.nio.charset.StandardCharsets.UTF_8)
      );
      db.query(
        "select to_jsonb(t)::text" + from + " order by " + slice.order,
        p,
        (org.springframework.jdbc.core.RowCallbackHandler) rs -> {
          digest.update(
            rs.getString(1).getBytes(java.nio.charset.StandardCharsets.UTF_8)
          );
          digest.update((byte) 0);
        }
      );
    }
    return Map.of(
      "counts",
      counts,
      "digest",
      java.util.HexFormat.of().formatHex(digest.digest()),
      "orders",
      db.queryForList(
        "select id,code,plan,status from mes_work_order where id in (:ids) order by code",
        p
      )
    );
  }

  @Transactional(rollbackFor = Exception.class)
  public Map<String, Object> preview(List<String> selected) throws Exception {
    var ids = ids(selected);
    db.getJdbcTemplate().execute("select pg_advisory_xact_lock(74215003)");
    state.assertWritable();
    visible(ids);
    var scope = scope(ids);
    UUID id = UUID.randomUUID();
    String token = jobs.secret();
    var plan = new LinkedHashMap<String, Object>();
    plan.put("id", id.toString());
    plan.put("kind", "CleanupPlan");
    plan.put("operatorId", SecurityUtils.getUserId());
    plan.put("ids", ids);
    plan.put("expires", Instant.now().plusSeconds(600).toString());
    plan.put(
      "tokenHash",
      MesDataPaths.sha256(
        token.getBytes(java.nio.charset.StandardCharsets.UTF_8)
      )
    );
    plan.put("scope", scope);
    paths.atomicWrite(
      paths.resolve("maintenance/plans/" + id + ".json"),
      json.writeValueAsBytes(plan)
    );
    return Map.of(
      "planId",
      id,
      "confirmationToken",
      token,
      "confirmationPhrase",
      PHRASE,
      "scope",
      scope,
      "preserved",
      List.of(
        "产线、工位、产品定义",
        "用户、角色与设备",
        "SOP 与附件",
        "操作审计与仪器接收日志",
        "旧 .NET 数据库与历史"
      )
    );
  }

  @SuppressWarnings("unchecked")
  public Map<String, Object> clean(
    UUID planId,
    String token,
    String suppliedPassword,
    String phrase
  ) throws Exception {
    state.assertWritable();
    backups.verifyPassword(suppliedPassword);
    if (!PHRASE.equals(phrase)) throw new ServiceException(
      "请输入完整清理确认文字",
      422
    );
    var plan = backups.readPlan(planId, "CleanupPlan", token);
    var ids = ids(
      ((List<Object>) plan.get("ids")).stream().map(Object::toString).toList()
    );
    visible(ids);
    var result = jobs.start(
      "TestCleanup",
      SecurityUtils.getUserId(),
      SecurityUtils.getUsername(),
      job -> {
        state.begin("test cleanup " + job.get("id"));
        boolean changed = false;
        try (var lease = state.enterExclusive()) {
          try {
            var before = transaction.execute(status -> {
              try {
                db
                  .getJdbcTemplate()
                  .execute("select pg_advisory_xact_lock(74215003)");
                return scope(ids);
              } catch (Exception e) {
                throw new IllegalStateException(e);
              }
            });
            if (
              !Objects.equals(
                before.get("digest"),
                ((Map<?, ?>) plan.get("scope")).get("digest")
              )
            ) throw new ServiceException(
              "待清理数据已变化，请重新预览范围",
              409
            );
            job.put("scope", before);
            jobs.update(job, "progress", "清理前备份当前数据库与附件");
            var safety = backups.protectedBackup(
              ((Number) job.get("operatorId")).longValue(),
              job.get("operatorName").toString()
            );
            jobs.update(job, "preBackupId", safety.get("id"));
            jobs.update(job, "progress", "按已确认的测试工单范围执行清理");
            var exports = db.queryForList(
              "select file_name from mes_export_job where work_order_id in (:ids) and file_name is not null",
              Map.of("ids", ids)
            );
            changed = true;
            transaction.executeWithoutResult(status -> {
              db
                .getJdbcTemplate()
                .execute("select pg_advisory_xact_lock(74215003)");
              var p = Map.of("ids", ids);
              for (var slice : SLICES) db.update(
                "delete from " + slice.table + " where " + slice.where,
                p
              );
            });
            if (
              db.queryForObject(
                "select count(*) from mes_work_order where id in (:ids)",
                Map.of("ids", ids),
                Long.class
              ) !=
              0
            ) throw new IOException("清理后范围验证失败");
            for (var export : exports) {
              String filename = export.get("file_name").toString();
              if (
                !filename.matches("[a-f0-9-]{36}\\.csv")
              ) throw new IOException(
                "导出文件名不符合安全规则，系统保持维护状态"
              );
              Files.deleteIfExists(paths.resolve("exports/" + filename));
            }
            state.finish();
            changes.publish();
          } catch (Exception e) {
            if (!changed) state.finish();
            throw e;
          }
        }
      }
    );
    Files.delete(paths.resolve("maintenance/plans/" + planId + ".json"));
    return result;
  }
}
