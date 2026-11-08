package org.example.connection.ws;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

import org.example.common.dto.DeliveredReport;
import org.example.common.dto.ReadReport;
import org.example.common.dto.SendMessageCommand;
import org.example.common.dto.SendMessageResult;
import org.example.common.result.Result;
import org.example.common.protocol.ChatFrames;
import org.example.common.support.InternalAuth;
import org.example.connection.client.MessageClient;
import org.example.connection.session.OnlineUserRegistry;
import org.example.connection.session.ConnectionSessions;
import org.springframework.beans.factory.annotation.Value;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.TextWebSocketHandler;

import lombok.RequiredArgsConstructor;

/**
 * 聊天实时通道入口（服务端侧）：单聊发送及回执、群聊发送受理。
 *
 * <p>目前负责：
 * <ul>
 *   <li>连接建立 → 登记在线表 + 回 welcome；断开/出错 → 摘掉登记</li>
 *   <li>心跳：收到 {@code ping} 回 {@code pong}</li>
 *   <li><b>送达 ack</b>：取连接身份，经内部接口交给消息服务写收据、出队并通知发送方</li>
 *   <li><b>已读上报</b>：取连接身份，经内部接口交给消息服务推进读游标并通知发送方</li>
 *   <li>坏帧 / 不认识的类型 → 回一个 error 帧（不把连接搞崩）</li>
 * </ul>
 *
 * <p>为什么用 {@link TextWebSocketHandler}（低层）而不是 STOMP：我们的帧协议是自定义的
 * （推送 + ack + 已送达/已读回执），STOMP 的订阅/目的地模型用不上，还多一层概念。
 *
 * <p><b>身份只从会话属性取</b>（握手时由 {@link ChatHandshakeInterceptor} 放进去的），
 * 永远不看客户端在帧里写的 userId —— 那玩意儿客户端想写什么就写什么。
 */
@Component
@RequiredArgsConstructor
public class ChatWebSocketHandler extends TextWebSocketHandler {

    private static final Logger log = LoggerFactory.getLogger(ChatWebSocketHandler.class);

    private final OnlineUserRegistry onlineUsers;
    private final ConnectionSessions sessions;
    private final MessageClient messageClient;
    private final AtomicLong nextReceiptFailureLogAt = new AtomicLong();
    private final AtomicLong receiptRpcFailures = new AtomicLong();

    @Value("${chat.internal.token}")
    private String internalToken;

    @Override
    public void afterConnectionEstablished(WebSocketSession session) {
        Long userId = ChatHandshakeInterceptor.userIdOf(session);
        // 登记（同一个人已在线时会踢掉旧连接），拿到的是可以安全并发写的会话
        WebSocketSession safeSession = sessions.register(userId, session);
        if (safeSession == null) return;

        send(safeSession, ChatFrames.of(ChatFrames.TYPE_WELCOME,
                "userId", userId,
                "serverTime", LocalDateTime.now().toString()));

        log.info("WebSocket 已连接: userId={}, sessionId={}, 当前在线人数={}",
                userId, session.getId(), onlineUsers.onlineCount());
    }

