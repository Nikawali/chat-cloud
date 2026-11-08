package org.example.message.ws;

import java.util.Map;
import org.example.common.dto.ConnectionRoute;
import org.example.common.routing.RedisConnectionRoutes;
import org.example.common.routing.ConnectionTargetResolver;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.example.common.dto.PushFrameRequest;
import org.example.common.protocol.ChatFrames;
import org.example.common.support.InternalAuth;
import org.example.message.client.ConnectionClient;
import org.example.message.vo.MessageVO;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/** 根据 Redis 用户连接归属定向投递；路由切换时最多重查并重试一次。 */
@Slf4j
@Component
@RequiredArgsConstructor
public class RemoteMessagePusher implements MessagePusher {
    private final ConnectionClient connectionClient;
    private final RedisConnectionRoutes routes;
    private final ConnectionTargetResolver targets;

    @Value("${chat.internal.token}")
    private String internalToken;

    @Override
    public boolean isOnline(Long userId) {
        try {
            return routes.find(userId) != null;
        } catch (Exception e) {
            // 路由存储故障不等于用户离线。保守允许尝试，让重试任务继续保留消息。
            log.warn("查询连接路由失败，保留投递尝试: userId={}, 原因={}", userId, e.getMessage());
            return true;
        }
    }

    @Override
    public boolean pushMessage(Long userId, MessageVO message) {
        return push(userId, ChatFrames.of(ChatFrames.TYPE_MESSAGE, "data", message));
    }

    @Override
    public boolean pushDelivered(Long userId, Long conversationId, Long upToMessageId) {
        return push(userId, ChatFrames.of(ChatFrames.TYPE_DELIVERED,
                "conversationId", conversationId, "upToMessageId", upToMessageId));
    }

    @Override
    public boolean pushRead(Long userId, Long conversationId, Long upToMessageId) {
        return push(userId, ChatFrames.of(ChatFrames.TYPE_READ,
                "conversationId", conversationId, "upToMessageId", upToMessageId));
    }

    public boolean push(Long userId, Map<String, Object> frame) {
        try {
            for (int attempt = 0; attempt < 2; attempt++) {
                ConnectionRoute route = routes.find(userId);
                if (route == null) return false;
                var target = targets.resolve(route.instanceId());
                if (target.isEmpty()) continue;
                try {
                    if (Boolean.TRUE.equals(InternalAuth.requireSuccess(connectionClient.push(
                            target.get(), internalToken, new PushFrameRequest(userId, route.sessionId(), frame))))) {
                        return true;
                    }
                } catch (Exception e) {
                    log.debug("本次定向投递失败，重新检查路由: userId={}, instance={}", userId, route.instanceId());
                }
            }
            return false;
        } catch (Exception e) {
            log.warn("调用连接服务推送失败: userId={}, type={}, 原因={}", userId, frame.get("type"), e.getMessage());
            return false;
        }
    }
    /** Redis failure propagates so MQ retries instead of treating the whole page as offline. */
    public Map<Long,ConnectionRoute> onlineRoutes(java.util.List<Long> users) {return routes.findAll(users);}

    public java.util.Set<Long> pushBatch(Map<Long,ConnectionRoute> initial,Map<String,Object> frame) {
        var pending=new java.util.LinkedHashMap<>(initial);
        var sent=new java.util.HashSet<Long>();
        for(int attempt=0;attempt<2 && !pending.isEmpty();attempt++) {
            var instances=new java.util.LinkedHashMap<String,java.util.List<org.example.common.dto.BatchPushFrameRequest.Target>>();
            pending.forEach((user,route)->instances.computeIfAbsent(route.instanceId(),key->new java.util.ArrayList<>())
                    .add(new org.example.common.dto.BatchPushFrameRequest.Target(user,route.sessionId())));
            for(var entry:instances.entrySet()) try {
                var target=targets.resolve(entry.getKey());
                if(target.isEmpty()) continue;
                var result=InternalAuth.requireSuccess(connectionClient.pushBatch(target.get(),internalToken,
                        new org.example.common.dto.BatchPushFrameRequest(entry.getValue(),frame)));
                if(result!=null) for(var user:entry.getValue()) if(result.contains(user.userId())) sent.add(user.userId());
            } catch(Exception e) {log.debug("Batch push failed: instance={}",entry.getKey(),e);}
            pending.keySet().removeAll(sent);
            if(attempt==0 && !pending.isEmpty()) try {pending=new java.util.LinkedHashMap<>(routes.findAll(new java.util.ArrayList<>(pending.keySet())));}
            catch(Exception e) {break;}
        }
        return sent;
    }
}
