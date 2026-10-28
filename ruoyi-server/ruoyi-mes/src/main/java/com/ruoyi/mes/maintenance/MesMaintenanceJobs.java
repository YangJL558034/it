package com.ruoyi.mes.maintenance;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ruoyi.common.exception.ServiceException;
import com.ruoyi.mes.storage.MesDataPaths;
import jakarta.annotation.PreDestroy;
import java.io.*;
import java.nio.file.*;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

@Component
public class MesMaintenanceJobs {

  @FunctionalInterface
  public interface Action {
    void run(Map<String, Object> job) throws Exception;
  }

  private final MesDataPaths paths;
  private final ObjectMapper json;
  private final JdbcTemplate db;
  private final AtomicBoolean busy = new AtomicBoolean();
  private final ExecutorService executor = Executors.newSingleThreadExecutor(
    r -> {
      Thread t = new Thread(r, "mes-maintenance");
      t.setDaemon(true);
      return t;
    }
  );

  public MesMaintenanceJobs(
    MesDataPaths paths,
    ObjectMapper json,
    JdbcTemplate db
  ) {
    this.paths = paths;
    this.json = json;
    this.db = db;
  }

  public boolean busy() {
    return busy.get();
  }

  public String secret() {
    byte[] b = new byte[32];
    new java.security.SecureRandom().nextBytes(b);
    return Base64.getUrlEncoder().withoutPadding().encodeToString(b);
  }

  public Map<String, Object> newJob(String kind, long actor, String actorName)
    throws IOException {
    var job = new LinkedHashMap<String, Object>();
    job.put("id", UUID.randomUUID().toString());
    job.put("kind", kind);
    job.put("operatorId", actor);
    job.put("operatorName", actorName);
    job.put("createdAt", Instant.now().toString());
    job.put("status", "Queued");
    save(job);
    return job;
  }

  public Map<String, Object> start(
    String kind,
    long actor,
    String actorName,
    Action action
  ) throws IOException {
    if (!busy.compareAndSet(false, true)) throw new ServiceException(
      "已有备份、恢复或清理任务正在执行",
      409
    );
    try {
      var job = newJob(kind, actor, actorName);
      String token = secret();
      job.put(
        "pollHash",
        MesDataPaths.sha256(
          token.getBytes(java.nio.charset.StandardCharsets.UTF_8)
        )
      );
      save(job);
      executor.submit(() -> {
        try {
          execute(job, action);
        } finally {
          busy.set(false);
        }
      });
      var result = publicJob(job);
      result.put("pollToken", token);
      return result;
    } catch (IOException | RuntimeException e) {
      busy.set(false);
      throw e;
    }
  }

  public void execute(Map<String, Object> job, Action action) {
    try {
      update(job, "status", "Running");
      action.run(job);
      update(job, "status", "Completed");
    } catch (Exception e) {
      try {
        job.put("error", safeMessage(e));
        update(job, "status", "Failed");
      } catch (Exception ignored) {}
    } finally {
      try {
        update(job, "finishedAt", Instant.now().toString());
      } catch (Exception ignored) {}
      try {
        String detail = json.writeValueAsString(publicJob(job));
        if (detail.length() > 1900) detail = detail.substring(0, 1900);
        db.update(
          "insert into sys_oper_log(title,business_type,method,request_method,operator_type,oper_name,oper_url,oper_ip,json_result,status,error_msg,oper_time) values(?,0,?,'JOB',1,?,'/mes/maintenance','127.0.0.1',?,?,?,now())",
          "MES 运维任务 " + job.get("kind"),
          "mes-maintenance:" + job.get("id"),
          job.get("operatorName"),
          detail,
          "Completed".equals(job.get("status")) ? 0 : 1,
          String.valueOf(job.getOrDefault("error", ""))
        );
      } catch (Exception e) {
        try {
          update(
            job,
            "auditWarning",
            "数据库操作日志暂时不可写；运维文件日志已保留"
          );
        } catch (Exception ignored) {}
      }
    }
  }

  public Map<String, Object> internalBackup(
    long actor,
    String actorName,
    Action action
  ) throws Exception {
    var job = newJob("Backup", actor, actorName);
    job.put("protected", true);
    save(job);
    execute(job, action);
    if (!"Completed".equals(job.get("status"))) throw new ServiceException(
      "操作前备份失败：" + job.get("error"),
      500
    );
    return job;
  }

  private String safeMessage(Exception e) {
    String message = e.getMessage();
    if (message == null) message = e.getClass().getSimpleName();
    return message.length() > 1200 ? message.substring(0, 1200) : message;
  }

  public synchronized void save(Map<String, Object> job) throws IOException {
    paths.atomicWrite(
      paths.resolve(
        "maintenance/jobs/" +
        UUID.fromString(job.get("id").toString()) +
        ".json"
      ),
      json.writeValueAsBytes(job)
    );
  }

  public synchronized void update(
    Map<String, Object> job,
    String key,
    Object value
  ) throws IOException {
    job.put(key, value);
    save(job);
  }

  public Map<String, Object> read(UUID id) throws IOException {
    Path file = paths.resolve("maintenance/jobs/" + id + ".json");
    if (!Files.exists(file)) throw new ServiceException("任务不存在", 404);
    return json.readValue(
      Files.readAllBytes(file),
      new TypeReference<LinkedHashMap<String, Object>>() {}
    );
  }

  public Map<String, Object> publicJob(Map<String, Object> job) {
    var copy = new LinkedHashMap<>(job);
    copy.remove("pollHash");
    return copy;
  }

  public Map<String, Object> poll(UUID id, String token) throws IOException {
    var job = read(id);
    String expected = String.valueOf(job.get("pollHash"));
    String actual = MesDataPaths.sha256(
      (token == null ? "" : token).getBytes(
          java.nio.charset.StandardCharsets.UTF_8
        )
    );
    if (
      !java.security.MessageDigest.isEqual(
        expected.getBytes(java.nio.charset.StandardCharsets.UTF_8),
        actual.getBytes(java.nio.charset.StandardCharsets.UTF_8)
      )
    ) throw new ServiceException("任务查询凭证无效", 403);
    return publicJob(job);
  }

  public List<Map<String, Object>> list() throws IOException {
    Path dir = paths.resolve("maintenance/jobs");
    if (!Files.isDirectory(dir)) return List.of();
    try (var files = Files.list(dir)) {
      var results = new ArrayList<Map<String, Object>>();
      for (Path file : files
        .filter(p -> p.getFileName().toString().matches("[a-f0-9-]{36}\\.json"))
        .toList()) {
        var job = json.readValue(
          Files.readAllBytes(file),
          new TypeReference<LinkedHashMap<String, Object>>() {}
        );
        results.add(publicJob(job));
      }
      results.sort(
        Comparator.comparing((Map<String, Object> j) ->
          String.valueOf(j.get("createdAt"))
        ).reversed()
      );
      return results.stream().limit(300).toList();
    }
  }

  @PreDestroy
  public void close() {
    executor.shutdownNow();
  }
}
