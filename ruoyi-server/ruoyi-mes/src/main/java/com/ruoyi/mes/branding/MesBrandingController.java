package com.ruoyi.mes.branding;

import com.ruoyi.common.annotation.*;
import com.ruoyi.common.core.domain.AjaxResult;
import com.ruoyi.common.enums.BusinessType;
import java.io.IOException;
import java.util.Map;
import org.springframework.core.io.Resource;
import org.springframework.http.*;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

@RestController
public class MesBrandingController {

  private final MesBrandingService service;

  public MesBrandingController(MesBrandingService service) {
    this.service = service;
  }

  @Anonymous
  @GetMapping("/api/branding")
  public Map<String, Object> current() {
    return service.current();
  }

  @Anonymous
  @GetMapping("/api/branding/logo/{key}")
  public ResponseEntity<Resource> logo(@PathVariable String key) {
    return ResponseEntity.ok()
      .contentType(MediaType.IMAGE_PNG)
      .cacheControl(CacheControl.noCache())
      .eTag("\"" + key + "\"")
      .body(service.logo(key));
  }

  @PreAuthorize("@ss.hasPermi('mes:branding:list')")
  @GetMapping("/mes/branding")
  public AjaxResult admin() {
    return AjaxResult.success(service.current());
  }

  @PreAuthorize("@ss.hasPermi('mes:branding:edit')")
  @PostMapping("/mes/branding")
  @Log(title = "平台名称与 Logo", businessType = BusinessType.UPDATE)
  public AjaxResult save(
    @RequestParam String name,
    @RequestParam(defaultValue = "false") boolean reset,
    @RequestParam(required = false) MultipartFile file
  ) throws IOException {
    return AjaxResult.success(service.save(name, file, reset));
  }
}
