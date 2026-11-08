package org.example.connection.session;

import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.ConcurrentWebSocketSessionDecorator;

/**
 * 在线连接注册表：{@code userId → 当前那条 WebSocket 连接}。
 *
 * <p>此类只管理本实例内存中的真实会话。ConnectionSessions 负责 Redis 全局归属、
 * 条件续期/删除及跨实例踢旧连接。不得用此本地在线表判断用户在整个集群是否在线。
 *
 * <h3>两个容易错的细节，这里都处理了</h3>
 * <ol>
 *   <li><b>发送要串行化</b>：{@code WebSocketSession.sendMessage} 不是线程安全的。
 *       将来会有两个线程同时往一条连接上写（HTTP 请求线程推新消息、定时任务推重试），
 *       并发写会抛 {@code IllegalStateException: TEXT_PARTIAL_WRITING}。
 *       所以存进表里的是 Spring 的 {@link ConcurrentWebSocketSessionDecorator} ——
 *       它按会话串行发送，顺便限制"发得有多慢"和"积压多少字节"，
 *       防止一个卡死的客户端把服务端内存吃光。</li>
 *   <li><b>断开时不能无条件移除</b>：同一用户重连时我们会踢掉旧连接并放入新连接，
 *       而旧连接关闭事件可能<b>晚于</b>新连接注册才到达。这时如果直接
 *       {@code sessions.remove(userId)}，会把刚建好的新连接从表里删掉 —— 人还在线却显示离线。
 *       所以只移除"仍然是当前那条"的记录（见 {@link #unregister}）。</li>
 * </ol>
 */
@Component
public class OnlineUserRegistry {

    private static final Logger log = LoggerFactory.getLogger(OnlineUserRegistry.class);

    /** 单帧最多花多久发出去（毫秒）；超时说明这条连接已经不行了，让 Spring 关掉它 */
    private static final int SEND_TIME_LIMIT_MS = 5_000;

    /** 发送缓冲区上限（字节）：客户端只收不读时，不至于把服务端内存撑爆 */
    private static final int BUFFER_SIZE_LIMIT = 512 * 1024;

    /**
     * 踢旧连接时用的关闭码（<b>客户端必须认识它</b>）。
     *
     * <p>为什么不能继续用默认的 {@code 1000}：客户端把"连接断了"一律当成"网络抖动，重连"。
     * 用 1000 踢人 → 被踢的那端立刻重连 → 把这一端踢掉 → 这一端也立刻重连……
     * 两边进入<b>无限对踢</b>，每秒一轮，而且每轮重连都会触发一次"上线补拉"。
     *
     * <p>1000 和"正常关闭"无法区分，所以这里用应用自定义区间（4000-4999）里的码，
     * 让客户端能明确区分"网络断了"（该重连）和"我在别处被顶掉了"（该退避）。
     * <b>这个数字和 {@code chat-web/electron/chat-socket.cjs} 里的 KICKED_CLOSE_CODE 必须一致。</b>
     */
    public static final int CLOSE_CODE_KICKED = 4001;

    /** 一条在线连接：原始会话 id（用来判断"是不是还同一条"）+ 可安全并发写的会话 + 最后活跃时间 */
    private record OnlineSession(String rawSessionId, WebSocketSession session, AtomicLong lastSeenMillis) {
    }

    private final Map<Long, OnlineSession> sessions = new ConcurrentHashMap<>();

    /**
     * 登记一条新连接。
     *
     * <p>同一个人已经有连接时<b>踢掉旧的</b>：和登录的"新登录踢旧登录"保持一致 ——
     * 一个账号同一时间只有一个在线端（多端是以后的事，那时候 ack 的语义要重新设计）。
     *
     * @return 包装好的会话，调用方用它发送（不要用原始 session 直接发）
     */
    public WebSocketSession register(Long userId, WebSocketSession rawSession) {
        WebSocketSession safeSession = new ConcurrentWebSocketSessionDecorator(
                rawSession, SEND_TIME_LIMIT_MS, BUFFER_SIZE_LIMIT);
        // lastSeen 从"登记的那一刻"开始算 —— 客户端要过 30 秒才会发第一个 ping
        OnlineSession previous = sessions.put(userId, new OnlineSession(
                rawSession.getId(), safeSession, new AtomicLong(System.currentTimeMillis())));

        if (previous != null) {
            log.info("同一用户已有连接，踢掉旧的: userId={}, 旧 sessionId={}", userId, previous.rawSessionId());
            // 用 4001 而不是默认的 1000：客户端据此区分"网络断了该重连"和"我被顶掉了该退避重连"，
            // 否则两边会无限对踢（见 CLOSE_CODE_KICKED 的注释）
            closeQuietly(previous.session(), CLOSE_CODE_KICKED, "账号在其他地方连接");
        }
        return safeSession;
    }

