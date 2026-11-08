package org.example.message.service.single;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.example.common.exception.BizException;
import org.example.message.entity.ConversationEntity;
import org.example.message.entity.ConversationMemberEntity;
import org.example.message.entity.MessageEntity;
import org.example.message.mapper.ConversationMapper;
import org.example.message.mapper.ConversationMemberMapper;
import org.example.message.mapper.MessageMapper;
import org.example.message.mapper.MessageReceiptMapper;
import org.example.message.vo.ConversationVO;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * 会话实现。
 *
 * <p><b>这里有一个 Spring 的经典坑，代码是特意绕开它的：</b>
 * {@link #findOrCreateSingle} 需要"建会话失败后重新查"，而"建会话 + 加两个成员"必须是事务。
 * 如果我把 {@code createSingle} 写成同类里的 {@code @Transactional} 方法、再在
 * {@code findOrCreateSingle} 里直接调用，<b>事务注解不会生效</b> ——
 * Spring 的 {@code @Transactional} 靠代理实现，<b>同类内部调用走的是 this，不经过代理</b>。
 *
 * <p>所以这里用 {@link TransactionTemplate} 显式开事务：意图清楚，也不会被"自调用"坑。
 * （另一种常见写法是把事务方法拆到另一个 bean 里。）
 *
 * <p>还有一个原因必须让 {@code createSingle} 的事务<b>独立且先结束</b>：
 * 插入撞唯一索引后再去查那条数据，如果还在同一个事务里，MySQL 的 REPEATABLE READ
 * 可能看不到别人刚提交的行（快照读），会查不到而误报 500。事务先回滚、再在事务外重查，才稳。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ConversationServiceImpl implements ConversationService {

    /** 会话列表默认返回多少个（不传 size 时） */
    private static final int DEFAULT_LIST_SIZE = 50;

    /** 会话列表最多返回多少个（防止有人一次把几千个会话拉走） */
    private static final int MAX_LIST_SIZE = 200;

    /**
     * 列表里那句摘要最多留多少个字符。
     *
     * <p>和客户端本地库（{@code message-store.cjs#PREVIEW_MAX_LENGTH}）保持一致：
     * 两边规则不一样的话，同一条消息在"服务端校正前/后"会显示成两种样子。
     */
    private static final int PREVIEW_MAX_LENGTH = 30;

    private final ConversationMapper conversationMapper;
    private final ConversationMemberMapper memberMapper;
    private final MessageMapper messageMapper;
    private final MessageReceiptMapper receiptMapper;
    private final TransactionTemplate transactionTemplate;

    /**
     * 我的会话列表。每步都是<b>一条</b>查询（未读那步见下面的说明）：
     * <pre>
     *   ① 我的会话成员行（走 idx_conversation_member_user）→ 会话 id + 我读到哪条
     *   ② 会话本身（selectBatchIds，主键）→ last_message_id / last_message_at，按时间倒序截断
     *   ③ 最后一条消息的内容（selectBatchIds，主键）→ 列表那句摘要
     *   ④ 每个会话的"对方"（解析 single_key，零查询）
     *   ⑤ 回执水位（一条 group by IN 查询）→ 勾的对账数据
     *   ⑥ 未读数：只对"确实有新增"的会话各查一条 count（详见下面）
     * </pre>
     */
    @Override
    public List<ConversationVO> listForUser(Long myUserId, Integer size) {
        int limit = clampListSize(size);

        // ① 我的单聊会话关联行。
        List<ConversationMemberEntity> members = memberMapper.selectList(
                Wrappers.<ConversationMemberEntity>lambdaQuery()
                        .eq(ConversationMemberEntity::getUserId, myUserId));
        if (members.isEmpty()) {
            return List.of();
        }
        // 会话 id → 我读到哪条为止（未读数要用它；null 表示一条都没读过）
        Map<Long, Long> myReadCursor = new HashMap<>(members.size());
        for (ConversationMemberEntity member : members) {
            myReadCursor.put(member.getConversationId(), member.getLastReadMessageId());
        }

        // ② 会话行（拿 last_message_id / last_message_at），按最后一条消息时间倒序截断
        List<ConversationEntity> conversations = new ArrayList<>(
                conversationMapper.selectBatchIds(myReadCursor.keySet()));
        // nullsLast：从没聊过的会话（last_message_at 为空）排最后，而不是排最前
        conversations.sort(Comparator.comparing(ConversationEntity::getLastMessageAt,
                Comparator.nullsLast(Comparator.reverseOrder())));
        if (conversations.size() > limit) {
            conversations = conversations.subList(0, limit);
        }
        if (conversations.isEmpty()) {
            // 理论上到不了（成员行指向的会话行不该缺）。但要挡住：下面两条 IN 查询
            // 传空列表会拼出 "in ()" 这种非法 SQL，直接报错。
            return List.of();
        }

        // ③ 最后一条消息的内容：一条 IN 查询（走主键），不是每个会话查一次
        List<Long> lastMessageIds = conversations.stream()
                .map(ConversationEntity::getLastMessageId)
                .filter(Objects::nonNull)
                .distinct()
                .toList();
        Map<Long, MessageEntity> lastMessages = lastMessageIds.isEmpty() ? Map.of()
                : messageMapper.selectBatchIds(lastMessageIds).stream()
                        .collect(Collectors.toMap(MessageEntity::getMessageId, Function.identity(), (a, b) -> a));

        // ④ 每个会话的"对方"。
        //    水位那一步必须知道对方是谁（收据是按接收者存的，见 MessageReceiptMapper#selectWatermarks），
        //    所以先算出来，后面组装时直接复用，不重复解析 single_key
        Map<Long, Long> peerOfConversation = new HashMap<>(conversations.size());
        for (ConversationEntity conversation : conversations) {
            Long peer = peerOf(conversation, myUserId);
            if (peer != null) {
                peerOfConversation.put(conversation.getConversationId(), peer);
            }
        }

        // ⑤ 回执水位：一条 group by（走 idx_receipt_receiver_conv 的索引前缀），不是每个会话查一次。
        //    两个列表都来自上面的步骤、都非空，所以不会拼出 "in ()" 这种非法 SQL
        List<Long> conversationIds = conversations.stream()
                .map(ConversationEntity::getConversationId)
                .toList();
        List<Long> peers = peerOfConversation.values().stream().distinct().toList();
        Map<Long, MessageReceiptMapper.ReceiptWatermark> watermarks = Map.of();
        if (!peers.isEmpty()) {
            watermarks = receiptMapper.selectWatermarks(peers, conversationIds).stream()
                    .collect(Collectors.toMap(
                            MessageReceiptMapper.ReceiptWatermark::conversationId, Function.identity(), (a, b) -> a));
        }

        // ⑥ 组装。未读数在循环里查，但【只对确实有新增的会话查】——
        //    判据是"会话的最后一条 id 比我的读游标还大"，没有新增就直接是 0。
        //    为什么不用"一条 join 把所有会话的未读数一次算出来"：两种写法【都能用上索引】
        //    （{@code EXPLAIN FORMAT=TREE} 实测：join 写法是 Nested loop，内层是
        //    {@code Index lookup on message using idx_message_conversation}，
        //    门槛条件被下推成 index condition —— 所以"join 一定全表扫描"这个说法是错的，
        //    别拿它当理由）。真正的差别有两条：
        //      ① join 写法不管有没有新消息，都要给我参与的【每个】会话做一次索引查找；
        //         而这里上面那个判断能把绝大多数会话直接跳过（实际通常只有 0~3 条 SQL）；
        //      ② join 写法里门槛来自另一个表的列（不是常量），优化器给内层估的行数就是
        //         【整个 message 表】（实测 {@code rows=115} = 全表 115 行）—— 表一大，
        //         估出来的总行数就是"会话数 × 全表行数"，它随时可能改主意去扫 message。
        //         逐会话 count 是一条不含连接的 count：没有连接顺序可挑，形状不随会话数变。
        //    代价是要多发几条 SQL；这个接口一次进消息页只调一次，换"计划可预测"是划算的。
        List<ConversationVO> result = new ArrayList<>(conversations.size());
        for (ConversationEntity conversation : conversations) {
            Long conversationId = conversation.getConversationId();
            Long myCursor = myReadCursor.get(conversationId);
            Long lastMessageId = conversation.getLastMessageId();

            long unread = 0;
            if (lastMessageId != null && (myCursor == null || lastMessageId > myCursor)) {
                Long count = messageMapper.selectCount(Wrappers.<MessageEntity>lambdaQuery()
                        .eq(MessageEntity::getConversationId, conversationId)
                        // 游标为空 = 一条都没读过，这时不加上界，整段算未读
                        .gt(myCursor != null, MessageEntity::getMessageId, myCursor)
                        // 自己发的不算未读（这是最容易写错的一条）
                        .ne(MessageEntity::getSenderUserId, myUserId));
                unread = count == null ? 0 : count;
            }

            MessageEntity last = lastMessageId == null ? null : lastMessages.get(lastMessageId);
            MessageReceiptMapper.ReceiptWatermark watermark = watermarks.get(conversationId);
            result.add(new ConversationVO(
                    conversationId,
                    peerOfConversation.get(conversationId),
                    lastMessageId,
                    conversation.getLastMessageAt(),
                    previewOf(last),
                    last == null ? null : last.getMessageType(),
                    last == null ? null : last.getSenderUserId(),
                    unread,
                    watermark == null ? null : watermark.deliveredUpTo(),
                    watermark == null ? null : watermark.readUpTo()));
        }
        return result;
    }

    /**
     * 单聊里"对方是谁"：从 {@code single_key}（升序的 {@code "2:3"}）里取不是我那个。
     *
     * <p>为什么不查成员表：那要么每个会话查一次（N+1），要么再来一条 IN 查询；
     * 而 {@code single_key} 本来就是为"同一对人只有一个会话"建的，解析它零成本。
     *
     * <p>缺失或损坏的唯一键返回null，避免向前端提供无效的对方身份。
     */
    private static Long peerOf(ConversationEntity conversation, Long myUserId) {
        String singleKey = conversation.getSingleKey();
        if (singleKey == null) {
            return null;
        }
        int split = singleKey.indexOf(':');
        if (split <= 0) {
            return null;
        }
        try {
            long first = Long.parseLong(singleKey.substring(0, split));
            long second = Long.parseLong(singleKey.substring(split + 1));
            return first == myUserId ? second : first;
        } catch (NumberFormatException e) {
            log.warn("会话的 single_key 不是预期格式，无法解析出对方: conversationId={}, singleKey={}",
                    conversation.getConversationId(), singleKey);
            return null;
        }
    }

    /**
     * 最后一条消息 → 列表里那句摘要（截断 + 把换行/连续空白压成一个空格）。
     *
     * <p>只在这里做"存/展示"该做的事，<b>不判断消息类型</b>：图片/语音要显示成 {@code [图片]}，
     * 那是前端的展示逻辑（它拿到 lastMessageType 自己决定），服务端不该掺和。
     */
    private static String previewOf(MessageEntity message) {
        if (message == null || message.getMessageContent() == null) {
            return null;
        }
        String flat = message.getMessageContent().replaceAll("\\s+", " ").trim();
        return flat.length() > PREVIEW_MAX_LENGTH
                ? flat.substring(0, PREVIEW_MAX_LENGTH) + "…"
                : flat;
    }

    /** 会话列表的条数：不传用默认值，传了就夹在 1..上限之间 */
    private static int clampListSize(Integer size) {
        if (size == null || size <= 0) {
            return DEFAULT_LIST_SIZE;
        }
        return Math.min(size, MAX_LIST_SIZE);
    }

    @Override
    public String singleKey(Long userA, Long userB) {
        long min = Math.min(userA, userB);
        long max = Math.max(userA, userB);
        return min + ":" + max;
    }

    @Override
    public ConversationEntity findSingle(Long userA, Long userB) {
        return conversationMapper.selectOne(Wrappers.<ConversationEntity>lambdaQuery()
                .eq(ConversationEntity::getSingleKey, singleKey(userA, userB)));
    }

    @Override
    public ConversationEntity findOrCreateSingle(Long userA, Long userB) {
        ConversationEntity existing = findSingle(userA, userB);
        if (existing != null) {
            return existing;
        }
        try {
            return createSingle(userA, userB);
        } catch (DuplicateKeyException e) {
            // 并发：另一个请求同时建了同一个会话，唯一索引 uk_conversation_single 拦下了我这次插入。
            // 这时候不能报错 —— 直接把它查出来用。注意这次查询【在事务外】，
            // 用的是新快照，一定看得见对方刚提交的行。
            ConversationEntity created = findSingle(userA, userB);
            if (created == null) {
                // 理论上到不了这里（插入失败说明确实有冲突行），真到了就说明不是 single_key 冲突
                throw e;
            }
            log.info("并发建会话，已复用已存在的会话: singleKey={}, conversationId={}",
                    created.getSingleKey(), created.getConversationId());
            return created;
        }
    }

    @Override
    public void requireMember(Long conversationId, Long userId) {
        Long count = memberMapper.selectCount(Wrappers.<ConversationMemberEntity>lambdaQuery()
                .eq(ConversationMemberEntity::getConversationId, conversationId)
                .eq(ConversationMemberEntity::getUserId, userId));
        if (count == null || count == 0) {
            throw new BizException(403, "你不在这个会话里");
        }
    }

    @Override
    public void touchLastMessage(Long conversationId, Long messageId, LocalDateTime at) {
        // 用带 set 的 update 而不是"先查实体再 updateById"：
        // 一条 SQL 只更新摘要字段，避免把其他字段写回旧值。
        conversationMapper.update(null, Wrappers.<ConversationEntity>lambdaUpdate()
                .eq(ConversationEntity::getConversationId, conversationId)
                // 多实例并发提交时，较旧消息不得覆盖较新的会话摘要。
                .and(w -> w.isNull(ConversationEntity::getLastMessageId)
                        .or().lt(ConversationEntity::getLastMessageId, messageId))
                .set(ConversationEntity::getLastMessageId, messageId)
                .set(ConversationEntity::getLastMessageAt, at));
    }

    @Override
    public void advanceReadCursor(Long conversationId, Long userId, Long messageId) {
        // 条件里带上"当前值比它小（或者还是空）"，把"只前进不后退"交给数据库判断 ——
        // 一条 SQL 完成"读-比较-写"，没有检查与使用之间的窗口
        memberMapper.update(null, Wrappers.<ConversationMemberEntity>lambdaUpdate()
                .eq(ConversationMemberEntity::getConversationId, conversationId)
                .eq(ConversationMemberEntity::getUserId, userId)
                .and(w -> w.isNull(ConversationMemberEntity::getLastReadMessageId)
                        .or()
                        .lt(ConversationMemberEntity::getLastReadMessageId, messageId))
                .set(ConversationMemberEntity::getLastReadMessageId, messageId));
    }

    /** 建会话 + 两个成员行：三件事必须在同一个事务里（否则会出现没有成员的会话） */
    private ConversationEntity createSingle(Long userA, Long userB) {
        return transactionTemplate.execute(status -> {
            ConversationEntity conversation = new ConversationEntity();
            conversation.setSingleKey(singleKey(userA, userB));
            conversation.setCreatedByUserId(userA);
            // 单聊没有名字（标题显示对方昵称/备注，那是展示层的事）
            conversationMapper.insert(conversation);

            insertMember(conversation.getConversationId(), userA);
            insertMember(conversation.getConversationId(), userB);
            return conversation;
        });
    }

    private void insertMember(Long conversationId, Long userId) {
        ConversationMemberEntity member = new ConversationMemberEntity();
        member.setConversationId(conversationId);
        member.setUserId(userId);
        // lastReadMessageId 留空：还没读过任何消息（读过的判据是"读到哪条"，
        // 空值等价于"一条都没读"，比塞个 0 更不容易出错）
        memberMapper.insert(member);
    }
}
