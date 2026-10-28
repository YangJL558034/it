package com.ruoyi.mes.maintenance;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ruoyi.common.constant.CacheConstants;
import com.ruoyi.common.core.domain.model.LoginUser;
import com.ruoyi.common.core.redis.RedisCache;
import com.ruoyi.common.exception.ServiceException;
import com.ruoyi.common.utils.SecurityUtils;
import com.ruoyi.framework.web.service.SysPasswordService;
import com.ruoyi.mes.realtime.MesChanges;
import com.ruoyi.mes.storage.MesDataPaths;
import com.ruoyi.system.service.*;
import java.io.*;
import java.net.URI;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.zip.*;
import javax.sql.DataSource;
import org.quartz.Scheduler;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

@Service
public class MesBackupService {

  public static final String RESTORE_PHRASE = "恢复整个 MES 数据库和附件";
  private static final List<String> FOLDERS = List.of(
    "files",
    "branding",
    "configuration",
    "exports"
  );
  private final JdbcTemplate db;
  private final DataSource source;
  private final MesDataPaths paths;
  private final MesMaintenanceJobs jobs;
  private final MesMaintenanceState state;
  private final ObjectMapper json;
  private final RedisCache redis;
  private final ISysConfigService configs;
  private final ISysDictTypeService dictionaries;
  private final SysPasswordService passwords;
  private final Scheduler scheduler;
  private final MesChanges changes;
  private final String pgBin, password;
  private volatile Instant lastScheduledAttempt = Instant.EPOCH;

  public MesBackupService(
    JdbcTemplate db,
    DataSource source,
    MesDataPaths paths,
    MesMaintenanceJobs jobs,
    MesMaintenanceState state,
    ObjectMapper json,
    RedisCache redis,
    ISysConfigService configs,
    ISysDictTypeService dictionaries,
    SysPasswordService passwords,
    Scheduler scheduler,
    MesChanges changes,
    @Value("${mes.pg-bin:}") String pgBin,
    @Value("${spring.datasource.druid.master.password}") String password
  ) {
    this.db = db;
    this.source = source;
    this.paths = paths;
    this.jobs = jobs;
    this.state = state;
    this.json = json;
    this.redis = redis;
    this.configs = configs;
    this.dictionaries = dictionaries;
    this.passwords = passwords;
    this.scheduler = scheduler;
    this.changes = changes;
    this.pgBin = pgBin;
    this.password = password;
  }

  public void verifyPassword(String supplied) {
    if (
      supplied == null ||
      !passwords.matches(SecurityUtils.getLoginUser().getUser(), supplied)
    ) throw new ServiceException("当前管理员密码不正确", 403);
  }

  private Map<String, String> connection() throws Exception {
    try (var c = source.getConnection()) {
      var u = URI.create(c.getMetaData().getURL().substring(5));
      String database = u.getPath().substring(1);
      if (!database.matches("[A-Za-z0-9_]+")) throw new ServiceException(
        "数据库名称不受支持",
        422
      );
      return Map.of(
        "host",
        u.getHost(),
        "port",
        String.valueOf(u.getPort() < 0 ? 5432 : u.getPort()),
        "database",
        database,
        "user",
        c.getMetaData().getUserName()
      );
    }
  }

  private Path tool(String executable) {
    String suffix = System.getProperty("os.name").toLowerCase().contains("win")
      ? ".exe"
      : "";
    Path file = Path.of(pgBin).resolve(executable + suffix);
    if (
      pgBin.isBlank() || !Files.isRegularFile(file)
    ) throw new ServiceException(
      "尚未配置 PostgreSQL 备份工具目录 MES_PG_BIN",
      422
    );
    return file.toAbsolutePath();
  }

  private void process(List<String> args, Path log) throws Exception {
    var builder = new ProcessBuilder(args);
    builder.environment().put("PGPASSWORD", password);
    builder.environment().put("PGCLIENTENCODING", "UTF8");
    builder.redirectErrorStream(true);
    builder.redirectOutput(log.toFile());
    Process child = builder.start();
    if (!child.waitFor(15, TimeUnit.MINUTES)) {
      child.destroyForcibly();
      throw new IOException("数据库备份/恢复超时，已终止工具");
    }
    if (child.exitValue() != 0) {
      String error = Files.readString(log);
      throw new IOException(
        "PostgreSQL 工具执行失败：" +
        error.substring(0, Math.min(1000, error.length()))
      );
    }
  }

