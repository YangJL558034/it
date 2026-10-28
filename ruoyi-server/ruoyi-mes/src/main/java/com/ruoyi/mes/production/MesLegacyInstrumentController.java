package com.ruoyi.mes.production;

import com.ruoyi.common.annotation.Anonymous;
import java.util.*;
import org.springframework.web.bind.annotation.*;

@RestController
public class MesLegacyInstrumentController {

  private final MesInstrumentService service;

  public MesLegacyInstrumentController(MesInstrumentService service) {
    this.service = service;
  }

  @Anonymous
  @PostMapping(
    {
      "/api/instrument/v1/check",
      "/api/instrument/v1/results",
      "/api/instrument/v1/heartbeat",
      "/api/instrument/v1/user",
    }
  )
  public Object receive(
    jakarta.servlet.http.HttpServletRequest request,
    @RequestHeader(value = "X-Device-Key", required = false) String key,
    @RequestBody(required = false) String raw
  ) {
    String operation = request
      .getRequestURI()
      .substring(request.getRequestURI().lastIndexOf('/') + 1);
    var result = service.receiveRaw(
      operation,
      key,
      raw == null ? "{}" : raw,
      true
    );
    return (
        List.of("check", "results").contains(operation) ||
        !Boolean.TRUE.equals(result.get("accepted"))
      )
      ? result
      : result.get("data");
  }

  @Anonymous
  @GetMapping("/api/instrument/v1/work-orders")
  public Object orders(
    @RequestHeader(value = "X-Device-Key", required = false) String key
  ) {
    return service.receive("orders", key, Map.of(), true).get("data");
  }
}
