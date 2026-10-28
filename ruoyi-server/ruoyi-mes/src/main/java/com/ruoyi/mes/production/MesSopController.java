package com.ruoyi.mes.production;

import com.ruoyi.common.annotation.Log;
import com.ruoyi.common.core.domain.AjaxResult;
import com.ruoyi.common.enums.BusinessType;
import com.ruoyi.common.exception.ServiceException;
import com.ruoyi.common.utils.SecurityUtils;
import com.ruoyi.framework.web.service.PermissionService;
import com.ruoyi.mes.realtime.MesChanges;
import com.ruoyi.mes.storage.MesDataPaths;
import java.nio.file.*;
import java.util.*;
import org.springframework.core.io.*;
import org.springframework.http.*;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

@RestController
public class MesSopController {

  private final MesProductionService p;
  private final MesDataPaths paths;
  private final MesChanges changes;
  private final PermissionService permissions;

  public MesSopController(
    MesProductionService p,
    MesDataPaths paths,
    MesChanges changes,
    PermissionService permissions
  ) {
    this.p = p;
    this.paths = paths;
    this.changes = changes;
    this.permissions = permissions;
  }

  private Map<String, Object> version(UUID id) {
    return p.one(
      "select * from mes_sop_version where id=:id",
      Map.of("id", id)
    );
  }

  private void access(UUID process) {
    if (permissions.hasPermi("mes:sop:list")) return;
    if (
      !permissions.hasPermi("mes:station:operate")
    ) throw new ServiceException("无 SOP 权限", 403);
    if (
      !SecurityUtils.isAdmin(SecurityUtils.getUserId()) &&
      p
        .database()
        .queryForObject(
          "select count(*) from mes_station_grant g join mes_station s on g.station_id=s.id where s.process_step_id=:p and g.user_id=:u",
          Map.of("p", process, "u", SecurityUtils.getUserId()),
          Long.class
        ) ==
      0
    ) throw new ServiceException("未授权此工序 SOP", 403);
  }

  private Map<String, Object> manifest(Map<String, Object> v) {
    return Map.of(
      "version",
      MesRecords.external(v),
      "files",
      p.rows(
        "select id,name,content_type,position,seconds,size from mes_sop_file where sop_version_id=:id order by position,id",
        Map.of("id", v.get("id"))
      )
    );
  }

  @GetMapping("/api/sop/current/{process}")
  public Object current(@PathVariable UUID process) {
    access(process);
    UUID id = p.sop(process);
    if (id != null) return manifest(version(id));
    var empty = new LinkedHashMap<String, Object>();
    empty.put("version", null);
    empty.put("files", List.of());
    return empty;
  }

  @GetMapping("/api/sop/files/{id}")
  public ResponseEntity<Resource> file(@PathVariable UUID id) throws Exception {
    var f = p.one(
      "select f.*,v.process_step_id,v.status from mes_sop_file f join mes_sop_version v on f.sop_version_id=v.id where f.id=:id",
      Map.of("id", id)
    );
    access((UUID) f.get("process_step_id"));
    if (
      !permissions.hasPermi("mes:sop:list") &&
      !List.of("Published", "Archived").contains(f.get("status"))
    ) throw new ServiceException("未发布 SOP 不可用于作业", 403);
    return ResponseEntity.ok()
      .header("X-Content-Type-Options", "nosniff")
      .header(HttpHeaders.CONTENT_DISPOSITION, "inline; filename=sop-" + id)
      .contentType(MediaType.parseMediaType(f.get("content_type").toString()))
      .body(
        new FileSystemResource(paths.resolve(f.get("storage_name").toString()))
      );
  }

  @GetMapping("/mes/sop")
  @PreAuthorize("@ss.hasPermi('mes:sop:list')")
  public Object list() {
    return AjaxResult.success(
      p.rows(
        "select v.*,p.name as process from mes_sop_version v join mes_process p on v.process_step_id=p.id order by p.sequence,v.version desc",
        Map.of()
      )
    );
  }

  @GetMapping("/mes/sop/{id}")
  @PreAuthorize("@ss.hasPermi('mes:sop:list')")
  public Object preview(@PathVariable UUID id) {
    return AjaxResult.success(manifest(version(id)));
  }