  private void dump(Path destination, Path log) throws Exception {
    var c = connection();
    process(
      List.of(
        tool("pg_dump").toString(),
        "--no-password",
        "-Fc",
        "--no-owner",
        "--no-privileges",
        "-h",
        c.get("host"),
        "-p",
        c.get("port"),
        "-U",
        c.get("user"),
        "-f",
        destination.toString(),
        c.get("database")
      ),
      log
    );
  }

  private void restoreDatabase(Path dump, Path log) throws Exception {
    var c = connection();
    process(
      List.of(
        tool("pg_restore").toString(),
        "--no-password",
        "--clean",
        "--if-exists",
        "--no-owner",
        "--no-privileges",
        "--exit-on-error",
        "--single-transaction",
        "-h",
        c.get("host"),
        "-p",
        c.get("port"),
        "-U",
        c.get("user"),
        "-d",
        c.get("database"),
        dump.toString()
      ),
      log
    );
  }

  private String schema() {
    return db.queryForObject(
      "select version from flyway_schema_history where success order by installed_rank desc limit 1",
      String.class
    );
  }

  public Map<String, Object> create() throws IOException {
    state.assertWritable();
    tool("pg_dump");
    return jobs.start(
      "Backup",
      SecurityUtils.getUserId(),
      SecurityUtils.getUsername(),
      job -> {
        backup(job);
        prune();
      }
    );
  }

  public Map<String, Object> protectedBackup(long user, String name)
    throws Exception {
    return jobs.internalBackup(user, name, this::backup);
  }

  public void backup(Map<String, Object> job) throws Exception {
    UUID id = UUID.fromString(job.get("id").toString());
    Path work = paths.resolve("maintenance/work/" + id);
    Files.createDirectories(work);
    Path dump = work.resolve("database.dump"), archive = paths.resolve(
      "backups/" + id + ".zip"
    ), temporary = work.resolve("backup.zip.tmp");
    Files.createDirectories(archive.getParent());
    try (
      var lock = source.getConnection();
      var statement = lock.createStatement()
    ) {
      statement.execute("select pg_advisory_lock(74215003)");
      try {
        jobs.update(job, "progress", "校验数据库与业务附件");
        verifyBusinessFiles();
        jobs.update(job, "progress", "备份 PostgreSQL 数据库");
        dump(dump, work.resolve("pg-dump.log"));
        var entries = new ArrayList<Map<String, Object>>();
        entries.add(entry("database.dump", dump));
        var files = new LinkedHashMap<String, Path>();
        files.put("database.dump", dump);
        for (String folder : FOLDERS) {
          Path dir = paths.resolve(folder);
          if (!Files.exists(dir)) continue;
          try (var tree = Files.walk(dir)) {
            for (Path file : tree.toList()) {
              if (Files.isSymbolicLink(file)) throw new IOException(
                "附件目录含符号链接，拒绝备份"
              );
              if (!Files.isRegularFile(file)) continue;
              String name =
                "data/" +
                paths.root().relativize(file).toString().replace('\\', '/');
              files.put(name, file);
              entries.add(entry(name, file));
            }
          }
        }
        var scope = new LinkedHashMap<String, Object>();
        for (String table : List.of(
          "mes_work_order",
          "mes_batch",
          "mes_unit",
          "mes_passage",
          "mes_test_result",
          "mes_sop_version",
          "sys_user"
        )) scope.put(
          table,
          db.queryForObject("select count(*) from " + table, Long.class)
        );
        var manifest = new LinkedHashMap<String, Object>();
        manifest.put("format", 1);
        manifest.put("application", "juxing-ruoyi-mes");
        manifest.put("schemaVersion", schema());
        manifest.put("createdAt", Instant.now().toString());
        manifest.put("sourceDatabase", connection().get("database"));
        manifest.put("scope", scope);
        manifest.put("entries", entries);
        manifest.put(
          "configuration",
          "sys_config is in the database; data/configuration is included; external service credentials remain outside this archive"
        );
        jobs.update(job, "progress", "保存附件、Logo 与校验清单");
        try (var zip = new ZipOutputStream(Files.newOutputStream(temporary))) {
          for (var pair : files.entrySet()) {
            zip.putNextEntry(new ZipEntry(pair.getKey()));
            Files.copy(pair.getValue(), zip);
            zip.closeEntry();
          }
          zip.putNextEntry(new ZipEntry("manifest.json"));
          zip.write(json.writeValueAsBytes(manifest));
          zip.closeEntry();
        }
        Files.move(temporary, archive, StandardCopyOption.REPLACE_EXISTING);
        job.put("fileName", id + ".zip");
        job.put("sha256", MesDataPaths.sha256(archive));
        job.put("bytes", Files.size(archive));
        job.put("scope", scope);
        job.put("schemaVersion", schema());
        job.put("fileCount", files.size() - 1);
        jobs.save(job);
      } finally {
        statement.execute("select pg_advisory_unlock(74215003)");
      }
    } finally {
      Files.deleteIfExists(dump);
      Files.deleteIfExists(temporary);
    }
  }