    /**
     * 记一笔"这个人刚刚有动静"（收到任何一帧时调）。
     *
     * <p>空闲连接清理全靠它：服务端不主动发心跳，能证明"客户端还活着"的只有它发来的帧
     * （每 30 秒一个 ping，可能还有 ack）。详见 {@link org.example.connection.job.StaleConnectionSweepJob}。
     *
     * <p>同样要判断"是不是还是当前那条"：旧连接的收尾帧可能晚于新连接到达，
     * 让它去刷新新连接的活跃时间就本末倒置了。
     */
    public void touch(Long userId, WebSocketSession rawSession) {
        OnlineSession current = userId == null ? null : sessions.get(userId);
        if (current != null && current.rawSessionId().equals(rawSession.getId())) {
            current.lastSeenMillis().set(System.currentTimeMillis());
        }
    }

    /**
     * 关掉"超过 {@code idleMillis} 没有任何动静"的连接，返回关掉的条数。
     *
     * <p>判定依据是应用层活跃时间（{@link #touch}），不是 TCP 层 ——
     * 半死连接的 TCP 还"通"着，写数据也不报错，只有"这个客户端再也没发过任何帧"是可信的信号。
     */
    public int closeIdle(long idleMillis, int maxClose) {
        long deadline = System.currentTimeMillis() - idleMillis;
        int closed = 0;
        for (Map.Entry<Long, OnlineSession> entry : sessions.entrySet()) {
            if (closed >= maxClose) {
                break;
            }
            OnlineSession online = entry.getValue();
            if (online.lastSeenMillis().get() >= deadline) {
                continue;
            }
            // 先摘登记再关连接：即使关闭动作失败（连接早断了），这个人也不会继续被当成在线，
            // 后面的推送会立刻发现他不在线，而不是继续推给一条死连接
            if (sessions.remove(entry.getKey(), online)) {
                log.info("连接空闲超时，主动关闭: userId={}, sessionId={}, 空闲毫秒={}",
                        entry.getKey(), online.rawSessionId(), System.currentTimeMillis() - online.lastSeenMillis().get());
                closeQuietly(online.session(), CloseStatus.NORMAL.getCode(), "空闲超时");
                closed++;
            }
        }
        return closed;
    }

    /**
     * 移除连接。<b>只移除"仍然是当前那条"的记录</b>，理由见类注释第 2 点。
     */
    public void unregister(Long userId, WebSocketSession rawSession) {
        sessions.computeIfPresent(userId, (id, current) ->
                current.rawSessionId().equals(rawSession.getId()) ? null : current);
    }

    public boolean isOnline(Long userId) {
        return userId != null && sessions.containsKey(userId);
    }

    /**
     * 取某个人的连接（下一步的推送就靠它）。
     *
     * @return 不在线返回 null
     */
    public WebSocketSession get(Long userId) {
        OnlineSession online = userId == null ? null : sessions.get(userId);
        return online == null ? null : online.session();
    }

    /** 当前在线人数（调试用） */
    public int onlineCount() {
        return sessions.size();
    }

    /** 当前在线的 userId 集合（调试用） */
    public Set<Long> onlineUserIds() {
        return Set.copyOf(sessions.keySet());
    }

    /**
     * 关一条连接：失败只记日志。
     *
     * @param code 关闭码。<b>不能随便传 1000</b> —— 客户端要靠它区分
     *             "被别处顶掉了"（{@link #CLOSE_CODE_KICKED}）和普通断开，
     *             否则被踢的那端会立刻重连，两边无限对踢
     */
    private void closeQuietly(WebSocketSession session, int code, String reason) {
        try {
            if (session.isOpen()) {
                session.close(new CloseStatus(code, reason));
            }
        } catch (Exception e) {
            // 关不上就关不上（连接可能已经断了），这里绝不能因为清理失败影响新连接的建立
            log.warn("关闭连接失败: sessionId={}, code={}, 原因={}", session.getId(), code, e.getMessage());
        }
    }
}
