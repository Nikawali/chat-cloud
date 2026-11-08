package org.example.message.service.single;

import java.util.List;

import org.example.message.entity.ConversationEntity;
import org.example.message.vo.ConversationVO;

/**
 * 会话业务接口。
 *
 * <p>只负责单聊会话；群聊由独立的表和业务实现处理。
 */
public interface ConversationService {

    /**
     * 我的会话列表（消息页用）：每个会话一行 —— 对方是谁、最后一条的摘要、未读数、回执水位。
     *
     * <p><b>这是消息页唯一的服务端数据源。</b> 客户端本地也存了一份会话摘要
     * （为了秒开和离线可见），但那份只在"这台机器本地存过消息"的范围内准确；
     * 这个接口给的是权威值，也是"换了台机器"时列表还能出现的唯一原因。
     *
     * <p>只返回"列表那一行"要的东西，<b>不含具体消息</b>（那是 history 的活）。
     *
     * @param myUserId 我（网关注入的身份）
     * @param size     最多返回多少个会话（不传用默认值，服务端会夹上限）
     * @return 按最后一条消息时间倒序；一个都没有时返回空列表（不是 null）
     */
    List<ConversationVO> listForUser(Long myUserId, Integer size);

    /**
     * 单聊的唯一键：两个 userId <b>升序</b>拼接（如 {@code "2:3"}）。
     *
     * <p>升序是关键：{@code (2,3)} 和 {@code (3,2)} 必须得出同一个 key，
     * 否则同一对人会建出两个会话。
     */
    String singleKey(Long userA, Long userB);

    /** 查单聊会话；还没建过返回 null（注意：<b>不会</b>顺手建） */
    ConversationEntity findSingle(Long userA, Long userB);

    /**
     * 查找或创建单聊会话。
     *
     * <p>并发安全：两个人同时点"发消息"时，靠 {@code conversation.single_key} 上的唯一索引
     * 保证只建一个会话 —— 谁先提交谁建，另一个插入失败后重新查出来用。
     */
    ConversationEntity findOrCreateSingle(Long userA, Long userB);

    /** 确认某人是会话成员，不是就抛 403（防"往别人的会话里塞消息"） */
    void requireMember(Long conversationId, Long userId);

    /** 更新会话的"最后一条消息"冗余字段（会话列表排序用），发消息时调 */
    void touchLastMessage(Long conversationId, Long messageId, java.time.LocalDateTime at);

    /**
     * 推进我的"读到哪了"游标。
     *
     * <p><b>只前进不后退</b>：上报可能是乱序的（先报大的、再报小的），
     * 所以实现里用一条带条件的 UPDATE 保证只往前走
     * （{@code where last_read_message_id is null or last_read_message_id < ?}），
     * 而不是"先查再比再写"（那中间有窗口，而且要多一条 SQL）。
     */
    void advanceReadCursor(Long conversationId, Long userId, Long messageId);
}