  private Map<String, Object> entry(String name, Path file) throws IOException {
    return Map.of(
      "path",
      name,
      "size",
      Files.size(file),
      "sha256",
      MesDataPaths.sha256(file)
    );
  }

  public Path archive(UUID id) throws IOException {
    var job = jobs.read(id);
    if (
      !"Backup".equals(job.get("kind")) ||
      !"Completed".equals(job.get("status"))
    ) throw new ServiceException("备份尚未完成或已删除", 409);
    Path file = paths.resolve("backups/" + id + ".zip");
    if (!Files.isRegularFile(file)) throw new ServiceException(
      "备份文件不存在",
      404
    );
    return file;
  }

  private boolean safeName(String name) {
    return (
      !name.contains("\\") &&
      !name.startsWith("/") &&
      !name.contains(":") &&
      !Arrays.asList(name.split("/")).contains("..") &&
      (name.equals("manifest.json") ||
        name.equals("database.dump") ||
        FOLDERS.stream().anyMatch(f -> name.startsWith("data/" + f + "/")))
    );
  }

  @SuppressWarnings("unchecked")
  public Map<String, Object> validate(UUID id, Path extract) throws Exception {
    Path archive = archive(id);
    var job = jobs.read(id);
    if (
      !Objects.equals(job.get("sha256"), MesDataPaths.sha256(archive))
    ) throw new ServiceException("备份文件摘要不匹配，拒绝恢复", 422);
    if (
      Files.size(archive) > 50L * 1024 * 1024 * 1024
    ) throw new ServiceException("备份超过当前恢复上限 50 GB", 422);
    try (var zip = new ZipFile(archive.toFile())) {
      var manifestEntry = zip.getEntry("manifest.json");
      if (
        manifestEntry == null ||
        manifestEntry.getSize() > 2 * 1024 * 1024 ||
        manifestEntry.getSize() < 1
      ) throw new ServiceException("缺少或损坏的备份清单", 422);
      Map<String, Object> manifest;
      try (var stream = zip.getInputStream(manifestEntry)) {
        manifest = json.readValue(
          stream,
          new TypeReference<LinkedHashMap<String, Object>>() {}
        );
      }
      if (
        !"juxing-ruoyi-mes".equals(manifest.get("application")) ||
        !Integer.valueOf(1).equals(manifest.get("format"))
      ) throw new ServiceException("不是兼容的 MES 备份", 422);
      if (
        !schema().equals(String.valueOf(manifest.get("schemaVersion")))
      ) throw new ServiceException(
        "备份数据库版本与当前程序不一致，请使用匹配版本恢复",
        422
      );
      var entries = (List<Map<String, Object>>) manifest.get("entries");
      if (
        entries == null ||
        entries.size() > 100000 ||
        zip.size() != entries.size() + 1
      ) throw new ServiceException("备份条目清单不一致", 422);
      long total = entries
        .stream()
        .mapToLong(e -> ((Number) e.get("size")).longValue())
        .sum();
      if (
        total < 0 || total > 100L * 1024 * 1024 * 1024
      ) throw new ServiceException("备份解压大小无效或超过 100 GB", 422);
      if (
        paths.root().toFile().getUsableSpace() < total * 2 + 256L * 1024 * 1024
      ) throw new ServiceException(
        "恢复临时空间不足，需至少预留两倍备份解压空间",
        422
      );
      Set<String> names = new HashSet<>();
      for (var item : entries) {
        String name = String.valueOf(item.get("path"));
        long expected = ((Number) item.get("size")).longValue();
        if (
          !safeName(name) ||
          name.equals("manifest.json") ||
          !names.add(name) ||
          expected < 0
        ) throw new ServiceException("备份包含无效或重复路径", 422);
        var sourceEntry = zip.getEntry(name);
        if (
          sourceEntry == null ||
          sourceEntry.isDirectory() ||
          sourceEntry.getSize() != expected
        ) throw new ServiceException("备份文件大小不匹配", 422);
        Path target = extract == null
          ? null
          : extract.resolve(name).normalize();
        if (
          target != null && !target.startsWith(extract)
        ) throw new ServiceException("无效备份路径", 422);
        if (target != null) Files.createDirectories(target.getParent());
        try (
          var input = zip.getInputStream(sourceEntry);
          var output = target == null
            ? OutputStream.nullOutputStream()
            : Files.newOutputStream(target)
        ) {
          var hash = java.security.MessageDigest.getInstance("SHA-256");
          byte[] buffer = new byte[65536];
          long read = 0;
          int n;
          while ((n = input.read(buffer)) != -1) {
            read += n;
            if (read > expected) throw new ServiceException(
              "备份解压大小超过清单",
              422
            );
            hash.update(buffer, 0, n);
            output.write(buffer, 0, n);
          }
          if (
            read != expected ||
            !java.util.HexFormat.of()
              .formatHex(hash.digest())
              .equals(item.get("sha256"))
          ) throw new ServiceException("备份附件或数据库校验失败", 422);
        }
      }
      if (!names.contains("database.dump")) throw new ServiceException(
        "备份不包含 PostgreSQL 数据库",
        422
      );
      return manifest;
    }
  }

