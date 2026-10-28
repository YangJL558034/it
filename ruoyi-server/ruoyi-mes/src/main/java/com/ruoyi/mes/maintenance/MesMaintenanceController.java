package com.ruoyi.mes.maintenance;

import com.ruoyi.common.annotation.Log;
import com.ruoyi.common.core.domain.AjaxResult;
import com.ruoyi.common.enums.BusinessType;
import java.util.*;
import org.springframework.core.io.*;
import org.springframework.http.*;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/mes/maintenance")
public class MesMaintenanceController {

  public record Confirmation(
    UUID planId,
    String confirmationToken,
    String phrase,
    String password
  ) {}

  public record Selection(List<String> orderIds) {}

  public record Mark(
    List<String> orderIds,
    String reason,
    String password,
    String phrase
  ) {}

  private final MesMaintenanceJobs jobs;
  private final MesBackupService backups;
  private final MesTestCleanupService cleanup;

  public MesMaintenanceController(
    MesMaintenanceJobs jobs,
    MesBackupService backups,
    MesTestCleanupService cleanup
  ) {
    this.jobs = jobs;
    this.backups = backups;
    this.cleanup = cleanup;
  }

  @GetMapping("/jobs")
  @PreAuthorize("@ss.hasPermi('mes:backup:list')")
  public AjaxResult list() throws Exception {
    return AjaxResult.success(jobs.list());
  }

  @PostMapping("/backups")
  @PreAuthorize("@ss.hasPermi('mes:backup:create')")
  @Log(
    title = "创建 MES 数据库及附件备份",
    businessType = BusinessType.OTHER,
    isSaveResponseData = false
  )
  public AjaxResult create() throws Exception {
    return AjaxResult.success(backups.create());
  }

  @GetMapping("/backups/{id}/download")
  @PreAuthorize("@ss.hasPermi('mes:backup:download')")
  @Log(
    title = "下载 MES 数据库备份",
    businessType = BusinessType.EXPORT,
    isSaveResponseData = false
  )
  public ResponseEntity<Resource> download(@PathVariable UUID id)
    throws Exception {
    return ResponseEntity.ok()
      .header(
        HttpHeaders.CONTENT_DISPOSITION,
        "attachment; filename=mes-backup-" + id + ".zip"
      )
      .contentType(MediaType.APPLICATION_OCTET_STREAM)
      .body(new FileSystemResource(backups.archive(id)));
  }

  @PostMapping("/backups/{id}/preview")
  @PreAuthorize("@ss.hasPermi('mes:backup:restore')")
  @Log(
    title = "校验 MES 数据库恢复范围",
    businessType = BusinessType.OTHER,
    isSaveResponseData = false
  )
  public AjaxResult preview(@PathVariable UUID id) throws Exception {
    return AjaxResult.success(backups.preview(id));
  }

  @PostMapping("/restore")
  @PreAuthorize("@ss.hasPermi('mes:backup:restore')")
  @Log(
    title = "确认恢复 MES 数据库与附件",
    businessType = BusinessType.OTHER,
    isSaveRequestData = false,
    isSaveResponseData = false
  )
  public AjaxResult restore(@RequestBody Confirmation body) throws Exception {
    return AjaxResult.success(
      backups.restore(
        body.planId,
        body.confirmationToken,
        body.phrase,
        body.password
      )
    );
  }

  @DeleteMapping("/backups/{id}")
  @PreAuthorize("@ss.hasPermi('mes:backup:remove')")
  @Log(title = "删除 MES 备份档案", businessType = BusinessType.DELETE)
  public AjaxResult delete(@PathVariable UUID id) throws Exception {
    backups.delete(id, false);
    return AjaxResult.success();
  }

  @GetMapping("/settings")
  @PreAuthorize("@ss.hasPermi('mes:backup:list')")
  public AjaxResult settings() {
    return AjaxResult.success(backups.settings());
  }

  @PutMapping("/settings")
  @PreAuthorize("@ss.hasPermi('mes:backup:config')")
  @Log(title = "修改 MES 备份策略", businessType = BusinessType.UPDATE)
  public AjaxResult settings(@RequestBody Map<String, Object> body) {
    backups.settings(body);
    return AjaxResult.success();
  }

  @GetMapping("/test-orders")
  @PreAuthorize("@ss.hasPermi('mes:maintenance:cleanup')")
  public AjaxResult orders(@RequestParam(defaultValue = "") String keyword) {
    return AjaxResult.success(cleanup.orders(keyword));
  }

  @PostMapping("/test-orders/mark")
  @PreAuthorize("@ss.hasPermi('mes:maintenance:cleanup')")
  @Log(
    title = "标记测试工单",
    businessType = BusinessType.UPDATE,
    isSaveRequestData = false
  )
  public AjaxResult mark(@RequestBody Mark body) {
    cleanup.mark(body.orderIds, body.reason, body.password, body.phrase);
    return AjaxResult.success();
  }

  @PostMapping("/test-orders/preview")
  @PreAuthorize("@ss.hasPermi('mes:maintenance:cleanup')")
  @Log(
    title = "预览测试数据清理范围",
    businessType = BusinessType.OTHER,
    isSaveResponseData = false
  )
  public AjaxResult preview(@RequestBody Selection body) throws Exception {
    return AjaxResult.success(cleanup.preview(body.orderIds));
  }

  @PostMapping("/test-orders/cleanup")
  @PreAuthorize("@ss.hasPermi('mes:maintenance:cleanup')")
  @Log(
    title = "清理已确认测试工单",
    businessType = BusinessType.DELETE,
    isSaveRequestData = false,
    isSaveResponseData = false
  )
  public AjaxResult clean(@RequestBody Confirmation body) throws Exception {
    return AjaxResult.success(
      cleanup.clean(
        body.planId,
        body.confirmationToken,
        body.password,
        body.phrase
      )
    );
  }
}
