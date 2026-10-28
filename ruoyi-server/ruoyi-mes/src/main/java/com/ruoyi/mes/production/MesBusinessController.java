package com.ruoyi.mes.production;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.ruoyi.common.annotation.Log;
import com.ruoyi.common.core.domain.AjaxResult;
import com.ruoyi.common.enums.BusinessType;
import com.ruoyi.common.exception.ServiceException;
import com.ruoyi.common.utils.SecurityUtils;
import com.ruoyi.mes.realtime.MesChanges;
import com.ruoyi.mes.service.MesMasterService;
import com.ruoyi.mes.storage.MesDataPaths;
import java.util.*;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

@RestController
public class MesBusinessController {

  private final MesProductionService p;
  private final MesMasterService master;
  private final MesChanges changes;
  private final ObjectMapper json;
  private final MesInstrumentService instruments;

  public MesBusinessController(
    MesProductionService p,
    MesMasterService master,
    MesChanges changes,
    ObjectMapper json,
    MesInstrumentService instruments
  ) {
    this.instruments = instruments;
    this.p = p;
    this.master = master;
    this.changes = changes;
    this.json = json;
  }

  @GetMapping("/mes/business/{module}")
  @PreAuthorize("@ss.hasPermi('mes:'+#module+':list')")
  public Object list(
    @PathVariable String module,
    @RequestParam(defaultValue = "") String keyword,
    @RequestParam(required = false) UUID orderId,
    @RequestParam(required = false) UUID batchId,
    @RequestParam(defaultValue = "") String verdict
  ) {
    if (
      !List.of("", "PASS", "FAIL").contains(verdict)
    ) throw new ServiceException("测试结果筛选无效", 422);
    var params = new HashMap<String, Object>();
    params.put("departments", master.visibleDepartments());
    params.put("q", "%" + keyword + "%");
    params.put("order", orderId);
    params.put("batch", batchId);
    params.put("verdict", verdict);
    Object data =
      switch (module) {
        case "barcodes" -> p.rules();
        case "grants" -> p.rows(
          "select g.user_id,g.station_id,u.user_name,s.code as station from mes_station_grant g join sys_user u on g.user_id=u.user_id join mes_station s on g.station_id=s.id where s.dept_id in (:departments) order by u.user_name,s.code",
          params
        );
        case "devices" -> p
          .rows(
            "select d.id,d.code,d.name,d.station_id,d.active,d.last_seen,d.protocol,d.program_version,d.required_items,s.code as station from mes_device d join mes_station s on d.station_id=s.id where s.dept_id in (:departments) order by d.code",
            params
          )
          .stream()
          .map(r -> {
            r.put("requiredItems", p.parse(r.get("requiredItems")));
            return r;
          })
          .toList();
        case "receipts" -> p.rows(
          "select r.*,d.code as device from mes_receipt r join mes_device d on r.device_id=d.id join mes_station s on d.station_id=s.id where s.dept_id in (:departments) and (r.record_id ilike :q or r.reason ilike :q) order by r.at desc limit 500",
          params
        );
        case "trace", "quality", "reports" -> p.rows(
          "select u.*,b.code as batch_code,b.work_order_id,o.code as order_code,o.line_id from mes_unit u join mes_batch b on u.batch_id=b.id join mes_work_order o on b.work_order_id=o.id where o.dept_id in (:departments) and (u.main_code ilike :q or coalesce(u.cell_code,'') ilike :q or coalesce(u.sn_code,'') ilike :q or coalesce(u.pack_code,'') ilike :q)" +
          (orderId == null ? "" : " and o.id=:order") +
          (batchId == null ? "" : " and b.id=:batch") +
          (verdict.isEmpty()
              ? ""
              : " and exists(select 1 from mes_test_task t join mes_test_result r on r.test_task_id=t.id where t.unit_id=u.id and r.verdict=:verdict)") +
          " order by u.created_at desc limit 500",
          params
        );
        case "messages" -> p.rows(
          "select m.*, (select count(*) from mes_message_ack a where a.message_id=m.id) as acknowledged from mes_message m left join mes_line l on m.line_id=l.id where m.line_id is null or l.dept_id in (:departments) order by m.at desc limit 200",
          params
        );
        case "protocols" -> instruments.protocols();
        default -> throw new ServiceException("未知业务模块", 404);
      };
    return AjaxResult.success(data);
  }

