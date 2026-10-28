package com.ruoyi.mes.controller;

import com.ruoyi.common.core.domain.AjaxResult;
import com.ruoyi.framework.web.service.PermissionService;
import com.ruoyi.mes.service.MesMasterService;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.*;

@RestController
public class MesModuleController {

  private final JdbcTemplate db;
  private final PermissionService permissions;
  private final MesMasterService master;

  public MesModuleController(
    JdbcTemplate db,
    PermissionService permissions,
    MesMasterService master
  ) {
    this.db = db;
    this.permissions = permissions;
    this.master = master;
  }

  @GetMapping("/mes/modules")
  public AjaxResult modules() {
    var rows = db.queryForList(
      "select module_key as key,title,group_name as \"groupName\",route,permission,status,description from mes_module order by sort"
    );
    var visible = new ArrayList<Map<String, Object>>();
    for (var row : rows) {
      if (!permissions.hasPermi((String) row.get("permission"))) continue;
      var module = new LinkedHashMap<>(row);
      if (
        List.of("lines", "products", "stations", "orders", "batches").contains(
          row.get("key")
        )
      ) module.put(
        "count",
        master.list((String) row.get("key"), "", 1, 1).get("total")
      );
      visible.add(module);
    }
    return AjaxResult.success(visible);
  }
}
