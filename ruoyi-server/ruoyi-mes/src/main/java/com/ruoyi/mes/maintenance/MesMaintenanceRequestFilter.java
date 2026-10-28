package com.ruoyi.mes.maintenance;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.ruoyi.common.exception.ServiceException;
import jakarta.servlet.*;
import jakarta.servlet.http.*;
import java.io.IOException;
import java.util.*;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 20)
public class MesMaintenanceRequestFilter extends OncePerRequestFilter {

  private final MesMaintenanceState state;
  private final MesMaintenanceJobs jobs;
  private final ObjectMapper json;

  public MesMaintenanceRequestFilter(
    MesMaintenanceState state,
    MesMaintenanceJobs jobs,
    ObjectMapper json
  ) {
    this.state = state;
    this.jobs = jobs;
    this.json = json;
  }

  @Override
  protected void doFilterInternal(
    HttpServletRequest request,
    HttpServletResponse response,
    FilterChain chain
  ) throws IOException, ServletException {
    String path = request.getRequestURI();
    if (path.startsWith("/api/maintenance/status/")) {
      try {
        var id = UUID.fromString(
          path.substring("/api/maintenance/status/".length())
        );
        write(
          response,
          200,
          jobs.poll(id, request.getHeader("X-MES-Maintenance-Token"))
        );
      } catch (Exception e) {
        write(
          response,
          403,
          Map.of("code", 403, "message", "任务查询凭证无效")
        );
      }
      return;
    }
    if (
      state.active() &&
      (path.equals("/api/health") || path.equals("/api/health/live"))
    ) {
      write(
        response,
        200,
        Map.of(
          "status",
          "maintenance",
          "backend",
          "ruoyi-java",
          "maintenance",
          true
        )
      );
      return;
    }
    boolean staticResource =
      path.startsWith("/admin/") ||
      path.equals("/station") ||
      path.equals("/tv") ||
      path.startsWith("/assets/") ||
      path.equals("/ws/notifications");
    if (staticResource) {
      chain.doFilter(request, response);
      return;
    }
    AutoCloseable lease;
    try {
      lease = state.enterRequest();
    } catch (ServiceException e) {
      write(response, 503, Map.of("code", 503, "message", e.getMessage()));
      return;
    }
    try {
      chain.doFilter(request, response);
    } finally {
      try {
        lease.close();
      } catch (Exception e) {
        throw new ServletException(e);
      }
    }
  }

  private void write(HttpServletResponse response, int status, Object value)
    throws IOException {
    response.setStatus(status);
    response.setContentType("application/json;charset=UTF-8");
    json.writeValue(response.getWriter(), value);
  }
}
