package org.example.connection.service;

import java.util.Map;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.example.connection.session.ConnectionSessions;
import org.example.common.routing.RedisConnectionRoutes;
import org.example.common.dto.ConnectionRoute;
import org.example.connection.ws.ChatFrameCodec;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;

/** 实际写入当前实例的 WS 连接。发送成功仅表示写出，不代表客户端已 ACK。 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ConnectionSender {
    private final ConnectionSessions sessions;
    private final RedisConnectionRoutes routes;

    public boolean isOnline(Long userId) {
        ConnectionRoute route = routes.find(userId);
        return route != null && sessions.current(userId, route.sessionId()) != null;
    }
    public java.util.List<Long> sendBatch(org.example.common.dto.BatchPushFrameRequest request) {
        var sockets=sessions.currentBatch(request.targets());
        var message=new TextMessage(ChatFrameCodec.toJson(request.frame()));
        var sent=new java.util.ArrayList<Long>();
        for(var entry:sockets.entrySet()) try {
            entry.getValue().sendMessage(message);
            sent.add(entry.getKey());
        } catch(Exception e) {log.debug("Batch WS write failed: user={}",entry.getKey(),e);}
        return sent;
    }

    public boolean send(Long userId, String sessionId, Map<String, Object> frame) {
        WebSocketSession session = sessions.current(userId, sessionId);
        if (session == null) {
            return false;
        }
        try {
            session.sendMessage(new TextMessage(ChatFrameCodec.toJson(frame)));
            return true;
        } catch (Exception e) {
            log.warn("WS 发送失败: userId={}, type={}, 原因={}", userId, frame.get("type"), e.getMessage());
            return false;
        }
    }
}