    @Override
    protected void handleTextMessage(WebSocketSession session, TextMessage message) {
        Long userId = ChatHandshakeInterceptor.userIdOf(session);
        // 收到任何一帧都是一次"我还活着"的证明（客户端每 30 秒一个 ping）。
        // 空闲连接清理任务只看这个时间戳，见 StaleConnectionSweepJob。
        if (!sessions.touch(userId, session)) return;

        Map<String, Object> frame;
        try {
            frame = ChatFrameCodec.parse(message.getPayload());
        } catch (IllegalArgumentException e) {
            // 坏帧不该把连接搞崩：回一个 error 帧，让客户端自己知道发错了
            log.warn("WS 收到不合法的帧: userId={}, 原因={}", userId, e.getMessage());
            send(session, ChatFrames.of(ChatFrames.TYPE_ERROR, "message", e.getMessage()));
            return;
        }

        String type = ChatFrames.typeOf(frame);
        switch (type) {
            case "group-receipt-report" -> handleGroupReceipt(session,userId,frame);
            case ChatFrames.TYPE_SEND_GROUP_MESSAGE -> handleGroupSend(session, userId, frame);
            case ChatFrames.TYPE_SEND_MESSAGE -> handleSend(session, userId, frame);
            case ChatFrames.TYPE_PING -> send(session, ChatFrames.of(ChatFrames.TYPE_PONG,
                    "userId", userId,
                    "serverTime", LocalDateTime.now().toString()));
            case ChatFrames.TYPE_ACK -> handleAck(session, userId, frame);
            case ChatFrames.TYPE_READ_REPORT -> handleReadReport(session, userId, frame);
            default -> {
                log.warn("WS 收到不认识的帧类型: userId={}, type={}", userId, type);
                send(session, ChatFrames.of(ChatFrames.TYPE_ERROR,
                        "message", "不认识的帧类型: " + type));
            }
        }
    }

    private void handleGroupReceipt(WebSocketSession session,Long userId,Map<String,Object> frame) {
        Object id=frame.get("clientMessageId");
        Long groupId=null;
        long started=System.nanoTime();
        Result<org.example.common.dto.GroupReceiptState> result;
        try {
            groupId=positiveInteger(frame.get("groupId"));
            if(groupId==null || !(id instanceof String key) || !key.matches("[A-Za-z0-9_-]{1,64}") || !(frame.get("deviceId") instanceof String device) || !device.matches("[A-Za-z0-9_-]{1,64}")) throw new IllegalArgumentException();
            Object bound=session.getAttributes().putIfAbsent("receiptDeviceId",device);
            if(bound!=null && !bound.equals(device)) throw new IllegalArgumentException();
            Long received=nonNegative(frame.get("receivedSeq")), read=nonNegative(frame.get("readSeq"));
            result=messageClient.groupReceipt(internalToken,new org.example.common.dto.GroupReceiptCommand(userId,groupId,device,positiveInteger(frame.get("memberVersion")),received,read,Boolean.TRUE.equals(frame.get("ownOnly"))));
            if(result==null) result=Result.error(503,"回执服务暂时不可用");
        } catch(IllegalArgumentException e) {result=Result.error(400,"群回执参数不合法");}
        catch(Exception e) {
            receiptRpcFailures.incrementAndGet();
            long now=System.currentTimeMillis(),next=nextReceiptFailureLogAt.get();
            if(now>=next && nextReceiptFailureLogAt.compareAndSet(next,now+5000)) {
                var root=org.springframework.core.NestedExceptionUtils.getMostSpecificCause(e);
                // Sample locally; never include credentials, request bodies or exception response bodies.
                log.warn("Group receipt RPC failed: group={}, user={}, request={}, elapsedMs={}, exception={}, rootCause={}, httpStatus={}, failuresSinceLastLog={}",
                        groupId,userId,id,(System.nanoTime()-started)/1_000_000,
                        e.getClass().getName(),root.getClass().getName(),
                        e instanceof feign.FeignException failure?failure.status():null,receiptRpcFailures.getAndSet(0));
            }
            result=Result.error(503,"群回执尚未确认，请重试");
        }
        send(session,ChatFrames.of("group-receipt-result","clientMessageId",id,"code",result.getCode(),"message",result.getMessage(),"data",result.getData()));
    }
    private static Long nonNegative(Object value) {
        if(value==null) return null;
        if(value instanceof Number n && n.longValue()>=0 && n.doubleValue()==n.longValue()) return n.longValue();
        throw new IllegalArgumentException();
    }

