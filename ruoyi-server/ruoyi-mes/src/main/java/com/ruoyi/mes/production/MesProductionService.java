package com.ruoyi.mes.production;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.ruoyi.common.exception.ServiceException;
import com.ruoyi.common.utils.SecurityUtils;
import com.ruoyi.mes.maintenance.MesMaintenanceState;
import com.ruoyi.mes.realtime.MesChanges;
import com.ruoyi.mes.service.MesMasterService;
import com.ruoyi.mes.storage.MesDataPaths;
import java.time.Instant;
import java.util.*;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class MesProductionService {

  public final NamedParameterJdbcTemplate db;
  private final MesMasterService master;
  private final MesMaintenanceState maintenance;
  private final MesChanges changes;
  private final ObjectMapper json;

  public MesProductionService(
    NamedParameterJdbcTemplate db,
    MesMasterService master,
    MesMaintenanceState maintenance,
    MesChanges changes,
    ObjectMapper json
  ) {
    this.db = db;
    this.master = master;
    this.maintenance = maintenance;
    this.changes = changes;
    this.json = json;
  }

  public NamedParameterJdbcTemplate database() {
    return db;
  }

  public void gate() {
    db.getJdbcTemplate().execute("select pg_advisory_xact_lock(74215003)");
    maintenance.assertWritable();
  }

  public List<Map<String, Object>> rows(String sql, Map<String, ?> p) {
    return db.queryForList(sql, p).stream().map(MesRecords::external).toList();
  }

  public Map<String, Object> one(String sql, Map<String, ?> p) {
    var r = db.queryForList(sql, p);
    if (r.isEmpty()) throw new ServiceException("关联记录不存在", 422);
    return r.get(0);
  }

  public Object parse(Object value) {
    try {
      return json.readValue(value.toString(), Object.class);
    } catch (Exception e) {
      throw new ServiceException("存储数据格式错误", 500);
    }
  }

  public Map<String, Object> station(UUID id) {
    var row = master.visibleRow("stations", id);
    if (
      !SecurityUtils.isAdmin(SecurityUtils.getUserId()) &&
      db.queryForObject(
        "select count(*) from mes_station_grant where user_id=:u and station_id=:s",
        Map.of("u", SecurityUtils.getUserId(), "s", id),
        Long.class
      ) ==
      0
    ) throw new ServiceException("未获此工位授权", 403);
    return row;
  }

  public Map<String, Object> batch(UUID id, Map<String, Object> station) {
    var b = master.visibleRow("batches", id);
    var o = master.visibleRow("orders", (UUID) b.get("work_order_id"));
    if (
      !o.get("line_id").equals(station.get("line_id"))
    ) throw new ServiceException("工单与工位不属于同一产线", 422);
    if (!"Open".equals(o.get("status"))) throw new ServiceException(
      "当前工单未开放生产",
      422
    );
    return b;
  }

  public List<Map<String, Object>> rules() {
    var result = rows(
      "select distinct on(kind) * from mes_barcode_rule order by kind,version desc",
      Map.of()
    );
    for (var r : result) r.put("prefixes", parse(r.get("prefixes")));
    return result;
  }

  public Map<String, Object> rule(String kind, String code) {
    var rule = rules()
      .stream()
      .filter(r -> kind.equals(r.get("kind")))
      .findFirst()
      .orElseThrow(() ->
        new ServiceException("尚未发布" + kind + "条码规则", 422)
      );
    int length = ((Number) rule.get("length")).intValue();
    if (code.length() != length) throw new ServiceException(
      "条码长度应为 " + length + " 位，当前 " + code.length() + " 位",
      422
    );
    var prefixes = (List<?>) rule.get("prefixes");
    if (
      !prefixes.isEmpty() &&
      prefixes.stream().noneMatch(p -> code.startsWith(p.toString()))
    ) throw new ServiceException("条码前缀不符合已发布规则", 422);
    String charset = rule.get("charset").toString();
    if (
      !(charset.equals("numeric")
          ? code.matches("[0-9]+")
          : charset.equals("alnum")
            ? code.matches("[A-Za-z0-9]+")
            : code.matches("[!-~]+"))
    ) throw new ServiceException("条码字符类型不符合规则", 422);
    return rule;
  }

  public UUID sop(UUID process) {
    var list = db.queryForList(
      "select id from mes_sop_version where process_step_id=:p and status='Published'",
      Map.of("p", process)
    );
    return list.isEmpty() ? null : (UUID) list.get(0).get("id");
  }

  public void passage(
    UUID unit,
    UUID station,
    UUID process,
    UUID sop,
    UUID rule,
    Long user
  ) {
    var p = new HashMap<String, Object>();
    p.put("id", UUID.randomUUID());
    p.put("u", unit);
    p.put("s", station);
    p.put("p", process);
    p.put("sop", sop);
    p.put("r", rule);
    p.put("user", user);
    db.update(
      "insert into mes_passage(id,unit_id,station_id,process_step_id,user_id,sop_version_id,barcode_rule_id) values(:id,:u,:s,:p,:user,:sop,:r) on conflict(unit_id,process_step_id) do nothing",
      p
    );
  }

  public void requires(UUID unit, int sequence) {
    long count = db.queryForObject(
      "select count(*) from mes_passage a join mes_process p on a.process_step_id=p.id where a.unit_id=:u and p.sequence=:n",
      Map.of("u", unit, "n", sequence),
      Long.class
    );
    if (count == 0) throw new ServiceException(
      "前序工序 " + sequence + " 尚未完成，禁止跳站",
      422
    );
  }

  public void passed(UUID unit) {
    var latest = db.queryForList(
      "select t.status,r.verdict from mes_test_task t left join mes_test_result r on r.test_task_id=t.id where t.unit_id=:u order by t.sequence desc limit 1",
      Map.of("u", unit)
    );
    if (
      latest.isEmpty() ||
      !"Completed".equals(latest.get(0).get("status")) ||
      !"PASS".equals(latest.get(0).get("verdict"))
    ) throw new ServiceException(
      "最新有效测试未通过或复测尚未完成，禁止放行",
      422
    );
  }

  public Map<String, Object> trace(UUID unit) {
    var result = MesRecords.external(
      one(
        "select u.*,b.code as batch_code,b.work_order_id,o.code as order_code,o.line_id from mes_unit u join mes_batch b on u.batch_id=b.id join mes_work_order o on b.work_order_id=o.id where u.id=:u",
        Map.of("u", unit)
      )
    );
    result.put(
      "passages",
      rows(
        "select a.*,p.name as process,s.code as station from mes_passage a join mes_process p on a.process_step_id=p.id join mes_station s on a.station_id=s.id where a.unit_id=:u order by p.sequence",
        Map.of("u", unit)
      )
    );
    result.put("tests", tests(null, null, unit));
    return result;
  }

  public List<Map<String, Object>> tests(UUID station, UUID batch, UUID unit) {
    var p = new HashMap<String, Object>();
    p.put("s", station);
    p.put("b", batch);
    p.put("u", unit);
    String where =
      " where 1=1" +
      (station == null ? "" : " and t.station_id=:s") +
      (batch == null ? "" : " and t.batch_id=:b") +
      (unit == null ? "" : " and t.unit_id=:u");
    var result = rows(
      "select r.*,t.unit_id,t.station_id,t.batch_id,t.program_version,t.external_id as task_id,u.main_code,d.code as device from mes_test_result r join mes_test_task t on r.test_task_id=t.id join mes_unit u on t.unit_id=u.id join mes_device d on t.device_id=d.id" +
      where +
      " order by r.received_at desc limit 200",
      p
    );
    for (var r : result) r.put(
      "items",
      rows(
        "select * from mes_test_item where test_result_id=:id order by name",
        Map.of("id", r.get("id"))
      )
    );
    return result;
  }

  public Map<String, Object> options() {
    var p = Map.of(
      "depts",
      master.visibleDepartments(),
      "user",
      SecurityUtils.getUserId()
    );
    boolean admin = SecurityUtils.isAdmin(SecurityUtils.getUserId());
    return Map.of(
      "stations",
      rows(
        "select * from mes_station s where dept_id in (:depts)" +
        (admin
            ? ""
            : " and exists(select 1 from mes_station_grant g where g.station_id=s.id and g.user_id=:user)") +
        " order by code",
        p
      ),
      "orders",
      rows(
        "select * from mes_work_order where dept_id in (:depts) and status='Open' order by code",
        p
      ),
      "batches",
      rows(
        "select * from mes_batch where dept_id in (:depts) order by code",
        p
      ),
      "processes",
      master.processes()
    );
  }

  public Map<String, Object> summary(UUID stationId, UUID batchId) {
    var s = station(stationId);
    var b = batch(batchId, s);
    var o = master.visibleRow("orders", (UUID) b.get("work_order_id"));
    var p = Map.of(
      "s",
      stationId,
      "b",
      batchId,
      "u",
      SecurityUtils.getUserId(),
      "p",
      s.get("process_step_id")
    );
    var contexts = rows(
      "select * from mes_scan_context where station_id=:s and batch_id=:b and user_id=:u and expires>now()",
      p
    );
    long completed = db.queryForObject(
      "select count(*) from mes_passage a join mes_unit u on a.unit_id=u.id where u.batch_id=:b and a.station_id=:s",
      p,
      Long.class
    );
    long batchDone = db.queryForObject(
      "select count(*) from mes_passage a join mes_unit u on a.unit_id=u.id where u.batch_id=:b and a.process_step_id=:p",
      p,
      Long.class
    );
    return Map.of(
      "order",
      MesRecords.external(o),
      "batch",
      MesRecords.external(b),
      "completed",
      completed,
      "remaining",
      Math.max(0, ((Number) b.get("plan")).longValue() - batchDone),
      "context",
      contexts.isEmpty() ? Map.of() : contexts.get(0),
      "recent",
      rows(
        "select a.at,u.main_code,u.cell_code,u.sn_code,u.pack_code from mes_passage a join mes_unit u on a.unit_id=u.id where a.station_id=:s and u.batch_id=:b order by a.at desc limit 20",
        p
      )
    );
  }

  @Transactional
  public Map<String, Object> reset(UUID stationId, UUID batchId) {
    gate();
    var s = station(stationId);
    batch(batchId, s);
    db.update(
      "delete from mes_scan_context where user_id=:u and station_id=:s",
      Map.of("u", SecurityUtils.getUserId(), "s", stationId)
    );
    return Map.of("reset", true);
  }

  @Transactional
  public Map<String, Object> heartbeat(UUID stationId, UUID batchId) {
    gate();
    var s = station(stationId);
    batch(batchId, s);
    db.update(
      "update mes_station set last_seen=now() where id=:s",
      Map.of("s", stationId)
    );
    return Map.of("online", true);
  }

  private void bind(String kind, String code, UUID unit) {
    var used = db.queryForList(
      "select * from mes_barcode where code=:c",
      Map.of("c", code)
    );
    if (!used.isEmpty()) throw new ServiceException(
      "条码已被绑定，禁止重复或跨工单绑定",
      409
    );
    db.update(
      "insert into mes_barcode(code,kind,unit_id) values(:c,:k,:u)",
      Map.of("c", code, "k", kind, "u", unit)
    );
    String column =
      switch (kind) {
        case "cell" -> "cell_code";
        case "sn" -> "sn_code";
        case "pack" -> "pack_code";
        default -> throw new ServiceException("条码类型不合法", 422);
      };
    db.update(
      "update mes_unit set " + column + "=:c where id=:u",
      Map.of("c", code, "u", unit)
    );
  }

  @Transactional
  public Map<String, Object> scan(Map<String, Object> body) {
    gate();
    long user = SecurityUtils.getUserId();
    UUID request = MesRecords.id(body.get("requestId")), stationId =
      MesRecords.id(body.get("stationId")), batchId = MesRecords.id(
      body.get("batchId")
    );
    String code = MesRecords.text(body, "code", 128);
    var s = station(stationId);
    var b = batch(batchId, s);
    UUID process = (UUID) s.get("process_step_id");
    int sequence =
      ((Number) one(
          "select sequence from mes_process where id=:id",
          Map.of("id", process)
        ).get("sequence")).intValue();
    if (sequence == 3) throw new ServiceException(
      "测试工位只接收仪器上传，不能手动扫码过站",
      422
    );
    var canonical = new TreeMap<String, Object>(body);
    String hash = MesDataPaths.sha256(
      MesRecords.json(json, canonical).getBytes(
        java.nio.charset.StandardCharsets.UTF_8
      )
    );
    var requestParams = Map.of("u", user, "r", request);
    var previous = db.queryForList(
      "select * from mes_scan_request where user_id=:u and request_id=:r",
      requestParams
    );
    if (!previous.isEmpty()) {
      if (!hash.equals(previous.get(0).get("hash"))) throw new ServiceException(
        "同一请求编号不能用于不同扫码内容",
        409
      );
      return (Map<String, Object>) parse(previous.get(0).get("response"));
    }
    UUID currentSop = sop(process), usedSop = body.get("sopVersionId") == null
      ? null
      : MesRecords.id(body.get("sopVersionId"));
    if (!Objects.equals(currentSop, usedSop)) throw new ServiceException(
      "SOP 已更新，请加载当前已发布版本后扫码",
      409
    );
    var p = Map.of("u", user, "s", stationId);
    var contexts = db.queryForList(
      "select * from mes_scan_context where user_id=:u and station_id=:s and expires>now()",
      p
    );
    Map<String, Object> context = contexts.isEmpty() ? null : contexts.get(0);
    if (
      context != null && !batchId.equals(context.get("batch_id"))
    ) throw new ServiceException("扫描上下文属于另一批次，请切换工位重置", 409);
    UUID unit = null;
    boolean duplicate = false;
    String waiting = sequence == 5 ? "SN" : "主码", message;
    if (sequence == 1) {
      var r = rule("main", code);
      var existing = db.queryForList(
        "select * from mes_unit where main_code=:c",
        Map.of("c", code)
      );
      if (!existing.isEmpty()) {
        if (
          !batchId.equals(existing.get(0).get("batch_id"))
        ) throw new ServiceException("主码已关联其他工单或批次", 409);
        unit = (UUID) existing.get(0).get("id");
        duplicate = true;
        message = "主码重复扫描，数量未增加";
      } else {
        // An imported master-data batch with unmigrated history must never be silently reused.
        if (
          db.queryForObject(
            "select count(*) from mes_legacy_guard where resource='batches' and row_id=:b",
            Map.of("b", batchId),
            Long.class
          ) >
          0
        ) throw new ServiceException(
          "此批次的历史记录尚未迁移完成，暂禁止新增过站",
          409
        );
        long count = db.queryForObject(
          "select count(*) from mes_unit where batch_id=:b",
          Map.of("b", batchId),
          Long.class
        );
        if (
          count >= ((Number) b.get("plan")).longValue()
        ) throw new ServiceException("已达到批次计划数量", 409);
        if (
          db.queryForObject(
            "select count(*) from mes_barcode where code=:c",
            Map.of("c", code),
            Long.class
          ) >
          0
        ) throw new ServiceException("此条码已作为其他类型绑定", 409);
        unit = UUID.randomUUID();
        db.update(
          "insert into mes_unit(id,batch_id,main_code) values(:id,:b,:c)",
          Map.of("id", unit, "b", batchId, "c", code)
        );
        db.update(
          "insert into mes_barcode(code,kind,unit_id) values(:c,'main',:id)",
          Map.of("c", code, "id", unit)
        );
        passage(unit, stationId, process, usedSop, (UUID) r.get("id"), user);
        message = "主码过站成功";
      }
    } else if (context == null) {
      String kind = sequence == 5 ? "sn" : "main";
      rule(kind, code);
      var barcode = one(
        "select * from mes_barcode where code=:c and kind=:k",
        Map.of("c", code, "k", kind)
      );
      unit = (UUID) barcode.get("unit_id");
      var product = one(
        "select * from mes_unit where id=:u",
        Map.of("u", unit)
      );
      if (!batchId.equals(product.get("batch_id"))) throw new ServiceException(
        "条码不属于当前工单和批次",
        409
      );
      requires(unit, sequence - 1);
      if (sequence >= 4) passed(unit);
      String field = sequence == 2
        ? "cell_code"
        : sequence == 4 ? "sn_code" : "pack_code";
      if (product.get(field) != null) {
        duplicate = true;
        message = "本工序已完成，重复扫描不会增加数量";
      } else {
        db.update(
          "insert into mes_scan_context(id,user_id,station_id,batch_id,unit_id,expires) values(:id,:u,:s,:b,:unit,now()+interval '10 minutes') on conflict(user_id,station_id) do update set batch_id=excluded.batch_id,unit_id=excluded.unit_id,expires=excluded.expires",
          Map.of(
            "id",
            UUID.randomUUID(),
            "u",
            user,
            "s",
            stationId,
            "b",
            batchId,
            "unit",
            unit
          )
        );
        waiting = sequence == 2 ? "电芯码" : sequence == 4 ? "SN" : "包装码";
        message = "请扫描" + waiting;
      }
    } else {
      unit = (UUID) context.get("unit_id");
      var existingUnit = one(
        "select * from mes_unit where id=:u",
        Map.of("u", unit)
      );
      String field = sequence == 2
        ? "cell_code"
        : sequence == 4 ? "sn_code" : "pack_code";
      if (existingUnit.get(field) != null) throw new ServiceException(
        "本产品已被另一操作员完成绑定，请重置后扫描下一件",
        409
      );
      String kind = sequence == 2 ? "cell" : sequence == 4 ? "sn" : "pack";
      var r = rule(kind, code);
      requires(unit, sequence - 1);
      if (sequence >= 4) passed(unit);
      bind(kind, code, unit);
      passage(unit, stationId, process, usedSop, (UUID) r.get("id"), user);
      db.update(
        "delete from mes_scan_context where user_id=:u and station_id=:s",
        p
      );
      message = "绑定成功";
    }
    var result = Map.<String, Object>of(
      "waiting",
      waiting,
      "message",
      message,
      "duplicate",
      duplicate,
      "trace",
      trace(unit)
    );
    db.update(
      "insert into mes_scan_request(user_id,request_id,batch_id,hash,response) values(:u,:r,:b,:h,cast(:response as jsonb))",
      Map.of(
        "u",
        user,
        "r",
        request,
        "b",
        batchId,
        "h",
        hash,
        "response",
        MesRecords.json(json, result)
      )
    );
    changes.publish();
    return result;
  }
}
