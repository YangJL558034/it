package com.ruoyi.mes.production;

import com.ruoyi.common.exception.ServiceException;
import java.util.*;

/** Compatibility with the existing .NET generic HTTP contract, derived from its source. */
public final class LegacyHttpV1 {

  private LegacyHttpV1() {}

  public static Map<String, Object> normalize(
    String operation,
    Map<String, Object> d,
    Map<String, Object> b,
    MesProductionService p
  ) {
    var n = new LinkedHashMap<String, Object>(b);
    n.put("deviceCode", d.get("code"));
    n.put("stationId", d.get("station_id").toString());
    if (
      List.of("check", "results").contains(operation) &&
      (!d.get("code").equals(b.get("deviceCode")) ||
        !d.get("station_code").equals(b.get("stationCode")))
    ) throw new ServiceException("设备或工位编号不匹配", 422);
    if (operation.equals("check")) {
      n.put("taskId", MesRecords.text(b, "externalTaskId", 128));
      UUID batch = MesRecords.id(b.get("batchId"));
      var row = p.one(
        "select work_order_id from mes_batch where id=:b",
        Map.of("b", batch)
      );
      if (
        !row.get("work_order_id").equals(MesRecords.id(b.get("workOrderId")))
      ) throw new ServiceException("当前工单与批次不符", 422);
    }
    if (operation.equals("results")) {
      var task = p.one(
        "select external_id,batch_id from mes_test_task where id=:t and device_id=:d",
        Map.of("t", MesRecords.id(b.get("taskId")), "d", d.get("id"))
      );
      n.put("taskId", task.get("external_id"));
      n.put("batchId", task.get("batch_id").toString());
      if (b.get("items") instanceof List<?> items) n.put(
        "items",
        items
          .stream()
          .map(item -> {
            if (!(item instanceof Map<?, ?> m)) throw new ServiceException(
              "测试参数格式错误",
              422
            );
            var result = new LinkedHashMap<String, Object>();
            m.forEach((k, v) -> result.put(k.toString(), v));
            result.put("lowerLimit", m.get("lower"));
            result.put("upperLimit", m.get("upper"));
            return result;
          })
          .toList()
      );
    }
    return n;
  }
}