    private void handleSend(WebSocketSession session, Long userId, Map<String, Object> frame) {
        Object id = frame.get("clientMessageId");
        if (!(id instanceof String clientId) || clientId.isBlank() || clientId.length() > 64) {
            send(session, ChatFrames.of(ChatFrames.TYPE_SEND_RESULT, "clientMessageId", id,
                    "code", 400, "message", "clientMessageId 不合法"));
            return;
        }
        Result<SendMessageResult> result;
        try {
            Long toUserId = positiveInteger(frame.get("toUserId"));
            Long type = frame.get("messageType") == null ? 1L : positiveInteger(frame.get("messageType"));
            if (toUserId == null || type == null || type > Integer.MAX_VALUE
                    || !(frame.get("content") instanceof String content)) {
                result = Result.error(400, "发送参数不合法");
            } else {
                result = messageClient.send(internalToken, new SendMessageCommand(userId, toUserId,
                        content, clientId, type.intValue()));
                if (result == null || (result.isSuccess() && result.getData() == null)) {
                    result = Result.error(503, "消息服务暂时不可用，请稍后重试");
                }
            }
        } catch (Exception e) {
            log.warn("WS 提交消息失败: userId={}, clientMessageId={}", userId, clientId, e);
            result = Result.error(503, "尚未确认消息受理结果，请稍后重试");
        }
        send(session, ChatFrames.of(ChatFrames.TYPE_SEND_RESULT, "clientMessageId", clientId,
                "code", result.getCode(), "message", result.getMessage(), "data", result.getData()));
    }

    private void handleGroupSend(WebSocketSession session, Long userId, Map<String, Object> frame) {
        Object id = frame.get("clientMessageId");
        Result<org.example.common.dto.SendGroupMessageResult> result;
        try {
            Long groupId = positiveInteger(frame.get("groupId"));
            Long type = frame.get("messageType") == null ? 1L : positiveInteger(frame.get("messageType"));
            if (!(id instanceof String clientId) || !clientId.matches("[A-Za-z0-9_-]{1,64}")
                    || groupId == null || type == null || type > Integer.MAX_VALUE
                    || !(frame.get("content") instanceof String content)) {
                result = Result.error(400, "群消息发送参数不合法");
            } else {
                result = messageClient.sendGroup(internalToken, new org.example.common.dto.SendGroupMessageCommand(
                        userId, groupId, content, clientId, type.intValue()));
                if (result == null || (result.isSuccess() && result.getData() == null))
                    result = Result.error(503, "消息服务暂时不可用，请稍后重试");
            }
        } catch (Exception e) {
            log.warn("WS 提交群消息失败: userId={}, clientMessageId={}", userId, id, e);
            result = Result.error(503, "尚未确认消息受理结果，请使用原 clientMessageId 重试");
        }
        send(session, ChatFrames.of(ChatFrames.TYPE_GROUP_SEND_RESULT, "clientMessageId", id,
                "code", result.getCode(), "message", result.getMessage(), "data", result.getData()));
    }

    private static Long positiveInteger(Object value) {
        if (!(value instanceof Number number)) return null;
        long result = number.longValue();
        return result > 0 && number.doubleValue() == result ? result : null;
    }

    /**
     * 送达确认：客户端收到推送、<b>存好本地之后</b>回这个帧。
     *
     * <p>帧里只有 {@code messageIds}，<b>没有 userId</b> —— 身份从连接上取
     * （客户端改不了"这条连接是谁的"）。这也意味着 ack 天然防伪造：
     * 只有接收方那条连接能确认他自己的消息。
     *
     * <p>处理失败（比如数据库抖了一下）时回一个 error 帧，让客户端知道可以稍后重发 ——
     * <b>ack 必须允许重发</b>，服务端那边是幂等的。
     */
    private void handleAck(WebSocketSession session, Long userId, Map<String, Object> frame) {
        List<Long> messageIds = ChatFrames.longList(frame.get("messageIds"));
        if (messageIds.isEmpty()) {
            send(session, ChatFrames.of(ChatFrames.TYPE_ERROR, "message", "ack 里没有 messageIds"));
            return;
        }
        try {
            InternalAuth.requireSuccess(messageClient.delivered(internalToken, new DeliveredReport(userId, messageIds)));
        } catch (Exception e) {
            log.error("处理送达 ack 失败: userId={}, messageIds={}", userId, messageIds, e);
            send(session, ChatFrames.of(ChatFrames.TYPE_ERROR,
                    "message", "送达确认处理失败，请稍后重发 ack"));
        }
    }