  @PostMapping("/mes/sop")
  @PreAuthorize("@ss.hasPermi('mes:sop:edit')")
  @Transactional
  @Log(title = "创建 SOP 版本", businessType = BusinessType.INSERT)
  public Object create(@RequestBody Map<String, Object> b) {
    p.gate();
    UUID process = MesRecords.id(b.get("processId")), id = UUID.randomUUID();
    p.one("select id from mes_process where id=:id", Map.of("id", process));
    int v = p
      .database()
      .queryForObject(
        "select coalesce(max(version),0)+1 from mes_sop_version where process_step_id=:p",
        Map.of("p", process),
        Integer.class
      );
    p
      .database()
      .update(
        "insert into mes_sop_version(id,process_step_id,version,title,status,created_by) values(:id,:p,:v,:t,'Draft',:u)",
        Map.of(
          "id",
          id,
          "p",
          process,
          "v",
          v,
          "t",
          MesRecords.text(b, "title", 256),
          "u",
          SecurityUtils.getUserId()
        )
      );
    return AjaxResult.success(Map.of("id", id));
  }

  @PostMapping("/mes/sop/{id}/files")
  @PreAuthorize("@ss.hasPermi('mes:sop:edit')")
  @Transactional(rollbackFor = Exception.class)
  @Log(
    title = "上传 SOP 文件",
    businessType = BusinessType.INSERT,
    isSaveRequestData = false
  )
  public Object upload(
    @PathVariable UUID id,
    @RequestParam("files") List<MultipartFile> files
  ) throws Exception {
    p.gate();
    if (!"Draft".equals(version(id).get("status"))) throw new ServiceException(
      "仅草稿允许修改文件",
      409
    );
    if (files.isEmpty() || files.size() > 50) throw new ServiceException(
      "一次选择 1–50 个文件",
      422
    );
    int position = p
      .database()
      .queryForObject(
        "select coalesce(max(position),0) from mes_sop_file where sop_version_id=:id",
        Map.of("id", id),
        Integer.class
      );
    for (var file : files) {
      if (
        file.isEmpty() || file.getSize() > 100L * 1024 * 1024
      ) throw new ServiceException("单文件限 100 MB", 422);
      byte[] bytes = file.getBytes();
      String type;
      if (
        bytes.length > 8 &&
        bytes[0] == (byte) 0x89 &&
        bytes[1] == 'P' &&
        bytes[2] == 'N' &&
        bytes[3] == 'G'
      ) type = "image/png";
      else if (
        bytes.length > 3 && bytes[0] == (byte) 0xff && bytes[1] == (byte) 0xd8
      ) type = "image/jpeg";
      else if (
        bytes.length > 5 &&
        new String(
          bytes,
          0,
          5,
          java.nio.charset.StandardCharsets.US_ASCII
        ).equals("%PDF-")
      ) type = "application/pdf";
      else if (
        bytes.length > 12 &&
        new String(
          bytes,
          4,
          4,
          java.nio.charset.StandardCharsets.US_ASCII
        ).equals("ftyp")
      ) type = "video/mp4";
      else throw new ServiceException("只支持 PNG、JPEG、PDF 和 MP4 文件", 422);
      UUID fileId = UUID.randomUUID();
      String storage = "files/sop/" + fileId;
      String name = Optional.ofNullable(file.getOriginalFilename()).orElse(
        "SOP"
      );
      if (
        name.length() > 256 || name.contains("\r") || name.contains("\n")
      ) throw new ServiceException("文件名无效", 422);
      paths.atomicWrite(paths.resolve(storage), bytes);
      p
        .database()
        .update(
          "insert into mes_sop_file(id,sop_version_id,name,storage_name,content_type,sha256,position,seconds,size) values(:id,:v,:n,:s,:t,:h,:p,10,:size)",
          Map.of(
            "id",
            fileId,
            "v",
            id,
            "n",
            name,
            "s",
            storage,
            "t",
            type,
            "h",
            MesDataPaths.sha256(bytes),
            "p",
            ++position,
            "size",
            bytes.length
          )
        );
    }
    return AjaxResult.success();
  }

