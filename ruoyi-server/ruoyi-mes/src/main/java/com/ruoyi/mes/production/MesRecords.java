package com.ruoyi.mes.production;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.ruoyi.common.exception.ServiceException;
import java.util.*;

public final class MesRecords {

  private MesRecords() {}

  public static Map<String, Object> external(Map<String, Object> row) {
    var result = new LinkedHashMap<String, Object>();
    row.forEach((k, v) -> {
      var key = new StringBuilder();
      boolean upper = false;
      for (char c : k.toCharArray()) {
        if (c == '_') {
          upper = true;
          continue;
        }
        key.append(upper ? Character.toUpperCase(c) : c);
        upper = false;
      }
      result.put(
        key.toString(),
        v instanceof java.sql.Timestamp t ? t.toInstant() : v
      );
    });
    return result;
  }

  public static UUID id(Object value) {
    try {
      return UUID.fromString(String.valueOf(value));
    } catch (Exception e) {
      throw new ServiceException("关联编号无效", 422);
    }
  }

  public static String text(Map<String, Object> body, String key, int max) {
    Object value = body.get(key);
    if (
      !(value instanceof String s) || s.isBlank() || s.length() > max
    ) throw new ServiceException(key + " 必填，最多 " + max + " 字符", 422);
    return s;
  }

  public static String json(ObjectMapper mapper, Object value) {
    try {
      return mapper.writeValueAsString(value);
    } catch (Exception e) {
      throw new ServiceException("数据无法序列化", 422);
    }
  }

  public static Object canonical(Object value) {
    if (value instanceof Map<?, ?> map) {
      var sorted = new TreeMap<String, Object>();
      map.forEach((k, v) -> sorted.put(k.toString(), canonical(v)));
      return sorted;
    }
    if (value instanceof List<?> list) return list
      .stream()
      .map(MesRecords::canonical)
      .toList();
    return value;
  }
}