  public Map<String, Object> preview(UUID id) throws Exception {
    state.assertWritable();
    var manifest = validate(id, null);
    String token = jobs.secret();
    var plan = new LinkedHashMap<String, Object>();
    UUID planId = UUID.randomUUID();
    plan.put("id", planId.toString());
    plan.put("kind", "RestorePlan");
    plan.put("operatorId", SecurityUtils.getUserId());
    plan.put("backupId", id.toString());
    plan.put("backupSha", jobs.read(id).get("sha256"));
    plan.put("expires", Instant.now().plusSeconds(600).toString());
    plan.put(
      "tokenHash",
      MesDataPaths.sha256(
        token.getBytes(java.nio.charset.StandardCharsets.UTF_8)
      )
    );
    plan.put("manifest", manifest);
    paths.atomicWrite(
      paths.resolve("maintenance/plans/" + planId + ".json"),
      json.writeValueAsBytes(plan)
    );
    return Map.of(
      "planId",
      planId,
      "confirmationToken",
      token,
      "confirmationPhrase",
      RESTORE_PHRASE,
      "scope",
      manifest,
      "targetDatabase",
      connection().get("database")
    );
  }

  public Map<String, Object> readPlan(UUID id, String kind, String token)
    throws IOException {
    Path file = paths.resolve("maintenance/plans/" + id + ".json");
    if (!Files.isRegularFile(file)) throw new ServiceException(
      "确认范围不存在或已使用",
      409
    );
    var plan = json.readValue(
      Files.readAllBytes(file),
      new TypeReference<LinkedHashMap<String, Object>>() {}
    );
    if (
      !kind.equals(plan.get("kind")) ||
      ((Number) plan.get("operatorId")).longValue() !=
      SecurityUtils.getUserId() ||
      Instant.parse(plan.get("expires").toString()).isBefore(Instant.now()) ||
      !Objects.equals(
        plan.get("tokenHash"),
        MesDataPaths.sha256(
          (token == null ? "" : token).getBytes(
              java.nio.charset.StandardCharsets.UTF_8
            )
        )
      )
    ) throw new ServiceException("范围确认已失效，请重新预览", 409);
    return plan;
  }

  public Map<String, Object> restore(
    UUID planId,
    String token,
    String phrase,
    String suppliedPassword
  ) throws Exception {
    state.assertWritable();
    verifyPassword(suppliedPassword);
    if (!RESTORE_PHRASE.equals(phrase)) throw new ServiceException(
      "请输入完整恢复确认文字",
      422
    );
    var plan = readPlan(planId, "RestorePlan", token);
    UUID backupId = UUID.fromString(plan.get("backupId").toString());
    if (
      !Objects.equals(jobs.read(backupId).get("sha256"), plan.get("backupSha"))
    ) throw new ServiceException("备份范围已变化", 409);
    var user = SecurityUtils.getLoginUser();
    var result = jobs.start(
      "Restore",
      user.getUserId(),
      user.getUsername(),
      job -> restoreJob(job, backupId)
    );
    Files.delete(paths.resolve("maintenance/plans/" + planId + ".json"));
    return result;
  }