  @PutMapping("/mes/sop/{id}/files")
  @PreAuthorize("@ss.hasPermi('mes:sop:edit')")
  @Transactional
  @Log(title = "调整 SOP 播放顺序与时长", businessType = BusinessType.UPDATE)
  public Object order(
    @PathVariable UUID id,
    @RequestBody List<Map<String, Object>> files
  ) {
    p.gate();
    if (!"Draft".equals(version(id).get("status"))) throw new ServiceException(
      "已审核版本不可修改",
      409
    );
    var old = p.rows(
      "select id from mes_sop_file where sop_version_id=:id",
      Map.of("id", id)
    );
    Set<UUID> ids = new HashSet<>();
    int position = 0;
    for (var file : files) {
      UUID f = MesRecords.id(file.get("id"));
      if (
        !ids.add(f) ||
        old.stream().noneMatch(r -> f.equals(r.get("id"))) ||
        !(file.get("seconds") instanceof Number seconds) ||
        seconds.doubleValue() != seconds.intValue() ||
        seconds.intValue() < 1 ||
        seconds.intValue() > 3600
      ) throw new ServiceException("文件、顺序或停留时间无效", 422);
      p
        .database()
        .update(
          "update mes_sop_file set position=:p,seconds=:s where id=:id",
          Map.of("p", ++position, "s", seconds.intValue(), "id", f)
        );
    }
    if (ids.size() != old.size()) throw new ServiceException(
      "必须提交完整文件顺序",
      422
    );
    return AjaxResult.success();
  }

  @DeleteMapping("/mes/sop/{id}/files/{file}")
  @PreAuthorize("@ss.hasPermi('mes:sop:edit')")
  @Transactional
  @Log(title = "删除草稿 SOP 文件", businessType = BusinessType.DELETE)
  public Object delete(@PathVariable UUID id, @PathVariable UUID file) {
    p.gate();
    if (!"Draft".equals(version(id).get("status"))) throw new ServiceException(
      "发布或审核版本不可删除文件",
      409
    );
    p
      .database()
      .update(
        "delete from mes_sop_file where id=:f and sop_version_id=:v",
        Map.of("f", file, "v", id)
      );
    return AjaxResult.success();
  }

  @PostMapping("/mes/sop/{id}/review")
  @PreAuthorize("@ss.hasPermi('mes:sop:review')")
  @Transactional
  @Log(title = "审核 SOP", businessType = BusinessType.UPDATE)
  public Object review(@PathVariable UUID id) {
    p.gate();
    var v = version(id);
    if (
      !"Draft".equals(v.get("status")) ||
      ((Number) v.get("created_by")).longValue() == SecurityUtils.getUserId()
    ) throw new ServiceException("须由另一名有审核权限的用户审核草稿", 409);
    if (
      p
        .database()
        .queryForObject(
          "select count(*) from mes_sop_file where sop_version_id=:id",
          Map.of("id", id),
          Long.class
        ) ==
      0
    ) throw new ServiceException("空 SOP 不可审核", 422);
    p
      .database()
      .update(
        "update mes_sop_version set status='Reviewed',reviewed_by=:u where id=:id",
        Map.of("u", SecurityUtils.getUserId(), "id", id)
      );
    return AjaxResult.success();
  }

  @PostMapping("/mes/sop/{id}/publish")
  @PreAuthorize("@ss.hasPermi('mes:sop:publish')")
  @Transactional
  @Log(title = "发布 SOP", businessType = BusinessType.UPDATE)
  public Object publish(@PathVariable UUID id) {
    p.gate();
    var v = version(id);
    if (!"Reviewed".equals(v.get("status"))) throw new ServiceException(
      "仅已审核版本可以发布",
      409
    );
    p
      .database()
      .update(
        "update mes_sop_version set status='Archived' where process_step_id=:p and status='Published'",
        Map.of("p", v.get("process_step_id"))
      );
    p
      .database()
      .update(
        "update mes_sop_version set status='Published',published_at=now() where id=:id",
        Map.of("id", id)
      );
    changes.publish();
    return AjaxResult.success();
  }
}
