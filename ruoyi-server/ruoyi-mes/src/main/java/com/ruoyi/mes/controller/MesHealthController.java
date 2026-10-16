package com.ruoyi.mes.controller;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import com.ruoyi.common.annotation.Anonymous;
import com.ruoyi.common.core.domain.AjaxResult;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.data.redis.core.StringRedisTemplate;

@RestController
public class MesHealthController {
    private final JdbcTemplate jdbc;
    private final StringRedisTemplate redis;
    private final Path dataDirectory;
    public MesHealthController(JdbcTemplate jdbc, StringRedisTemplate redis,
            @Value("${mes.data-directory}") String dataDirectory) {
        this.jdbc = jdbc; this.redis = redis; this.dataDirectory = Path.of(dataDirectory);
    }

    @Anonymous
    @GetMapping("/api/health/live")
    public Map<String, Object> live() { return Map.of("status", "up", "backend", "ruoyi-java", "apiVersion", "2.0-migration"); }

    @PreAuthorize("@ss.hasPermi('mes:system:health')")
    @GetMapping("/api/health/ready")
    public AjaxResult ready() throws Exception {
        jdbc.queryForObject("select 1", Integer.class);
        try (var connection = redis.getConnectionFactory().getConnection()) {
            if (!"PONG".equals(connection.ping())) throw new IllegalStateException("缓存健康检查失败");
        }
        Files.createDirectories(dataDirectory.resolve("health"));
        Path probe = Files.createTempFile(dataDirectory.resolve("health"), "probe-", ".tmp");
        try {
            Files.writeString(probe, "mes-ready");
            if (!Files.readString(probe).equals("mes-ready")) throw new IllegalStateException("文件读写校验失败");
        } finally { Files.deleteIfExists(probe); }
        return AjaxResult.success(Map.of("database", true, "cache", true, "files", true));
    }
}
