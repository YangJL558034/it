package com.ruoyi.mes.controller;

import java.util.*;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import com.ruoyi.common.annotation.Log;
import com.ruoyi.common.enums.BusinessType;
import com.ruoyi.common.core.domain.AjaxResult;
import com.ruoyi.mes.service.MesMasterService;

@RestController
@RequestMapping("/mes/master")
public class MesMasterController {
    private final MesMasterService service;
    public MesMasterController(MesMasterService service){this.service=service;}
    @GetMapping("/{resource}")
    @PreAuthorize("@ss.hasPermi('mes:'+#resource+':list')")
    public AjaxResult list(@PathVariable String resource,@RequestParam(defaultValue="") String keyword,@RequestParam(defaultValue="1") int pageNum,@RequestParam(defaultValue="20") int pageSize){var data=service.list(resource,keyword,pageNum,pageSize);return AjaxResult.success().put("rows",data.get("rows")).put("total",data.get("total"));}
    @GetMapping("/{resource}/options")
    @PreAuthorize("@ss.hasPermi('mes:'+#resource+':list')")
    public AjaxResult options(@PathVariable String resource){return AjaxResult.success(service.options(resource));}
    @GetMapping("/processes/list")
    @PreAuthorize("@ss.hasPermi('mes:processes:list')")
    public AjaxResult processes(){return AjaxResult.success(service.processes());}
    @PostMapping("/{resource}")
    @PreAuthorize("@ss.hasPermi('mes:'+#resource+':add')")
    @Log(title="MES 基础资料新增",businessType=BusinessType.INSERT)
    public AjaxResult create(@PathVariable String resource,@RequestBody Map<String,Object> body){return AjaxResult.success(service.save(resource,null,body));}
    @PutMapping("/{resource}/{id}")
    @PreAuthorize("@ss.hasPermi('mes:'+#resource+':edit')")
    @Log(title="MES 基础资料修改",businessType=BusinessType.UPDATE)
    public AjaxResult update(@PathVariable String resource,@PathVariable UUID id,@RequestBody Map<String,Object> body){return AjaxResult.success(service.save(resource,id,body));}
    @DeleteMapping("/{resource}/{id}")
    @PreAuthorize("@ss.hasPermi('mes:'+#resource+':remove')")
    @Log(title="MES 基础资料删除",businessType=BusinessType.DELETE)
    public AjaxResult delete(@PathVariable String resource,@PathVariable UUID id,@RequestParam int version){service.delete(resource,id,version);return AjaxResult.success();}
}