  @GetMapping("/mes/business/trace/{id}")
  @PreAuthorize(
    "@ss.hasPermi('mes:trace:list') or @ss.hasPermi('mes:quality:list') or @ss.hasPermi('mes:reports:list')"
  )
  public Object trace(@PathVariable UUID id) {
    var u = p.one(
      "select batch_id from mes_unit where id=:id",
      Map.of("id", id)
    );
    master.visibleRow("batches", (UUID) u.get("batch_id"));
    return AjaxResult.success(p.trace(id));
  }

  @GetMapping("/mes/business/options")
  @PreAuthorize(
    "@ss.hasPermi('mes:grants:list') or @ss.hasPermi('mes:devices:list') or @ss.hasPermi('mes:messages:list') or @ss.hasPermi('mes:reports:list')"
  )
  public Object options() {
    var params = Map.of("departments", master.visibleDepartments());
    return AjaxResult.success(
      Map.of(
        "protocols",
        instruments.protocols(),
        "users",
        p.rows(
          "select user_id as id,user_name as name from sys_user where dept_id in (:departments) and del_flag='0' order by user_name",
          params
        ),
        "stations",
        p.rows(
          "select id,code,process_step_id,line_id from mes_station where dept_id in (:departments) order by code",
          params
        ),
        "lines",
        p.rows(
          "select id,code,name from mes_line where dept_id in (:departments) order by code",
          params
        ),
        "orders",
        p.rows(
          "select id,code from mes_work_order where dept_id in (:departments) order by code",
          params
        ),
        "batches",
        p.rows(
          "select id,code,work_order_id from mes_batch where dept_id in (:departments) order by code",
          params
        )
      )
    );
  }

  @PostMapping("/mes/business/barcodes")
  @PreAuthorize("@ss.hasPermi('mes:barcodes:edit')")
  @Transactional
  @Log(title = "发布条码规则", businessType = BusinessType.UPDATE)
  public Object rule(@RequestBody Map<String, Object> b) {
    p.gate();
    String kind = MesRecords.text(b, "kind", 8), charset = MesRecords.text(
      b,
      "charset",
      12
    );
    if (
      !List.of("main", "cell", "sn", "pack").contains(kind) ||
      !List.of("alnum", "numeric", "printable").contains(charset) ||
      !(b.get("length") instanceof Number n) ||
      n.doubleValue() != n.intValue() ||
      n.intValue() < 1 ||
      n.intValue() > 128 ||
      !(b.get("prefixes") instanceof List<?> prefixes) ||
      prefixes.size() > 100
    ) throw new ServiceException("条码规则不合法", 422);
    for (var prefix : prefixes) if (
      !(prefix instanceof String s) ||
      s.isEmpty() ||
      s.length() > n.intValue() ||
      !(charset.equals("numeric")
          ? s.matches("[0-9]+")
          : charset.equals("alnum")
            ? s.matches("[A-Za-z0-9]+")
            : s.matches("[!-~]+"))
    ) throw new ServiceException("条码前缀与长度或字符规则不一致", 422);
    int version = p
      .database()
      .queryForObject(
        "select coalesce(max(version),0)+1 from mes_barcode_rule where kind=:k",
        Map.of("k", kind),
        Integer.class
      );
    p
      .database()
      .update(
        "insert into mes_barcode_rule(id,kind,version,prefixes,length,charset,published_by) values(:id,:k,:v,cast(:p as jsonb),:n,:c,:u)",
        Map.of(
          "id",
          UUID.randomUUID(),
          "k",
          kind,
          "v",
          version,
          "p",
          MesRecords.json(json, prefixes),
          "n",
          n.intValue(),
          "c",
          charset,
          "u",
          SecurityUtils.getUserId()
        )
      );
    changes.publish();
    return AjaxResult.success();
  }

  @PostMapping("/mes/business/grants")
  @PreAuthorize("@ss.hasPermi('mes:grants:edit')")
  @Transactional
  @Log(title = "修改工位授权", businessType = BusinessType.GRANT)
  public Object grant(@RequestBody Map<String, Object> b) {
    p.gate();
    UUID station = MesRecords.id(b.get("stationId"));
    master.visibleRow("stations", station);
    if (!(b.get("userId") instanceof Number user)) throw new ServiceException(
      "用户编号无效",
      422
    );
    var u = p.one(
      "select dept_id from sys_user where user_id=:u and del_flag='0'",
      Map.of("u", user.longValue())
    );
    if (
      !master
        .visibleDepartments()
        .contains(((Number) u.get("dept_id")).longValue())
    ) throw new ServiceException("用户不在授权数据范围", 403);
    if (Boolean.TRUE.equals(b.get("enabled"))) p
      .database()
      .update(
        "insert into mes_station_grant(user_id,station_id) values(:u,:s) on conflict do nothing",
        Map.of("u", user.longValue(), "s", station)
      );
    else p
      .database()
      .update(
        "delete from mes_station_grant where user_id=:u and station_id=:s",
        Map.of("u", user.longValue(), "s", station)
      );
    changes.publish();
    return AjaxResult.success();
  }

