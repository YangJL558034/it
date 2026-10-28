package com.ruoyi.mes.production;

import com.ruoyi.common.annotation.Log;
import com.ruoyi.common.core.domain.AjaxResult;
import com.ruoyi.common.enums.BusinessType;
import java.util.*;
import org.springframework.core.io.*;
import org.springframework.http.*;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/mes/reports")
public class MesReportController {

  private final MesReportService service;

  public MesReportController(MesReportService service) {
    this.service = service;
  }

  @GetMapping("/jobs")
  @PreAuthorize("@ss.hasPermi('mes:reports:list')")
  public Object jobs() {
    return AjaxResult.success(service.list());
  }

  @PostMapping("/jobs")
  @PreAuthorize("@ss.hasPermi('mes:reports:export')")
  @Log(title = "按工单批次导出 MES 报表", businessType = BusinessType.EXPORT)
  public Object create(@RequestBody Map<String, Object> b) {
    return AjaxResult.success(service.create(b));
  }

  @GetMapping("/jobs/{id}/download")
  @PreAuthorize("@ss.hasPermi('mes:reports:export')")
  public ResponseEntity<Resource> download(@PathVariable UUID id) {
    return ResponseEntity.ok()
      .header(
        HttpHeaders.CONTENT_DISPOSITION,
        "attachment; filename=mes-" + id + ".csv"
      )
      .contentType(MediaType.parseMediaType("text/csv;charset=UTF-8"))
      .body(new FileSystemResource(service.file(id)));
  }
}
