package com.ruoyi.mes.production;

import com.ruoyi.common.annotation.Anonymous;
import com.ruoyi.common.constant.Constants;
import com.ruoyi.common.core.domain.entity.SysUser;
import com.ruoyi.common.core.domain.model.LoginUser;
import com.ruoyi.common.exception.ServiceException;
import com.ruoyi.common.utils.SecurityUtils;
import com.ruoyi.framework.manager.AsyncManager;
import com.ruoyi.framework.manager.factory.AsyncFactory;
import com.ruoyi.framework.web.service.*;
import com.ruoyi.mes.maintenance.MesMaintenanceState;
import com.ruoyi.mes.storage.MesDataPaths;
import com.ruoyi.system.service.ISysUserService;
import jakarta.servlet.http.*;
import java.nio.charset.StandardCharsets;
import java.time.*;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

/** Uses the RuoYi user, password encoder, permissions, audit and JWT; no second account system. */
@RestController
public class MesTerminalAuth {

  private final JdbcTemplate db;
  private final ISysUserService users;
  private final SysPasswordService passwords;
  private final SysPermissionService permissions;
  private final SysLoginService login;
  private final TokenService tokens;
  private final MesMaintenanceState maintenance;

  public MesTerminalAuth(
    JdbcTemplate db,
    ISysUserService users,
    SysPasswordService passwords,
    SysPermissionService permissions,
    SysLoginService login,
    TokenService tokens,
    MesMaintenanceState maintenance
  ) {
    this.db = db;
    this.users = users;
    this.passwords = passwords;
    this.permissions = permissions;
    this.login = login;
    this.tokens = tokens;
    this.maintenance = maintenance;
  }

  private String hash(String s) {
    return MesDataPaths.sha256(s.getBytes(StandardCharsets.UTF_8));
  }

  private void valid(SysUser user) {
    if (
      user == null ||
      !"0".equals(user.getStatus()) ||
      !"0".equals(user.getDelFlag())
    ) throw new ServiceException("账号或密码错误，或账号已停用", 401);
    if (
      !user.isAdmin() &&
      !permissions.getMenuPermission(user).contains("mes:station:operate")
    ) throw new ServiceException("账号没有工位作业权限", 403);
  }

  private Map<String, Object> issue(SysUser user) {
    valid(user);
    var loginUser = new LoginUser(
      user.getUserId(),
      user.getDeptId(),
      user,
      permissions.getMenuPermission(user)
    );
    var result = new LinkedHashMap<String, Object>();
    result.put("authenticated", true);
    result.put("token", tokens.createToken(loginUser));
    result.put(
      "user",
      Map.of(
        "id",
        user.getUserId().toString(),
        "account",
        user.getUserName(),
        "name",
        user.getNickName(),
        "role",
        user.isAdmin() ? "系统管理员" : "工位操作员"
      )
    );
    return result;
  }

  private String cookie(HttpServletRequest request) {
    if (request.getCookies() != null) for (var c : request.getCookies()) if (
      c.getName().equals("MES_STATION")
    ) return c.getValue();
    return null;
  }

  private void cookie(
    HttpServletResponse response,
    String value,
    int age,
    HttpServletRequest request
  ) {
    response.addHeader(
      "Set-Cookie",
      "MES_STATION=" +
      value +
      "; Path=/api/auth/station; Max-Age=" +
      age +
      "; HttpOnly; SameSite=Strict" +
      (request.isSecure() ? "; Secure" : "")
    );
  }

  @Anonymous
  @PostMapping("/api/auth/station/login")
  @Transactional
  public Map<String, Object> login(
    @RequestBody Map<String, Object> body,
    HttpServletRequest request,
    HttpServletResponse response
  ) {
    db.execute("select pg_advisory_xact_lock(74215003)");
    maintenance.assertWritable();
    String account = MesRecords.text(body, "account", 30), password =
      MesRecords.text(body, "password", 50);
    login.loginPreCheck(account, password);
    var user = users.selectUserByUserName(account);
    if (user == null || !passwords.matches(user, password)) {
      AsyncManager.me()
        .execute(
          AsyncFactory.recordLogininfor(
            account,
            Constants.LOGIN_FAIL,
            "工位端密码校验失败"
          )
        );
      throw new ServiceException("账号或密码错误", 401);
    }
    var result = issue(user);
    login.recordLoginInfo(user.getUserId());
    AsyncManager.me()
      .execute(
        AsyncFactory.recordLogininfor(
          account,
          Constants.LOGIN_SUCCESS,
          "工位端登录成功"
        )
      );
    if (Boolean.TRUE.equals(body.get("rememberStation"))) {
      byte[] random = new byte[32];
      new java.security.SecureRandom().nextBytes(random);
      String secret = Base64.getUrlEncoder()
        .withoutPadding()
        .encodeToString(random);
      db.update(
        "insert into mes_terminal_credential(id,user_id,token_hash,pwd_stamp,expires) values(?,?,?,?,?)",
        UUID.randomUUID(),
        user.getUserId(),
        hash(secret),
        hash(user.getPassword()),
        java.sql.Timestamp.from(Instant.now().plusSeconds(365L * 86400))
      );
      if (Boolean.TRUE.equals(body.get("native"))) result.put(
        "stationToken",
        secret
      );
      else cookie(response, secret, 365 * 86400, request);
    }
    return result;
  }

  @Anonymous
  @PostMapping("/api/auth/station/resume")
  @Transactional
  public Map<String, Object> resume(
    @RequestBody Map<String, Object> body,
    HttpServletRequest request
  ) {
    if (
      !"1".equals(request.getHeader("X-MES-Station-Auth"))
    ) throw new ServiceException("缺少工位认证请求标识", 403);
    db.execute("select pg_advisory_xact_lock(74215003)");
    maintenance.assertWritable();
    String secret = body.get("stationToken") instanceof String s
      ? s
      : cookie(request);
    if (secret == null || secret.length() > 128) throw new ServiceException(
      "请登录工位",
      401
    );
    var rows = db.queryForList(
      "select * from mes_terminal_credential where token_hash=? and expires>now()",
      hash(secret)
    );
    if (rows.isEmpty()) throw new ServiceException("请重新登录工位", 401);
    var row = rows.get(0);
    var user = users.selectUserById(((Number) row.get("user_id")).longValue());
    valid(user);
    if (
      !hash(user.getPassword()).equals(row.get("pwd_stamp"))
    ) throw new ServiceException("密码已变更，请重新登录", 401);
    db.update(
      "update mes_terminal_credential set last_used=now() where id=?",
      row.get("id")
    );
    return issue(user);
  }

  @Anonymous
  @PostMapping("/api/auth/station/forget")
  @Transactional
  public Map<String, Object> forget(
    @RequestBody Map<String, Object> body,
    HttpServletRequest request,
    HttpServletResponse response
  ) {
    if (
      !"1".equals(request.getHeader("X-MES-Station-Auth"))
    ) throw new ServiceException("缺少认证请求标识", 403);
    db.execute("select pg_advisory_xact_lock(74215003)");
    maintenance.assertWritable();
    String secret = body.get("stationToken") instanceof String s
      ? s
      : cookie(request);
    if (secret != null) db.update(
      "delete from mes_terminal_credential where token_hash=?",
      hash(secret)
    );
    cookie(response, "", 0, request);
    return Map.of("forgotten", true);
  }

  @PostMapping("/api/auth/logout")
  public Map<String, Object> logout() {
    tokens.delLoginUser(SecurityUtils.getLoginUser().getToken());
    return Map.of("loggedOut", true);
  }
}
