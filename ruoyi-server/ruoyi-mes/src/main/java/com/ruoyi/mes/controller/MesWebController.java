package com.ruoyi.mes.controller;

import com.ruoyi.common.annotation.Anonymous;
import org.springframework.core.io.*;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;

@RestController
public class MesWebController {

  @Anonymous
  @GetMapping({ "/station", "/tv" })
  public ResponseEntity<Resource> terminal() {
    return ResponseEntity.ok()
      .contentType(MediaType.TEXT_HTML)
      .cacheControl(CacheControl.noStore())
      .body(new ClassPathResource("static/terminal/index.html"));
  }
}
