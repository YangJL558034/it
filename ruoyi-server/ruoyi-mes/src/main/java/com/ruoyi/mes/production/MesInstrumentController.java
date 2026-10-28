package com.ruoyi.mes.production;

import com.ruoyi.common.annotation.Anonymous;
import java.util.*;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
public class MesInstrumentController {

  private final MesInstrumentService service;

  public MesInstrumentController(MesInstrumentService service) {
    this.service = service;
  }

  @Anonymous
  @PostMapping("/api/instruments/{protocol}/{operation}")
  public ResponseEntity<Object> receive(
    @PathVariable String protocol,
    @PathVariable String operation,
    @RequestHeader(value = "X-Device-Key", required = false) String key,
    @RequestBody String raw
  ) {
    var result = service.receiveRaw(operation, key, raw, false, protocol);
    return ResponseEntity.status(
      Boolean.TRUE.equals(result.get("accepted")) ? 200 : 422
    ).body(result);
  }
}
