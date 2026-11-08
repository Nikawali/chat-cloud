package org.example.connection.job;

import org.example.connection.session.OnlineUserRegistry;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * 清理"没有任何动静"的 WebSocket 连接。
 *
 * <h3>为什么需要它</h3>
 * 服务端<b>不主动发心跳</b>（心跳是客户端每 30 秒发一个 {@code ping}，服务端回 {@code pong}）。
 * 于是当客户端"物理上死了但连接没关"时（笔记本合盖、拔网线、进程被冻住、NAT 表项过期），
 * 服务端<b>没有任何办法自己发现</b>：
 * <ul>
 *   <li>TCP 连接还在，{@code isOnline} 返回 true；</li>
 *   <li>写一帧进去也不会报错（数据进了对方本机的接收缓冲，没人读而已）；</li>
 *   <li>容器默认也不设空闲超时。</li>
 * </ul>
 * 结果就是：这个人在线表里"永远在线"，重试任务对着一条死连接认认真真推满 180 秒
 * （每条消息约 10 次，每次还附带一次查昵称头像的 Feign 调用），日志里还写着"已推送"。
 *
 * <h3>怎么判定"没有动静"</h3>
 * 不去猜 TCP 层，直接看<b>应用层</b>：客户端每 30 秒必然发一个 {@code ping}
 * （还可能发 ack），所以"整整 {@code ws-idle-timeout-seconds}（默认 90 = 3 个心跳周期）
 * 没收到这个人的任何一帧"就足以判定它已经死了。
 *
 * <p>阈值必须<b>明显大于</b>客户端心跳间隔，否则会把正常客户端误杀。
 * 这两者是跨进程的约定：客户端 {@code HEARTBEAT_MS = 30s}（chat-socket.cjs）↔ 这里 90 秒。
 *
 * <p>误杀也不致命：客户端会自己重连（正常退避），重连后顺手补拉一遍。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class StaleConnectionSweepJob {

    private final OnlineUserRegistry onlineUsers;

    /** 超过这么久没收到任何一帧就认为连接已死；必须是客户端心跳间隔的若干倍 */
    @Value("${chat.connection.ws-idle-timeout-seconds:90}")
    private long idleTimeoutSeconds;

    /** 一轮最多清理多少条（清理动作要关连接，别在一轮里阻塞太久） */
    @Value("${chat.connection.ws-sweep-batch-size:500}")
    private int batchSize;

    @Scheduled(fixedDelayString = "${chat.connection.ws-idle-scan-interval-ms:30000}")
    public void sweep() {
        int closed = onlineUsers.closeIdle(idleTimeoutSeconds * 1000L, batchSize);
        if (closed > 0) {
            log.info("清理空闲 WebSocket 连接: 本轮关闭={}, 当前在线={}", closed, onlineUsers.onlineCount());
        }
    }
}
