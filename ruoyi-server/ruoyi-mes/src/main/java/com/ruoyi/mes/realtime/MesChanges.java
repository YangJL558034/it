package com.ruoyi.mes.realtime;

import java.time.Instant;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.web.socket.*;
import org.springframework.web.socket.handler.ConcurrentWebSocketSessionDecorator;
import org.springframework.web.socket.handler.TextWebSocketHandler;

/** Public invalidation messages contain no production data, credentials or callable commands. */
@Component
public class MesChanges extends TextWebSocketHandler {

  private final ConcurrentHashMap<String, WebSocketSession> sessions =
    new ConcurrentHashMap<>();

  @Override
  public void afterConnectionEstablished(WebSocketSession session)
    throws Exception {
    if (sessions.size() >= 1024) {
      session.close(CloseStatus.SERVICE_OVERLOAD);
      return;
    }
    session.setTextMessageSizeLimit(1024);
    var wrapped = new ConcurrentWebSocketSessionDecorator(session, 1000, 8192);
    sessions.put(session.getId(), wrapped);
    wrapped.sendMessage(new TextMessage("{\"type\":\"connected\"}"));
  }

  @Override
  protected void handleTextMessage(
    WebSocketSession session,
    TextMessage message
  ) throws Exception {
    var wrapped = sessions.get(session.getId());
    if (
      wrapped != null && "ping".equals(message.getPayload())
    ) wrapped.sendMessage(new TextMessage("{\"type\":\"pong\"}"));
  }

  @Override
  public void afterConnectionClosed(
    WebSocketSession session,
    CloseStatus status
  ) {
    sessions.remove(session.getId());
  }

  @Override
  public void handleTransportError(WebSocketSession session, Throwable error)
    throws Exception {
    sessions.remove(session.getId());
    session.close();
  }

  public void publish() {
    if (TransactionSynchronizationManager.isSynchronizationActive()) {
      TransactionSynchronizationManager.registerSynchronization(
        new TransactionSynchronization() {
          @Override
          public void afterCommit() {
            broadcast();
          }
        }
      );
    } else broadcast();
  }

  private void broadcast() {
    var message = new TextMessage(
      "{\"type\":\"changed\",\"at\":\"" + Instant.now() + "\"}"
    );
    sessions.forEach((id, session) -> {
      try {
        if (session.isOpen()) session.sendMessage(message);
        else sessions.remove(id);
      } catch (Exception e) {
        sessions.remove(id);
        try {
          session.close();
        } catch (Exception ignored) {}
      }
    });
  }
}
