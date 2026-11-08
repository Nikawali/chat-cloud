package org.example.message.service.group;
import org.example.message.mq.GroupMessagePublisher;

import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import lombok.RequiredArgsConstructor;
import org.example.common.dto.*;
import org.example.common.exception.BizException;
import org.example.message.entity.GroupMessageEntity;
import org.example.message.mapper.GroupMessageMapper;
import org.example.message.service.send.MessageContentPolicy;
import org.example.message.service.send.MessageSender;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

@Service
@RequiredArgsConstructor
public class GroupMessageSender implements MessageSender<SendGroupMessageCommand,SendGroupMessageResult> {
    private final GroupMessageMapper messages;
    private final GroupAccess access;
    private final TransactionTemplate transactions;
    private final GroupMessagePublisher publisher;

    @Override
    public SendGroupMessageResult send(SendGroupMessageCommand req) {
        if (req == null || req.userId() == null || req.userId() <= 0
                || req.groupId() == null || req.groupId() <= 0)
            throw new BizException(400, "群消息发送参数不合法");
        // 与表中 ASCII、区分大小写的幂等键约定一致，兼容 UUID 和前端随机十六进制 ID。
        if (req.clientMessageId() == null || !req.clientMessageId().matches("[A-Za-z0-9_-]{1,64}"))
            throw new BizException(400, "clientMessageId 不合法");
        if (req.content() == null || req.content().isBlank() || req.content().length() > 2000)
            throw new BizException(400, "消息内容不能为空且不能超过 2000 个字符");
        int type = MessageContentPolicy.requireSupportedType(req.messageType());
        MessageContentPolicy.requireValidContent(type, req.content());
        String content = req.content().trim();
        try {
            var accepted = transactions.execute(status -> {
                // 与关系服务入群/邀请一致：先锁群，再查成员。
                // 同库事务内校验，避免远程权限查询与实际写入之间发生成员状态变化。
                var head = access.lockForSend(req.groupId(),req.userId());
                // 首次快照查询在群锁之后，等待同群发送提交后可看到已存在消息。
                var existing = messages.findDuplicate(req.userId(), req.clientMessageId());
                if (existing != null) return duplicate(existing, req, type, content);
                if (head.lastSeq() == Long.MAX_VALUE) throw new BizException(409, "群消息序号已达上限");
                var row = new GroupMessageEntity();
                row.setGroupId(req.groupId());
                row.setSeq(head.lastSeq() + 1);
                row.setSenderUserId(req.userId());
                row.setClientMessageId(req.clientMessageId());
                row.setMessageType(type);
                row.setMessageContent(content);
                row.setCreatedAt(LocalDateTime.now().truncatedTo(ChronoUnit.MICROS));
                messages.insert(row);
                if (messages.updateHead(row) != 1) throw new IllegalStateException("群摘要更新失败");
                return result(row, false);
            });
            publisher.publish(accepted.messageId());
            return accepted;
        } catch (DuplicateKeyException e) {
            // 跨群并发复用同一幂等键时，先回滚，再用新快照检查冲突。
            var existing = messages.findDuplicate(req.userId(), req.clientMessageId());
            if (existing == null) throw e;
            return duplicate(existing, req, type, content);
        }
    }

    private SendGroupMessageResult duplicate(GroupMessageEntity row, SendGroupMessageCommand req, int type, String content) {
        if (!row.getGroupId().equals(req.groupId()) || row.getMessageType() != type
                || !row.getMessageContent().equals(content))
            throw new BizException(409, "同一 clientMessageId 不能更换群、消息类型或内容");
        return result(row, true);
    }

    private SendGroupMessageResult result(GroupMessageEntity row, boolean duplicated) {
        return new SendGroupMessageResult(row.getMessageId(), row.getGroupId(), row.getSeq(), row.getCreatedAt(), duplicated);
    }
}
