package com.ruoyi.mes.production;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.ruoyi.common.exception.ServiceException;
import com.ruoyi.common.utils.SecurityUtils;
import com.ruoyi.mes.maintenance.MesMaintenanceState;
import com.ruoyi.mes.service.MesMasterService;
import com.ruoyi.mes.storage.MesDataPaths;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/** Durable batch export jobs. CSV is streamed from PostgreSQL, not a browser snapshot. */
@Service
public class MesReportService {

  private final MesProductionService p;
  private final MesMasterService master;
  private final MesDataPaths paths;
  private final MesMaintenanceState state;
  private final ObjectMapper json;
  private final TransactionTemplate tx;

  public MesReportService(
    MesProductionService p,
    MesMasterService master,
    MesDataPaths paths,
    MesMaintenanceState state,
    ObjectMapper json,
    PlatformTransactionManager manager
  ) {
    this.p = p;
    this.master = master;
    this.paths = paths;
    this.state = state;
    this.json = json;
    tx = new TransactionTemplate(manager);
  }

  public Object create(Map<String, Object> b) {
    return tx.execute(status -> {
      p.gate();
      UUID order = MesRecords.id(b.get("orderId")), batch = MesRecords.id(
        b.get("batchId")
      );
      master.visibleRow("orders", order);
      var batchRow = master.visibleRow("batches", batch);
      if (
        !order.equals(batchRow.get("work_order_id"))
      ) throw new ServiceException("批次不属于所选工单", 422);
      String kind = MesRecords.text(b, "kind", 16);
      if (
        !List.of("products", "passages", "tests").contains(kind)
      ) throw new ServiceException("导出类型无效", 422);
      String keyword = b.get("keyword") instanceof String s ? s : "", verdict =
        b.get("verdict") instanceof String s ? s : "";
      if (
        keyword.length() > 128 || !List.of("", "PASS", "FAIL").contains(verdict)
      ) throw new ServiceException("筛选条件无效", 422);
      UUID id = UUID.randomUUID();
      p
        .database()
        .update(
          "insert into mes_export_job(id,user_id,work_order_id,batch_id,kind,filter,status) values(:id,:u,:o,:b,:k,cast(:f as jsonb),'Queued')",
          Map.of(
            "id",
            id,
            "u",
            SecurityUtils.getUserId(),
            "o",
            order,
            "b",
            batch,
            "k",
            kind,
            "f",
            MesRecords.json(
              json,
              Map.of("keyword", keyword, "verdict", verdict)
            )
          )
        );
      return Map.of("id", id);
    });
  }

  public Object list() {
    return p.rows(
      "select j.*,o.code as order_code,b.code as batch_code from mes_export_job j join mes_work_order o on j.work_order_id=o.id join mes_batch b on j.batch_id=b.id where o.dept_id in (:departments) and (j.user_id=:u or :admin) order by j.at desc limit 200",
      Map.of(
        "departments",
        master.visibleDepartments(),
        "u",
        SecurityUtils.getUserId(),
        "admin",
        SecurityUtils.isAdmin(SecurityUtils.getUserId())
      )
    );
  }

  public Path file(UUID id) {
    var row = p.one(
      "select * from mes_export_job where id=:id",
      Map.of("id", id)
    );
    master.visibleRow("orders", (UUID) row.get("work_order_id"));
    if (
      !SecurityUtils.isAdmin(SecurityUtils.getUserId()) &&
      ((Number) row.get("user_id")).longValue() != SecurityUtils.getUserId()
    ) throw new ServiceException("未授权此导出", 403);
    if (!"Completed".equals(row.get("status"))) throw new ServiceException(
      "导出尚未完成",
      409
    );
    return paths.resolve("exports/" + id + ".csv");
  }

  private String csv(Object v) {
    String s = v == null ? "" : v.toString();
    if (!s.isEmpty() && "=+-@\t\r".indexOf(s.charAt(0)) >= 0) s = "'" + s;
    return "\"" + s.replace("\"", "\"\"") + "\"";
  }