  @PostMapping("/mes/business/devices")
  @PreAuthorize("@ss.hasPermi('mes:devices:edit')")
  @Transactional
  @Log(
    title = "登记测试设备",
    businessType = BusinessType.INSERT,
    isSaveRequestData = false,
    isSaveResponseData = false
  )
  public Object device(@RequestBody Map<String, Object> b) {
    p.gate();
    UUID station = MesRecords.id(b.get("stationId"));
    var s = master.visibleRow("stations", station);
    var process = p.one(
      "select sequence from mes_process where id=:p",
      Map.of("p", s.get("process_step_id"))
    );
    if (
      ((Number) process.get("sequence")).intValue() != 3
    ) throw new ServiceException("测试仪必须绑定第三道测试工位", 422);
    String protocol = String.valueOf(b.getOrDefault("protocol", "generic-v1"));
    if (!instruments.supports(protocol)) throw new ServiceException(
      "此协议适配器尚未安装",
      422
    );
    String code = MesRecords.text(b, "code", 128), name = MesRecords.text(
      b,
      "name",
      128
    ), program = MesRecords.text(b, "programVersion", 128);
    if (
      !(b.get("requiredItems") instanceof List<?> items) ||
      items.isEmpty() ||
      items.size() > 200
    ) throw new ServiceException("请配置测试项目与限值", 422);
    Set<String> names = new HashSet<>();
    for (var item : items) {
      if (
        !(item instanceof Map<?, ?> m) ||
        !(m.get("name") instanceof String nm) ||
        nm.isBlank() ||
        nm.length() > 128 ||
        !names.add(nm) ||
        !(m.get("unit") instanceof String unit) ||
        unit.length() > 64
      ) throw new ServiceException("测试项目不合法或重复", 422);
      try {
        if (
          new java.math.BigDecimal(
            String.valueOf(m.get("lowerLimit"))
          ).compareTo(
            new java.math.BigDecimal(String.valueOf(m.get("upperLimit")))
          ) >
          0
        ) throw new Exception();
      } catch (Exception e) {
        throw new ServiceException("测试上下限不合法", 422);
      }
    }
    UUID id = UUID.randomUUID();
    byte[] secret = new byte[32];
    new java.security.SecureRandom().nextBytes(secret);
    String key = Base64.getUrlEncoder().withoutPadding().encodeToString(secret);
    p
      .database()
      .update(
        "insert into mes_device(id,code,name,station_id,key_hash,protocol,program_version,required_items) values(:id,:c,:n,:s,:h,:protocol,:v,cast(:i as jsonb))",
        Map.of(
          "id",
          id,
          "c",
          code,
          "n",
          name,
          "s",
          station,
          "h",
          MesDataPaths.sha256(
            key.getBytes(java.nio.charset.StandardCharsets.UTF_8)
          ),
          "v",
          program,
          "i",
          MesRecords.json(json, items),
          "protocol",
          protocol
        )
      );
    return AjaxResult.success(
      Map.of("id", id, "deviceKey", key, "notice", "凭证仅显示一次，请安全保存")
    );
  }

  @PutMapping("/mes/business/devices/{id}/active")
  @PreAuthorize("@ss.hasPermi('mes:devices:edit')")
  @Transactional
  @Log(title = "启停测试仪", businessType = BusinessType.UPDATE)
  public Object active(
    @PathVariable UUID id,
    @RequestBody Map<String, Object> b
  ) {
    p.gate();
    var d = p.one(
      "select station_id from mes_device where id=:id",
      Map.of("id", id)
    );
    master.visibleRow("stations", (UUID) d.get("station_id"));
    if (
      !(b.get("active") instanceof Boolean active)
    ) throw new ServiceException("状态无效", 422);
    p
      .database()
      .update(
        "update mes_device set active=:a where id=:id",
        Map.of("a", active, "id", id)
      );
    return AjaxResult.success();
  }

