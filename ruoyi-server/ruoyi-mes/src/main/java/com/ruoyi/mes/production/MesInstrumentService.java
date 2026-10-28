package com.ruoyi.mes.production;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.ruoyi.common.exception.ServiceException;
import com.ruoyi.mes.realtime.MesChanges;
import com.ruoyi.mes.storage.MesDataPaths;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/** Generic HTTP adapter. Manufacturer routes must be supplied by a separately registered adapter. */
@Service
public class MesInstrumentService {

  private final MesProductionService production;
  private final TransactionTemplate transaction;
  private final ObjectMapper json;
  private final MesChanges changes;
  private final MesInstrumentDomainService domain;
  private final Map<String, MesInstrumentAdapter> adapters = new HashMap<>();

  public MesInstrumentService(
    MesProductionService production,
    PlatformTransactionManager manager,
    ObjectMapper json,
    MesChanges changes,
    List<MesInstrumentAdapter> installed,
    MesInstrumentDomainService domain
  ) {
    this.domain = domain;
    this.production = production;
    this.transaction = new TransactionTemplate(manager);
    this.json = json;
    this.changes = changes;
    for (var adapter : installed) if (
      adapters.putIfAbsent(adapter.protocol(), adapter) != null
    ) throw new IllegalStateException("Duplicate MES adapter");
  }

  public Map<String, Object> receive(
    String operation,
    String key,
    Map<String, Object> body
  ) {
    return receive(operation, key, body, false);
  }

  public Map<String, Object> receive(
    String operation,
    String key,
    Map<String, Object> body,
    boolean legacy
  ) {
    return receiveInternal(
      operation,
      key,
      body,
      legacy,
      MesRecords.json(json, body),
      false,
      "generic-v1"
    );
  }

  public List<Map<String, Object>> protocols() {
    return adapters
      .keySet()
      .stream()
      .sorted()
      .map(code ->
        Map.<String, Object>of(
          "code",
          code,
          "name",
          code.equals("generic-v1") ? "通用 HTTP v1" : code,
          "status",
          "已安装适配器；真实设备需单独验收"
        )
      )
      .toList();
  }

  public boolean supports(String protocol) {
    return adapters.containsKey(protocol);
  }

  public Map<String, Object> receiveRaw(
    String operation,
    String key,
    String raw,
    boolean legacy
  ) {
    return receiveRaw(operation, key, raw, legacy, "generic-v1");
  }

