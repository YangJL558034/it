package com.ruoyi.mes.monitor;

import com.alibaba.druid.pool.DruidDataSource;
import com.ruoyi.common.core.domain.AjaxResult;
import com.ruoyi.mes.storage.MesDataPaths;
import java.util.*;
import javax.sql.DataSource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

@RestController
public class MesDatabaseMonitor {

  private final JdbcTemplate db;
  private final DataSource source;
  private final MesDataPaths paths;

  public MesDatabaseMonitor(
    JdbcTemplate db,
    DataSource source,
    MesDataPaths paths
  ) {
    this.db = db;
    this.source = source;
    this.paths = paths;
  }

  @GetMapping("/mes/monitor/database")
  @PreAuthorize("@ss.hasPermi('monitor:druid:list')")
  public AjaxResult database() {
    long start = System.nanoTime();
    db.queryForObject("select 1", Integer.class);
    long latency = (System.nanoTime() - start) / 1000000;
    var info = db.queryForMap(
      "select current_database() as name,current_setting('server_version') as version,pg_database_size(current_database()) as bytes,current_setting('max_connections')::integer as \"maxConnections\""
    );
    var stats = db.queryForMap(
      "select numbackends as connections,xact_commit as commits,xact_rollback as rollbacks,blks_hit as hits,blks_read as reads,deadlocks,temp_bytes as \"tempBytes\",stats_reset as \"statsReset\" from pg_stat_database where datname=current_database()"
    );
    var tables = db.queryForList(
      "select schemaname as schema,relname as name,n_live_tup as \"estimatedRows\",n_dead_tup as \"deadRows\",pg_total_relation_size(relid) as bytes,seq_scan as \"sequentialScans\",idx_scan as \"indexScans\",last_autovacuum as \"lastAutoVacuum\" from pg_stat_user_tables order by pg_total_relation_size(relid) desc"
    );
    // Query text is deliberately excluded: it may contain personal data or credentials.
    var sessions = db.queryForList(
      "select coalesce(state,'unknown') as state,coalesce(wait_event_type,'none') as \"waitType\",count(*) as count from pg_stat_activity where datname=current_database() group by state,wait_event_type order by count(*) desc"
    );
    Map<String, Object> pool = new LinkedHashMap<>();
    if (source instanceof DruidDataSource d) {
      pool.put("active", d.getActiveCount());
      pool.put("idle", d.getPoolingCount());
      pool.put("max", d.getMaxActive());
      pool.put("created", d.getCreateCount());
      pool.put("errors", d.getConnectErrorCount());
    }
    var disk = paths.root().toFile();
    return AjaxResult.success(
      Map.of(
        "database",
        info,
        "statistics",
        stats,
        "tables",
        tables,
        "sessions",
        sessions,
        "pool",
        pool,
        "latencyMs",
        latency,
        "disk",
        Map.of(
          "usableBytes",
          disk.getUsableSpace(),
          "totalBytes",
          disk.getTotalSpace()
        ),
        "at",
        java.time.Instant.now()
      )
    );
  }
}