  @Scheduled(fixedDelay = 1500, initialDelay = 3000)
  public void process() {
    if (state.active()) return;
    tx.executeWithoutResult(status -> {
      p
        .database()
        .getJdbcTemplate()
        .execute("select pg_advisory_xact_lock(74215003)");
      if (state.active()) return;
      var queued = p
        .database()
        .queryForList(
          "select * from mes_export_job where status='Queued' order by at limit 1 for update",
          Map.of()
        );
      if (queued.isEmpty()) return;
      var job = queued.get(0);
      UUID id = (UUID) job.get("id");
      Path temp = paths.resolve("exports/" + id + ".tmp");
      try {
        var filter = (Map<?, ?>) p.parse(job.get("filter"));
        String kind = job.get("kind").toString(), keyword =
          "%" + filter.get("keyword") + "%", verdict = filter
          .get("verdict")
          .toString();
        var params = Map.of(
          "b",
          job.get("batch_id"),
          "q",
          keyword,
          "v",
          verdict
        );
        String common =
          " from mes_unit u join mes_batch b on u.batch_id=b.id join mes_work_order o on b.work_order_id=o.id ";
        String where =
          " where b.id=:b and (u.main_code ilike :q or coalesce(u.cell_code,'') ilike :q or coalesce(u.sn_code,'') ilike :q or coalesce(u.pack_code,'') ilike :q)";
        String exists = verdict.isEmpty()
          ? ""
          : " and exists(select 1 from mes_test_task tt join mes_test_result rr on rr.test_task_id=tt.id where tt.unit_id=u.id and rr.verdict=:v)";
        String query =
          switch (kind) {
            case "products" -> "select o.code as work_order,b.code as batch,u.*" +
            common +
            where +
            exists +
            " order by u.main_code";
            case "passages" -> "select o.code as work_order,b.code as batch,u.main_code,p.name as process,s.code as station,a.at,a.sop_version_id,a.barcode_rule_id,a.user_id" +
            common +
            "join mes_passage a on a.unit_id=u.id join mes_process p on a.process_step_id=p.id join mes_station s on a.station_id=s.id" +
            where +
            exists +
            " order by u.main_code,p.sequence";
            default -> "select o.code as work_order,b.code as batch,u.main_code,u.cell_code,u.sn_code,u.pack_code,t.external_id as task_id,t.sequence as test_sequence,t.program_version,r.record_id,r.tested_at,r.received_at,r.verdict as result,i.name,i.value,i.unit,i.lower_limit,i.upper_limit,i.verdict as item_result,r.raw" +
            common +
            "join mes_test_task t on t.unit_id=u.id join mes_test_result r on r.test_task_id=t.id left join mes_test_item i on i.test_result_id=r.id" +
            where +
            (verdict.isEmpty() ? "" : " and r.verdict=:v") +
            " order by u.main_code,t.sequence,i.name";
          };
        Files.createDirectories(temp.getParent());
        try (var out = Files.newBufferedWriter(temp, StandardCharsets.UTF_8)) {
          out.write('\ufeff');
          boolean[] header = { false };
          p
            .database()
            .query(
              query,
              params,
              (org.springframework.jdbc.core.RowCallbackHandler) rs -> {
                try {
                  int columns = rs.getMetaData().getColumnCount();
                  if (!header[0]) {
                    for (int n = 1; n <= columns; n++) {
                      if (n > 1) out.write(',');
                      out.write(csv(rs.getMetaData().getColumnLabel(n)));
                    }
                    out.newLine();
                    header[0] = true;
                  }
                  for (int n = 1; n <= columns; n++) {
                    if (n > 1) out.write(',');
                    out.write(csv(rs.getObject(n)));
                  }
                  out.newLine();
                } catch (java.io.IOException e) {
                  throw new java.io.UncheckedIOException(e);
                }
              }
            );
        }
        Files.move(
          temp,
          paths.resolve("exports/" + id + ".csv"),
          StandardCopyOption.REPLACE_EXISTING
        );
        p
          .database()
          .update(
            "update mes_export_job set status='Completed',file_name=:f where id=:id",
            Map.of("f", id + ".csv", "id", id)
          );
      } catch (Exception e) {
        try {
          Files.deleteIfExists(temp);
        } catch (Exception ignored) {}
        p
          .database()
          .update(
            "update mes_export_job set status='Failed',error=:e where id=:id",
            Map.of("id", id, "e", "生成导出失败，请检查附件目录和数据库日志")
          );
      }
    });
  }
}
