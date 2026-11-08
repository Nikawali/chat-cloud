package org.example.connection.session;

import java.time.Duration;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.example.common.dto.ConnectionRoute;
import org.example.common.dto.KickConnectionRequest;
import org.example.common.routing.ConnectionTargetResolver;
import org.example.common.routing.RedisConnectionRoutes;
import org.example.common.support.InternalAuth;
import org.example.connection.client.ConnectionPeerClient;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.WebSocketSession;
import jakarta.annotation.PreDestroy;
import jakarta.annotation.PostConstruct;

/** 本地会话与全局路由协调。心跳只能续期自己仍拥有的那一代连接。 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ConnectionSessions {
    private final OnlineUserRegistry local;
    private final RedisConnectionRoutes routes;
    private final ConnectionTargetResolver targets;
    private final ConnectionPeerClient peers;

    @Value("${chat.connection.instance-id}")
    private String instanceId;
    @Value("${chat.connection.route-ttl-seconds:120}")
    private long ttlSeconds;
    @Value("${chat.internal.token}")
    private String token;

    @Value("${chat.connection.ws-idle-timeout-seconds:90}")
    private long idleSeconds;

    @PostConstruct
    public void validateConfiguration() {
        if (instanceId == null || instanceId.isBlank() || ttlSeconds <= idleSeconds || idleSeconds <= 30) {
            throw new IllegalStateException("连接实例标识不能为空，路由 TTL 必须大于空闲阈值，空闲阈值必须大于 30 秒心跳周期");
        }
    }

    // 串行化本实例的注册，避免本地会话替换顺序与 Redis 替换顺序相反。
    public synchronized WebSocketSession register(Long userId, WebSocketSession raw) {
        WebSocketSession safe = local.register(userId, raw);
        try {
            ConnectionRoute previous = routes.replace(userId, route(raw.getId()), Duration.ofSeconds(ttlSeconds));
            if (previous != null && !instanceId.equals(previous.instanceId())) {
                try {
                    targets.resolve(previous.instanceId()).ifPresent(uri ->
                            InternalAuth.requireSuccess(peers.kick(uri, token,
                                    new KickConnectionRequest(userId, previous.sessionId()))));
                } catch (Exception e) {
                    // 旧端仍会在下一次心跳检测到自己失去归属，主动关闭。
                    log.warn("跨实例关闭旧连接失败，等待所有权检查: userId={}", userId);
                }
            }
            if (!routes.matches(userId, route(raw.getId()))) {
                close(raw, OnlineUserRegistry.CLOSE_CODE_KICKED);
                return null;
            }
            return safe;
        } catch (RuntimeException e) {
            unregister(userId, raw);
            close(raw, 1011);
            throw e;
        }
    }

    public boolean touch(Long userId, WebSocketSession raw) {
        WebSocketSession current = local.get(userId);
        if (current == null || !current.getId().equals(raw.getId())) {
            close(raw, OnlineUserRegistry.CLOSE_CODE_KICKED);
            return false;
        }
        try {
            if (!routes.renew(userId, route(raw.getId()), Duration.ofSeconds(ttlSeconds))) {
                ConnectionRoute owner = routes.find(userId);
                kick(userId, raw.getId(), owner == null ? 1012 : OnlineUserRegistry.CLOSE_CODE_KICKED);
                return false;
            }
            local.touch(userId, raw);
            return true;
        } catch (RuntimeException e) {
            unregister(userId, raw);
            close(raw, 1011);
            return false;
        }
    }

    public void unregister(Long userId, WebSocketSession raw) {
        local.unregister(userId, raw);
        try { routes.remove(userId, route(raw.getId())); }
        catch (RuntimeException e) { log.warn("清理连接路由失败，等待 TTL: userId={}", userId); }
    }

    public WebSocketSession current(Long userId, String sessionId) {
        WebSocketSession socket = local.get(userId);
        if (socket == null || !socket.getId().equals(sessionId)) return null;
        return routes.matches(userId, route(sessionId)) ? socket : null;
    }

    public boolean kick(Long userId, String sessionId, int code) {
        WebSocketSession socket = local.get(userId);
        if (socket == null || !socket.getId().equals(sessionId)) return false;
        // 只摘除指定会话；迟到的关闭回调不会删掉新会话或新路由。
        unregister(userId, socket);
        close(socket, code);
        return true;
    }

    private ConnectionRoute route(String sessionId) { return new ConnectionRoute(instanceId, sessionId); }
    public java.util.Map<Long,WebSocketSession> currentBatch(java.util.List<org.example.common.dto.BatchPushFrameRequest.Target> targets) {
        var current=routes.findAll(targets.stream().map(org.example.common.dto.BatchPushFrameRequest.Target::userId).toList());
        var sockets=new java.util.LinkedHashMap<Long,WebSocketSession>();
        for(var target:targets) {
            var socket=local.get(target.userId());
            if(socket!=null && socket.getId().equals(target.sessionId()) && route(target.sessionId()).equals(current.get(target.userId())))
                sockets.put(target.userId(),socket);
        }
        return sockets;
    }

    private void close(WebSocketSession socket, int code) {
        try { socket.close(new CloseStatus(code)); }
        catch (Exception e) { log.debug("关闭会话失败: {}", socket.getId()); }
    }

    @PreDestroy
    public void shutdown() {
        for (Long userId : local.onlineUserIds()) {
            WebSocketSession socket = local.get(userId);
            if (socket != null) kick(userId, socket.getId(), 1001);
        }
    }
}
