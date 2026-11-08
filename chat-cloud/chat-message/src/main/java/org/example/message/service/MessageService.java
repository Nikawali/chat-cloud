package org.example.message.service;

import java.util.List;

import org.example.message.dto.ReadMessageRequest;
import org.example.message.dto.SendMessageRequest;
import org.example.message.vo.MessageVO;
import org.example.message.vo.SendMessageVO;

/**
 * 单聊兼容业务接口；发送、查询、回执分别委托专门组件。
 *
 * <p>发送由 SingleMessageSender 实现公共发送契约，数据库提交后交给
 * SingleMessageDelivery 投递；HTTP 与 connection 内部接口继续使用本接口。
 *
 * <p>每个方法的第一个参数都是 {@code myUserId}（网关注入的 {@code X-User-Id}），
 * 它既是"我是谁"，也是唯一的安全边界：所有"只能操作自己的数据"的判断都基于它。
 */
public interface MessageService {

    /**
     * 发消息。
     *
     * <p>顺序是：校验（是好友？）→ 幂等检查 → 查找或创建会话 → 落库（消息 + 收据 + 会话冗余字段）。
     * 落库和收据在同一个事务里 —— 收据漏了的话，这条消息就永远不会被推送和补拉。
     *
     * @return 受理结果；重复提交时返回的是<b>已存在的那条</b>（{@code duplicated=true}）
     */
    SendMessageVO send(Long myUserId, SendMessageRequest req);

    /**
     * 拉历史消息（按 messageId 倒序翻页，返回时<u>从旧到新</u>）。
     *
     * <p>用"对方 userId"而不是 conversationId 定位会话：聊天页的路由就是按人来的，
     * 而且<b>还没聊过天的时候不该为了看一眼就建一个空会话</b>。
     *
     * @param beforeMessageId 翻页锚点：只取比它更早的；第一次传 null = 最新的一批
     */
    List<MessageVO> history(Long myUserId, Long peerUserId, Long beforeMessageId, Integer size);

    /**
     * 补拉"发给我的、还没送达的"消息。
     *
     * <p>这是整条可靠性的<b>兜底</b>：实时推送失败（不在线、网络抖、超过 3 分钟放弃重试）
     * 都不会让消息丢 —— 用户一上线就调这个接口，按 messageId 顺序补下来。
     */
    List<MessageVO> undelivered(Long myUserId, Integer size);

    /**
     * 上报已读：更新我的读游标 + 把"已读"写进收据（发送方要靠它显示双勾）。
     *
     * <p>顺带把 {@code delivered_at} 补上（已读必然意味着已送达），
     * 这样就算 ack 丢了，也不会留下"已读但未送达"的矛盾状态。
     *
     * <p>最后会通过 WebSocket 把"已读"推给发送方。
     */
    void markRead(Long myUserId, ReadMessageRequest req);

    /**
     * 送达确认（接收方客户端收到推送、存好本地之后回的 ack）。
     *
     * <p>做三件事，顺序不能乱：
     * <ol>
     *   <li><b>幂等</b>写收据的 {@code delivered_at}（只更新还没记过送达时间的行）；</li>
     *   <li>把这几条从"待送达队列"里出队（停止重推）；</li>
     *   <li>回推发送方"已送达"（对方界面上的单勾变双勾）。</li>
     * </ol>
     *
     * <p>整个方法是<b>幂等</b>的：同一个 ack 重发多少次，结果都一样（这是必需的 ——
     * 客户端断线重连后会重放没确认成功的 ack）。
     *
     * @param messageIds 确认收到的消息 id 列表（客户端可能一次确认一批）
     */
    void markDelivered(Long myUserId, List<Long> messageIds);
}
