package com.ruoyi.mes.realtime;

import org.springframework.context.annotation.Configuration;
import org.springframework.web.socket.config.annotation.*;

@Configuration
@EnableWebSocket
public class MesWebSocketConfiguration implements WebSocketConfigurer {

  private final MesChanges changes;

  public MesWebSocketConfiguration(MesChanges changes) {
    this.changes = changes;
  }

  @Override
  public void registerWebSocketHandlers(WebSocketHandlerRegistry registry) {
    registry
      .addHandler(changes, "/ws/notifications")
      .setAllowedOriginPatterns("*");
  }
}