  private void restoreJob(Map<String, Object> job, UUID backupId)
    throws Exception {
    state.begin("restore " + job.get("id"));
    boolean priorRunning = !scheduler.isInStandbyMode();
    boolean touched = false;
    Map<String, Object> safety = null;
    try (var lease = state.enterExclusive()) {
      scheduler.standby();
      Path work = paths.resolve("maintenance/work/" + job.get("id"));
      Files.createDirectories(work);
      try {
        jobs.update(job, "progress", "再次校验恢复范围与所有附件");
        Path extract = work.resolve("source");
        Files.createDirectories(extract);
        validate(backupId, extract);
        jobs.update(job, "progress", "恢复前备份当前数据库和附件");
        safety = protectedBackup(
          ((Number) job.get("operatorId")).longValue(),
          job.get("operatorName").toString()
        );
        jobs.update(job, "preBackupId", safety.get("id"));
        jobs.update(job, "progress", "恢复 PostgreSQL 数据库");
        touched = true;
        restoreDatabase(
          extract.resolve("database.dump"),
          work.resolve("pg-restore.log")
        );
        jobs.update(job, "progress", "恢复并验证 SOP、附件与平台标识");
        applyFiles(extract, work.resolve("previous"));
        afterRestore();
        state.finish();
        changes.publish();
      } catch (Exception failure) {
        if (!touched) {
          state.finish();
          throw failure;
        }
        try {
          jobs.update(job, "progress", "恢复失败，正在回退到操作前备份");
          Path rollback = work.resolve("safety");
          Files.createDirectories(rollback);
          validate(UUID.fromString(safety.get("id").toString()), rollback);
          restoreDatabase(
            rollback.resolve("database.dump"),
            work.resolve("pg-rollback.log")
          );
          applyFiles(rollback, work.resolve("failed-state"));
          afterRestore();
          jobs.update(job, "rolledBack", true);
          state.finish();
        } catch (Exception rollbackError) {
          jobs.update(
            job,
            "rollbackError",
            rollbackError.getMessage() == null
              ? "回退失败"
              : rollbackError.getMessage()
          );
          throw new IOException(
            "恢复及自动回退未完成，系统保持维护状态；须使用操作前备份进行离线恢复",
            failure
          );
        }
        throw failure;
      }
    } finally {
      if (!state.active() && priorRunning) scheduler.start();
    }
  }

  private void applyFiles(Path extract, Path previous) throws IOException {
    Files.createDirectories(previous);
    for (String folder : FOLDERS) {
      Path live = paths.resolve(folder), saved = previous.resolve(
        folder
      ), restored = extract.resolve("data/" + folder);
      if (Files.exists(live)) Files.move(
        live,
        saved,
        StandardCopyOption.REPLACE_EXISTING
      );
      if (Files.exists(restored)) {
        Files.createDirectories(live.getParent());
        Files.move(restored, live, StandardCopyOption.REPLACE_EXISTING);
      } else Files.createDirectories(live);
    }
  }

  private void afterRestore() throws Exception {
    verifyBusinessFiles();
    db.queryForObject("select 1", Integer.class);
    db.update("delete from mes_terminal_credential");
    db.update("delete from mes_scan_context");
    redis.deleteObject(redis.keys(CacheConstants.LOGIN_TOKEN_KEY + "*"));
    configs.resetConfigCache();
    dictionaries.resetDictCache();
  }

  public void verifyBusinessFiles() throws IOException {
    for (var file : db.queryForList(
      "select storage_name,sha256,size from mes_sop_file"
    )) {
      Path path = paths.resolve(file.get("storage_name").toString());
      if (
        !Files.isRegularFile(path) ||
        Files.size(path) != ((Number) file.get("size")).longValue() ||
        !MesDataPaths.sha256(path).equals(file.get("sha256"))
      ) throw new IOException(
        "SOP 附件与数据库不一致，拒绝继续：" + file.get("storage_name")
      );
    }
    var branding = db.queryForMap(
      "select logo_key from mes_branding where id=1"
    );
    if (branding.get("logo_key") != null) {
      String key = branding.get("logo_key").toString();
      Path file = paths.resolve("branding/" + key + ".png");
      if (
        !Files.isRegularFile(file) || !MesDataPaths.sha256(file).equals(key)
      ) throw new IOException("平台 Logo 与数据库不一致");
    }
  }