  @PostMapping("/mes/business/devices/{id}/rotate")
  @PreAuthorize("@ss.hasPermi('mes:devices:edit')")
  @Transactional
  @Log(
    title = "轮换设备凭证",
    businessType = BusinessType.UPDATE,
    isSaveResponseData = false
  )
  public Object rotate(@PathVariable UUID id) {
    p.gate();
    var d = p.one(
      "select station_id from mes_device where id=:id",
      Map.of("id", id)
    );
    master.visibleRow("stations", (UUID) d.get("station_id"));
    byte[] bytes = new byte[32];
    new java.security.SecureRandom().nextBytes(bytes);
    String key = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    p
      .database()
      .update(
        "update mes_device set key_hash=:h where id=:id",
        Map.of(
          "h",
          MesDataPaths.sha256(
            key.getBytes(java.nio.charset.StandardCharsets.UTF_8)
          ),
          "id",
          id
        )
      );
    return AjaxResult.success(Map.of("deviceKey", key));
  }

  @GetMapping("/mes/business/messages/{id}")
  @PreAuthorize("@ss.hasPermi('mes:messages:list')")
  public Object confirmations(@PathVariable UUID id) {
    var m = p.one("select * from mes_message where id=:id", Map.of("id", id));
    if (m.get("line_id") != null) master.visibleRow(
      "lines",
      (UUID) m.get("line_id")
    );
    else if (
      !SecurityUtils.isAdmin(SecurityUtils.getUserId())
    ) throw new ServiceException("全局确认记录仅管理员可查看", 403);
    return AjaxResult.success(
      Map.of(
        "message",
        MesRecords.external(m),
        "acknowledgements",
        p.rows(
          "select a.at,u.user_name,u.nick_name from mes_message_ack a join sys_user u on a.user_id=u.user_id where a.message_id=:id order by a.at",
          Map.of("id", id)
        )
      )
    );
  }

  @PostMapping("/mes/business/messages")
  @PreAuthorize("@ss.hasPermi('mes:messages:edit')")
  @Transactional
  @Log(title = "发布工位消息", businessType = BusinessType.INSERT)
  public Object message(@RequestBody Map<String, Object> b) {
    p.gate();
    UUID line = b.get("lineId") == null ? null : MesRecords.id(b.get("lineId"));
    if (line != null) master.visibleRow("lines", line);
    else if (
      !SecurityUtils.isAdmin(SecurityUtils.getUserId())
    ) throw new ServiceException("全局消息仅管理员可发布", 403);
    var params = new HashMap<String, Object>();
    params.put("id", UUID.randomUUID());
    params.put("t", MesRecords.text(b, "title", 256));
    params.put("body", MesRecords.text(b, "body", 16000));
    params.put("line", line);
    params.put("i", Boolean.TRUE.equals(b.get("important")));
    params.put("u", SecurityUtils.getUserId());
    p
      .database()
      .update(
        "insert into mes_message(id,title,body,line_id,important,created_by) values(:id,:t,:body,:line,:i,:u)",
        params
      );
    changes.publish();
    return AjaxResult.success();
  }

  @GetMapping("/api/messages")
  @PreAuthorize("@ss.hasPermi('mes:station:operate')")
  public Object inbox() {
    return p.rows(
      "select m.*,exists(select 1 from mes_message_ack a where a.message_id=m.id and a.user_id=:u) as acked from mes_message m where m.line_id is null or exists(select 1 from mes_station s join mes_station_grant g on s.id=g.station_id where g.user_id=:u and s.line_id=m.line_id) or :admin order by m.at desc limit 200",
      Map.of(
        "u",
        SecurityUtils.getUserId(),
        "admin",
        SecurityUtils.isAdmin(SecurityUtils.getUserId())
      )
    );
  }

  @PostMapping("/api/messages/{id}/ack")
  @PreAuthorize("@ss.hasPermi('mes:station:operate')")
  @Transactional
  public Object ack(@PathVariable UUID id) {
    p.gate();
    var allowed =
      ((List<Map<String, Object>>) inbox()).stream()
        .anyMatch(m -> id.equals(m.get("id")));
    if (!allowed) throw new ServiceException("消息不存在或未授权", 403);
    p
      .database()
      .update(
        "insert into mes_message_ack(message_id,user_id) values(:m,:u) on conflict do nothing",
        Map.of("m", id, "u", SecurityUtils.getUserId())
      );
    return Map.of("acked", true);
  }
}