  public Map<String, Object> receiveRaw(
    String operation,
    String key,
    String raw,
    boolean legacy,
    String expectedProtocol
  ) {
    if (
      raw == null || raw.length() > 2 * 1024 * 1024
    ) throw new ServiceException("上传报文超过 2 MB 或为空", 422);
    try {
      var body = json.readValue(
        raw,
        new com.fasterxml.jackson.core.type.TypeReference<
          LinkedHashMap<String, Object>
        >() {}
      );
      return receiveInternal(
        operation,
        key,
        body == null ? Map.of() : body,
        legacy,
        raw,
        body == null,
        expectedProtocol
      );
    } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
      return receiveInternal(
        operation,
        key,
        Map.of(),
        legacy,
        raw,
        true,
        expectedProtocol
      );
    }
  }

  private Map<String, Object> receiveInternal(
    String operation,
    String key,
    Map<String, Object> body,
    boolean legacy,
    String raw,
    boolean invalidJson,
    String expectedProtocol
  ) {
    if (
      key == null || key.length() < 32 || key.length() > 256
    ) throw new ServiceException("缺少有效设备凭证", 401);
    var devices = production
      .database()
      .queryForList(
        "select d.*,s.line_id,s.code as station_code,s.process_step_id,p.sequence from mes_device d join mes_station s on d.station_id=s.id join mes_process p on s.process_step_id=p.id where d.key_hash=:key and d.active=true",
        Map.of(
          "key",
          MesDataPaths.sha256(
            key.getBytes(java.nio.charset.StandardCharsets.UTF_8)
          )
        )
      );
    if (devices.isEmpty()) throw new ServiceException(
      "设备凭证无效或设备已停用",
      401
    );
    var device = devices.get(0);
    return transaction.execute(status -> {
      production.gate();
      if (
        production
          .database()
          .queryForObject(
            "select count(*) from mes_device where id=:id and active=true and key_hash=:key",
            Map.of(
              "id",
              device.get("id"),
              "key",
              MesDataPaths.sha256(
                key.getBytes(java.nio.charset.StandardCharsets.UTF_8)
              )
            ),
            Long.class
          ) ==
        0
      ) throw new ServiceException("设备凭证无效或设备已停用", 401);
      Object savepoint = status.createSavepoint();
      String record = String.valueOf(body.getOrDefault("recordId", ""));
      if (raw.length() > 2 * 1024 * 1024) throw new ServiceException(
        "请求过大",
        422
      );
      try {
        if (
          !List.of("user", "orders", "check", "results", "heartbeat").contains(
            operation
          )
        ) throw new ServiceException("协议操作不支持", 404);
        if (
          !device.get("protocol").equals(expectedProtocol)
        ) throw new ServiceException("设备协议与接口路径不匹配", 403);
        if (invalidJson) throw new ServiceException("无法解析 JSON 报文", 422);
        var adapter = adapters.get(device.get("protocol").toString());
        if (adapter == null) throw new ServiceException(
          "此设备协议未安装适配器",
          422
        );
        var normalized = legacy
          ? LegacyHttpV1.normalize(operation, device, body, production)
          : body;
        Map<String, Object> result = null;
        if (legacy && operation.equals("results")) {
          var retried = production
            .database()
            .queryForList(
              "select * from mes_test_result where device_id=:d and record_id=:r",
              Map.of("d", device.get("id"), "r", body.get("recordId"))
            );
          if (
            !retried.isEmpty() &&
            MesRecords.canonical(
              production.parse(retried.get(0).get("raw"))
            ).equals(MesRecords.canonical(body))
          ) result = Map.of(
            "resultId",
            retried.get(0).get("id"),
            "duplicate",
            true,
            "verdict",
            retried.get(0).get("verdict")
          );
        }
        if (result == null) result = domain.execute(
          operation,
          device,
          adapter.normalize(operation, normalized),
          raw
        );
        if (legacy && operation.equals("results") && result.containsKey("id")) {
          result = new LinkedHashMap<>(result);
          result.put("resultId", result.remove("id"));
        }
        if (legacy && operation.equals("orders")) {
          var q = Map.of("l", device.get("line_id"));
          result = Map.of(
            "deviceCode",
            device.get("code"),
            "stationCode",
            device.get("station_code"),
            "orders",
            production.rows(
              "select * from mes_work_order where line_id=:l and status='Open'",
              q
            ),
            "batches",
            production.rows(
              "select b.* from mes_batch b join mes_work_order o on b.work_order_id=o.id where o.line_id=:l and o.status='Open'",
              q
            )
          );
        }
        receipt(device, operation, record, raw, "Accepted", "");
        status.releaseSavepoint(savepoint);
        changes.publish();
        return Map.<String, Object>of("accepted", true, "data", result);
      } catch (RuntimeException failure) {
        status.rollbackToSavepoint(savepoint);
        String reason = failure instanceof ServiceException
          ? failure.getMessage()
          : "数据格式不合法或数据库约束冲突";
        receipt(device, operation, record, raw, "Rejected", reason);
        return Map.<String, Object>of("accepted", false, "message", reason);
      }
    });
  }

  private void receipt(
    Map<String, Object> device,
    String op,
    String record,
    String raw,
    String state,
    String reason
  ) {
    production
      .database()
      .update(
        "insert into mes_receipt(id,device_id,operation,record_id,raw,status,reason) values(:id,:d,:op,:r,:raw,:status,:reason)",
        Map.of(
          "id",
          UUID.randomUUID(),
          "d",
          device.get("id"),
          "op",
          op.length() > 32 ? op.substring(0, 32) : op,
          "r",
          record.length() > 128 ? record.substring(0, 128) : record,
          "raw",
          raw,
          "status",
          state,
          "reason",
          reason.length() > 1024 ? reason.substring(0, 1024) : reason
        )
      );
  }
}
