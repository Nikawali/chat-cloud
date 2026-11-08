package org.example.message.service.single;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import org.example.common.exception.BizException;
import org.example.message.entity.ConversationEntity;
import org.example.message.entity.MessageEntity;
import org.example.message.entity.MessageReceiptEntity;
import org.example.message.mapper.MessageMapper;
import org.example.message.mapper.MessageReceiptMapper;
import org.example.message.vo.MessageVO;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/** 单聊历史与未送达正文查询，不创建空会话。 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SingleMessageQuery {
    private static final int DEFAULT_PAGE_SIZE=20;
    private final MessageMapper messageMapper;
    private final MessageReceiptMapper receiptMapper;
    private final ConversationService conversationService;
    private final MessageVoAssembler assembler;
    @Value("${chat.message.max-page-size:100}") private int maxPageSize;

    public List<MessageVO> history(Long myUserId, Long peerUserId, Long beforeMessageId, Integer size) {
        if (peerUserId == null) {
            throw new BizException(400, "对方 userId 不能为空");
        }
        ConversationEntity conversation = conversationService.findSingle(myUserId, peerUserId);
        if (conversation == null) {
            // 还没聊过 → 空列表。这里【不建会话】：只是打开看一眼，不该产生副作用
            return List.of();
        }

        int limit = clampSize(size);
        List<MessageEntity> desc = messageMapper.selectList(Wrappers.<MessageEntity>lambdaQuery()
                .eq(MessageEntity::getConversationId, conversation.getConversationId())
                // 翻页锚点：只取比它更早的
                .lt(beforeMessageId != null, MessageEntity::getMessageId, beforeMessageId)
                // 倒序 + limit 才能吃到 idx_message_conversation 索引；
                // 正序 + limit 会先把这个会话的所有消息都读出来再截断
                .orderByDesc(MessageEntity::getMessageId)
                // last() 直接拼在 SQL 末尾。这里拼的是我们自己 clampSize 算出来的 int，
                // 不是用户输入，所以没有注入风险（也正因如此才能这么写）
                .last("limit " + limit));

        List<MessageEntity> asc = new ArrayList<>(desc);
        // 倒着查是为了取"最近 N 条"，但返回给前端要从旧到新（界面从上往下就是时间顺序）
        Collections.reverse(asc);
        return assembler.assemble(myUserId, peerUserId, asc);
    }

    public List<MessageVO> undelivered(Long myUserId, Integer size) {
        int limit = clampSize(size);
        List<MessageReceiptEntity> receipts = receiptMapper.selectList(
                Wrappers.<MessageReceiptEntity>lambdaQuery()
                        .eq(MessageReceiptEntity::getReceiverUserId, myUserId)
                        // 唯一判据：delivered_at 为空。
                        // （delivery_status 只记"最后一次投递尝试的结果"，不拿它判断送达与否）
                        .isNull(MessageReceiptEntity::getDeliveredAt)
                        .orderByAsc(MessageReceiptEntity::getMessageId)
                        .last("limit " + limit));
        if (receipts.isEmpty()) {
            return List.of();
        }

        List<Long> messageIds = receipts.stream().map(MessageReceiptEntity::getMessageId).toList();
        List<MessageEntity> messages = messageMapper.selectBatchIds(messageIds);
        // selectBatchIds 不保证返回顺序，补拉必须按 messageId 顺序重放，所以显式排一次
        messages.sort(Comparator.comparing(MessageEntity::getMessageId));

        // 这些消息都是"发给我的"，所以没有"对方的送达时间"要展示 → 第二参传 null，省一次查询
        return assembler.assemble(myUserId, null, messages);
    }

    private int clampSize(Integer size) {
        if (size == null || size <= 0) {
            return DEFAULT_PAGE_SIZE;
        }
        return Math.min(size, maxPageSize);
    }
}
