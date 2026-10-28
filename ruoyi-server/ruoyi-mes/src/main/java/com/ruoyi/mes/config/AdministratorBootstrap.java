package com.ruoyi.mes.config;

import com.ruoyi.common.utils.SecurityUtils;
import java.nio.file.Files;
import java.nio.file.Path;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/** Initial provisioning only: never changes an existing account or logs its secret. */
@Component
public class AdministratorBootstrap implements ApplicationRunner {

  private final JdbcTemplate jdbc;
  private final String passwordFile;

  public AdministratorBootstrap(
    JdbcTemplate jdbc,
    @Value("${mes.bootstrap-password-file:}") String passwordFile
  ) {
    this.jdbc = jdbc;
    this.passwordFile = passwordFile;
  }

  @Override
  @Transactional
  public void run(ApplicationArguments arguments) throws Exception {
    jdbc.execute("select pg_advisory_xact_lock(74215002)");
    if (
      jdbc.queryForObject("select count(*) from sys_user", Long.class) != 0
    ) return;
    if (passwordFile.isBlank()) throw new IllegalStateException(
      "首次初始化必须指定管理员密码文件；无默认生产密码"
    );
    String password = Files.readString(Path.of(passwordFile)).strip();
    if (
      password.length() < 12 ||
      password.length() > 50 ||
      password.getBytes(java.nio.charset.StandardCharsets.UTF_8).length > 72 ||
      !password.matches("(?s).*[A-Za-z].*") ||
      !password.matches("(?s).*[0-9].*")
    ) throw new IllegalArgumentException(
      "初始管理员密码须为 12–50 位并包含字母和数字"
    );
    jdbc.update(
      "insert into sys_user(user_id,dept_id,user_name,nick_name,password,status,del_flag,create_by,create_time,pwd_update_date) values(1,100,'admin','系统管理员',?,'0','0','installer',CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)",
      SecurityUtils.encryptPassword(password)
    );
    jdbc.update("insert into sys_user_role(user_id,role_id) values(1,1)");
  }
}
