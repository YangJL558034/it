package com.ruoyi.mes.production;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.ruoyi.common.exception.ServiceException;
import com.ruoyi.mes.storage.MesDataPaths;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.*;
import org.springframework.stereotype.Component;

/** Only the documented generic protocol. No guessed manufacturer fields or routes. */
@Component
public class MesInstrumentDomainService {

  private final MesProductionService production;
  private final ObjectMapper json;

  public MesInstrumentDomainService(
    MesProductionService production,
    ObjectMapper json
  ) {
    this.production = production;
    this.json = json;
  }

  public Map<String, Object> execute(
    String operation,
    Map<String, Object> d,
    Map<String, Object> b,
    String raw
  ) {
    if (
      ((Number) d.get("sequence")).intValue() != 3
    ) throw new ServiceException("设备没有绑定仪器测试工位", 422);
    if (
      !d.get("code").equals(b.get("deviceCode")) ||
      !d.get("station_id").equals(MesRecords.id(b.get("stationId")))
    ) throw new ServiceException("设备编号或工位关联不符", 422);
    production
      .database()
      .update(
        "update mes_device set last_seen=now() where id=:id",
        Map.of("id", d.get("id"))
      );
    production
      .database()
      .update(
        "update mes_station set last_seen=now() where id=:id",
        Map.of("id", d.get("station_id"))
      );
    if (operation.equals("heartbeat")) return Map.of("online", true);
    if (operation.equals("orders")) return Map.of(
      "orders",
      production.rows(
        "select o.id,o.code,b.id as batch_id,b.code as batch_code from mes_work_order o join mes_batch b on b.work_order_id=o.id where o.line_id=:l and o.status='Open'",
        Map.of("l", d.get("line_id"))
      )
    );
    if (operation.equals("user")) {
      String account = MesRecords.text(b, "account", 30);
      var users = production.rows(
        "select u.user_id as id,u.user_name as account,u.nick_name as name from sys_user u where u.user_name=:a and u.status='0' and u.del_flag='0' and exists(select 1 from mes_station_grant g where g.user_id=u.user_id and g.station_id=:s)",
        Map.of("a", account, "s", d.get("station_id"))
      );
      if (users.isEmpty()) throw new ServiceException("用户未授权此工位", 403);
      var result = new LinkedHashMap<String, Object>(users.get(0));
      result.put("stationId", d.get("station_id"));
      return result;
    }
    String external = MesRecords.text(b, "taskId", 128), main = MesRecords.text(
      b,
      "mainCode",
      128
    );
    UUID batch = MesRecords.id(b.get("batchId"));
    if (operation.equals("check")) {
      production.rule("main", main);
      var unit = production.one(
        "select u.*,o.status,o.line_id from mes_unit u join mes_batch b on u.batch_id=b.id join mes_work_order o on b.work_order_id=o.id where u.main_code=:c",
        Map.of("c", main)
      );
      UUID uid = (UUID) unit.get("id");
      if (
        !batch.equals(unit.get("batch_id")) ||
        !d.get("line_id").equals(unit.get("line_id")) ||
        !"Open".equals(unit.get("status"))
      ) throw new ServiceException("主码与当前工单、批次或产线不匹配", 422);
      production.requires(uid, 1);
      production.requires(uid, 2);
      if (unit.get("cell_code") == null) throw new ServiceException(
        "电芯尚未绑定",
        422
      );
      if (unit.get("sn_code") != null) throw new ServiceException(
        "产品已进入后续工序，禁止复测",
        422
      );
      if (
        !d.get("program_version").equals(b.get("programVersion"))
      ) throw new ServiceException("测试程序版本不符", 422);
      var existing = production
        .database()
        .queryForList(
          "select * from mes_test_task where device_id=:d and external_id=:t",
          Map.of("d", d.get("id"), "t", external)
        );
      if (!existing.isEmpty()) {
        var task = existing.get(0);
        if (
          !uid.equals(task.get("unit_id")) ||
          !batch.equals(task.get("batch_id"))
        ) throw new ServiceException("任务编号重复用于其他产品", 409);
        return Map.of(
          "taskId",
          task.get("id"),
          "duplicate",
          true,
          "status",
          task.get("status")
        );
      }
      if (
        production
          .database()
          .queryForObject(
            "select count(*) from mes_test_task where unit_id=:u and status='Pending'",
            Map.of("u", uid),
            Long.class
          ) >
        0
      ) throw new ServiceException("产品已有未完成测试任务", 409);
      UUID id = UUID.randomUUID();
      var p = new HashMap<String, Object>();
      p.put("id", id);
      p.put("u", uid);
      p.put("d", d.get("id"));
      p.put("s", d.get("station_id"));
      p.put("b", batch);
      p.put("t", external);
      p.put("v", d.get("program_version"));
      p.put("spec", d.get("required_items").toString());
      p.put("sop", production.sop((UUID) d.get("process_step_id")));
      production
        .database()
        .update(
          "insert into mes_test_task(id,unit_id,device_id,station_id,batch_id,external_id,program_version,specification,sop_version_id,status) values(:id,:u,:d,:s,:b,:t,:v,cast(:spec as jsonb),:sop,'Pending')",
          p
        );
      return Map.of("taskId", id, "duplicate", false, "status", "Pending");
    }
    if (!operation.equals("results")) throw new ServiceException(
      "未知协议操作",
      404
    );
    String record = MesRecords.text(b, "recordId", 128), verdict =
      MesRecords.text(b, "verdict", 4);
    if (!List.of("PASS", "FAIL").contains(verdict)) throw new ServiceException(
      "测试结论必须是 PASS 或 FAIL",
      422
    );
    String hash = MesDataPaths.sha256(
      MesRecords.json(json, MesRecords.canonical(b)).getBytes(
        java.nio.charset.StandardCharsets.UTF_8
      )
    );
    var retries = production
      .database()
      .queryForList(
        "select id,payload_hash from mes_test_result where device_id=:d and record_id=:r",
        Map.of("d", d.get("id"), "r", record)
      );
    if (!retries.isEmpty()) {
      if (
        !hash.equals(retries.get(0).get("payload_hash"))
      ) throw new ServiceException("重复记录编号的内容发生变化", 409);
      return Map.of("id", retries.get(0).get("id"), "duplicate", true);
    }
    var t = production.one(
      "select t.*,u.main_code,u.cell_code,u.sn_code,o.line_id,o.status as order_status from mes_test_task t join mes_unit u on t.unit_id=u.id join mes_batch b on t.batch_id=b.id join mes_work_order o on b.work_order_id=o.id where t.device_id=:d and t.external_id=:t",
      Map.of("d", d.get("id"), "t", external)
    );
    if (
      !main.equals(t.get("main_code")) ||
      !batch.equals(t.get("batch_id")) ||
      !d.get("station_id").equals(t.get("station_id")) ||
      !d.get("line_id").equals(t.get("line_id")) ||
      !"Open".equals(t.get("order_status"))
    ) throw new ServiceException("测试任务与产品、工单、批次或工位不符", 422);
    if (
      !"Pending".equals(t.get("status")) || t.get("sn_code") != null
    ) throw new ServiceException("测试任务已完成或产品已进入后工序", 409);
    if (t.get("cell_code") == null) throw new ServiceException(
      "电芯未绑定",
      422
    );
    production.requires((UUID) t.get("unit_id"), 2);
    if (
      !t.get("program_version").equals(b.get("programVersion"))
    ) throw new ServiceException("程序版本与测试任务不符", 422);
    Instant at = Instant.parse(MesRecords.text(b, "testedAt", 64));
    if (
      at.isAfter(Instant.now().plusSeconds(300)) ||
      at.isBefore(
        ((java.sql.Timestamp) t.get("created_at")).toInstant().minusSeconds(300)
      )
    ) throw new ServiceException("测试时间超出任务允许范围", 422);
    var specification = (List<?>) production.parse(t.get("specification"));
    if (
      !(b.get("items") instanceof List<?> items) ||
      items.size() != specification.size() ||
      items.isEmpty()
    ) throw new ServiceException("测试参数缺失或项目数不符", 422);
    var checked = new ArrayList<Map<String, Object>>();
    boolean allPass = true;
    Set<String> names = new HashSet<>();
    for (Object item : items) {
      if (!(item instanceof Map<?, ?> input)) throw new ServiceException(
        "测试参数格式无效",
        422
      );
      String name = String.valueOf(input.get("name"));
      if (!names.add(name)) throw new ServiceException("测试项目重复", 422);
      var spec = (Map<?, ?>) specification
        .stream()
        .filter(v -> v instanceof Map<?, ?> m && name.equals(m.get("name")))
        .findFirst()
        .orElseThrow(() -> new ServiceException("未知测试项目", 422));
      BigDecimal value = new BigDecimal(
        String.valueOf(input.get("value"))
      ), lower = new BigDecimal(
        String.valueOf(
          (spec.containsKey("lowerLimit")
              ? spec.get("lowerLimit")
              : spec.get("lower"))
        )
      ), upper = new BigDecimal(
        String.valueOf(
          (spec.containsKey("upperLimit")
              ? spec.get("upperLimit")
              : spec.get("upper"))
        )
      );
      if (
        !Objects.equals(spec.get("unit"), input.get("unit")) ||
        lower.compareTo(
          new BigDecimal(String.valueOf(input.get("lowerLimit")))
        ) !=
        0 ||
        upper.compareTo(
          new BigDecimal(String.valueOf(input.get("upperLimit")))
        ) !=
        0
      ) throw new ServiceException("参数单位或上下限与任务标准不符", 422);
      boolean pass = value.compareTo(lower) >= 0 && value.compareTo(upper) <= 0;
      if (
        !Objects.equals(input.get("verdict"), pass ? "PASS" : "FAIL")
      ) throw new ServiceException("项目结论与实际数值不一致", 422);
      allPass &= pass;
      checked.add(
        Map.of(
          "name",
          name,
          "value",
          value,
          "unit",
          input.get("unit"),
          "low",
          lower,
          "high",
          upper,
          "verdict",
          pass ? "PASS" : "FAIL"
        )
      );
    }
    if (!verdict.equals(allPass ? "PASS" : "FAIL")) throw new ServiceException(
      "含不合格参数的测试不能判为 PASS",
      422
    );
    UUID id = UUID.randomUUID();
    production
      .database()
      .update(
        "insert into mes_test_result(id,test_task_id,device_id,record_id,tested_at,verdict,payload_hash,raw) values(:id,:t,:d,:r,:at,:v,:h,:raw)",
        Map.of(
          "id",
          id,
          "t",
          t.get("id"),
          "d",
          d.get("id"),
          "r",
          record,
          "at",
          java.sql.Timestamp.from(at),
          "v",
          verdict,
          "h",
          hash,
          "raw",
          raw
        )
      );
    for (var item : checked) {
      var p = new HashMap<String, Object>(item);
      p.put("id", UUID.randomUUID());
      p.put("r", id);
      production
        .database()
        .update(
          "insert into mes_test_item(id,test_result_id,name,value,unit,lower_limit,upper_limit,verdict) values(:id,:r,:name,:value,:unit,:low,:high,:verdict)",
          p
        );
    }
    production
      .database()
      .update(
        "update mes_test_task set status='Completed' where id=:t",
        Map.of("t", t.get("id"))
      );
    if (verdict.equals("PASS")) production.passage(
      (UUID) t.get("unit_id"),
      (UUID) d.get("station_id"),
      (UUID) d.get("process_step_id"),
      (UUID) t.get("sop_version_id"),
      null,
      null
    );
    return Map.of("id", id, "duplicate", false, "verdict", verdict);
  }
}