    /**
     * 已读上报：客户端告诉我"在这个会话里读到哪条了"。
     *
     * <p>走的是和 HTTP 接口 {@code POST /message/read} <b>完全同一段</b>
     * 消息服务的已读处理：推进我的读游标（未读数靠它算）+ 写 read_at
     * + 回推发送方"对方已读"。两条路都在，是因为浏览器里没有 WS（见 ChatFrames 的说明）。
     *
     * <p>为什么这个可以放心走 WS：它是<b>纯通知</b>——不需要返回值、失败可以重发、
     * 服务端取 max 所以天然幂等、而且"读到哪条"会随着后续每一条新消息不断被覆盖
     * （这次丢了，下次读更新的消息时会连这段一起补上）。
     *
     * <p>同样的身份规矩：<b>只是从帧里拿"对方是谁"和"读到哪"，自己是谁从连接上取</b>。
     */
    private void handleReadReport(WebSocketSession session, Long userId, Map<String, Object> frame) {
        Long peerUserId = ChatFrames.longOrNull(frame.get("peerUserId"));
        Long upToMessageId = ChatFrames.longOrNull(frame.get("upToMessageId"));
        if (peerUserId == null || upToMessageId == null) {
            send(session, ChatFrames.of(ChatFrames.TYPE_ERROR,
                    "message", "read-report 需要 peerUserId 和 upToMessageId"));
            return;
        }
        try {
            InternalAuth.requireSuccess(messageClient.read(internalToken, new ReadReport(userId, peerUserId, upToMessageId)));
        } catch (Exception e) {
            // 失败只回 error 帧：客户端会退回 HTTP 兜底（见 Chat.vue 的 sendReadToServer）
            log.error("处理已读上报失败: userId={}, peer={}, upTo={}", userId, peerUserId, upToMessageId, e);
            send(session, ChatFrames.of(ChatFrames.TYPE_ERROR,
                    "message", "已读上报处理失败，请稍后重试"));
        }
    }

    @Override
    public void afterConnectionClosed(WebSocketSession session, CloseStatus status) {
        Long userId = ChatHandshakeInterceptor.userIdOf(session);
        sessions.unregister(userId, session);
        log.info("WebSocket 已断开: userId={}, sessionId={}, status={}, 当前在线人数={}",
                userId, session.getId(), status, onlineUsers.onlineCount());
    }

    @Override
    public void handleTransportError(WebSocketSession session, Throwable exception) {
        Long userId = ChatHandshakeInterceptor.userIdOf(session);
        // 传输层出错基本等于这条连接废了：登记表里先摘掉，剩下的交给 afterConnectionClosed
        sessions.unregister(userId, session);
        log.warn("WebSocket 传输错误: userId={}, sessionId={}, 原因={}",
                userId, session.getId(), exception.getMessage());
    }

    /**
     * 发一帧。
     *
     * <p>发送失败<b>只记日志、不往上抛</b>：连接断了是常态（用户关窗口、切网络），
     * 清理工作由 {@code afterConnectionClosed} 负责；把异常抛出去会变成"传输错误"，
     * 反而多绕一圈。
     */
    private void send(WebSocketSession session, Map<String, Object> frame) {
        try {
            // 心跳响应与远程推送共用并发安全包装，避免同时写原始 WS 会话。
            WebSocketSession safeSession = onlineUsers.get(ChatHandshakeInterceptor.userIdOf(session));
            if (safeSession != null && safeSession.getId().equals(session.getId())) {
                safeSession.sendMessage(new TextMessage(ChatFrameCodec.toJson(frame)));
            }
        } catch (Exception e) {
            log.warn("发送 WS 帧失败: sessionId={}, 原因={}", session.getId(), e.getMessage());
        }
    }
}