  public Map<String, Object> settings() {
    var result = new LinkedHashMap<String, Object>();
    for (var row : db.queryForList(
      "select config_key,config_value from sys_config where config_key in ('mes.backup.enabled','mes.backup.intervalHours','mes.backup.retentionDays')"
    )) {
      String key = row
        .get("config_key")
        .toString()
        .substring("mes.backup.".length());
      result.put(
        key,
        key.equals("enabled")
          ? Boolean.parseBoolean(row.get("config_value").toString())
          : Integer.parseInt(row.get("config_value").toString())
      );
    }
    result.put(
      "toolsAvailable",
      !pgBin.isBlank() &&
      Files.isRegularFile(
        Path.of(pgBin).resolve(
          System.getProperty("os.name").toLowerCase().contains("win")
            ? "pg_dump.exe"
            : "pg_dump"
        )
      )
    );
    return result;
  }

  public void settings(Map<String, Object> body) {
    state.assertWritable();
    if (!(body.get("enabled") instanceof Boolean)) throw new ServiceException(
      "备份开关无效",
      422
    );
    int interval = integer(body.get("intervalHours"), 1, 720), retention =
      integer(body.get("retentionDays"), 1, 3650);
    if (Boolean.TRUE.equals(body.get("enabled"))) tool("pg_dump");
    for (var entry : Map.of(
      "mes.backup.enabled",
      body.get("enabled").toString(),
      "mes.backup.intervalHours",
      String.valueOf(interval),
      "mes.backup.retentionDays",
      String.valueOf(retention)
    ).entrySet()) db.update(
      "update sys_config set config_value=?,update_by=?,update_time=now() where config_key=?",
      entry.getValue(),
      SecurityUtils.getUsername(),
      entry.getKey()
    );
    configs.resetConfigCache();
  }

  private int integer(Object value, int min, int max) {
    if (
      !(value instanceof Number n) ||
      n.doubleValue() != n.longValue() ||
      n.longValue() < min ||
      n.longValue() > max
    ) throw new ServiceException("策略数值超出允许范围", 422);
    return n.intValue();
  }

  public void delete(UUID id, boolean retention) throws IOException {
    state.assertWritable();
    if (jobs.busy()) throw new ServiceException(
      "运维任务执行期间不能删除备份",
      409
    );
    var job = jobs.read(id);
    if (retention && Boolean.TRUE.equals(job.get("protected"))) return;
    archive(id);
    Files.delete(paths.resolve("backups/" + id + ".zip"));
    jobs.update(job, "status", "Deleted");
  }

  private void prune() throws IOException {
    int days = ((Number) settings().get("retentionDays")).intValue();
    Instant cutoff = Instant.now().minusSeconds(days * 86400L);
    for (var job : jobs.list()) if (
      "Backup".equals(job.get("kind")) &&
      "Completed".equals(job.get("status")) &&
      !Boolean.TRUE.equals(job.get("protected")) &&
      Instant.parse(job.get("createdAt").toString()).isBefore(cutoff)
    ) {
      UUID id = UUID.fromString(job.get("id").toString());
      Files.deleteIfExists(paths.resolve("backups/" + id + ".zip"));
      job.put("deletionReason", "retention");
      jobs.update(job, "status", "Deleted");
    }
  }

  @Scheduled(fixedDelay = 60000, initialDelay = 60000)
  public void scheduled() {
    if (
      state.active() ||
      jobs.busy() ||
      lastScheduledAttempt.plusSeconds(900).isAfter(Instant.now())
    ) return;
    try {
      var policy = settings();
      if (!Boolean.TRUE.equals(policy.get("enabled"))) return;
      int hours = ((Number) policy.get("intervalHours")).intValue();
      var all = jobs.list();
      Instant last = all
        .stream()
        .filter(
          j ->
            "Backup".equals(j.get("kind")) &&
            "Completed".equals(j.get("status"))
        )
        .map(j -> Instant.parse(j.get("createdAt").toString()))
        .max(Comparator.naturalOrder())
        .orElse(Instant.EPOCH);
      if (last.plusSeconds(hours * 3600L).isAfter(Instant.now())) return;
      lastScheduledAttempt = Instant.now();
      jobs.start("Backup", 0L, "scheduler", job -> {
        backup(job);
        prune();
      });
    } catch (Exception ignored) {
      lastScheduledAttempt = Instant.now();
    }
  }
}
