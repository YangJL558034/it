package com.ruoyi.mes.production;

import com.ruoyi.common.annotation.Anonymous;
import java.util.*;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

@RestController
public class MesStationController {

  private final MesProductionService service;

  public MesStationController(MesProductionService service) {
    this.service = service;
  }

  @Anonymous
  @GetMapping("/api/station/barcode-rules")
  public Object rules() {
    return service.rules();
  }

  @GetMapping("/api/station/options")
  @PreAuthorize("@ss.hasPermi('mes:station:operate')")
  public Object options() {
    return service.options();
  }

  @GetMapping("/api/station/summary")
  @PreAuthorize("@ss.hasPermi('mes:station:operate')")
  public Object summary(
    @RequestParam UUID stationId,
    @RequestParam UUID batchId
  ) {
    return service.summary(stationId, batchId);
  }

  @PostMapping("/api/station/scan")
  @PreAuthorize("@ss.hasPermi('mes:station:operate')")
  public Object scan(@RequestBody Map<String, Object> body) {
    return service.scan(body);
  }

  @PostMapping("/api/station/reset")
  @PreAuthorize("@ss.hasPermi('mes:station:operate')")
  public Object reset(@RequestBody Map<String, Object> body) {
    return service.reset(
      MesRecords.id(body.get("stationId")),
      MesRecords.id(body.get("batchId"))
    );
  }

  @PostMapping("/api/station/heartbeat")
  @PreAuthorize("@ss.hasPermi('mes:station:operate')")
  public Object heartbeat(@RequestBody Map<String, Object> body) {
    return service.heartbeat(
      MesRecords.id(body.get("stationId")),
      MesRecords.id(body.get("batchId"))
    );
  }

  @GetMapping("/api/tests")
  @PreAuthorize("@ss.hasPermi('mes:station:operate')")
  public Object tests(
    @RequestParam UUID stationId,
    @RequestParam UUID batchId
  ) {
    service.batch(batchId, service.station(stationId));
    return service.tests(stationId, batchId, null);
  }
}
