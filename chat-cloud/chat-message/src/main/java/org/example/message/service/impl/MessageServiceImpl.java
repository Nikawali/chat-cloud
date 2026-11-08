package org.example.message.service.impl;

import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.example.common.dto.SendMessageCommand;
import org.example.message.dto.ReadMessageRequest;
import org.example.message.dto.SendMessageRequest;
import org.example.message.vo.MessageVO;
import org.example.message.vo.SendMessageVO;
import org.example.message.service.MessageService;
import org.example.message.service.single.SingleMessageSender;
import org.example.message.service.single.SingleMessageQuery;
import org.example.message.service.single.SingleMessageReceipt;

/** 保留原HTTP与内部接口契约，各项单聊业务委托专门组件。 */
@Service
@RequiredArgsConstructor
public class MessageServiceImpl implements MessageService {
    private final SingleMessageSender sender;
    private final SingleMessageQuery queries;
    private final SingleMessageReceipt receipts;

    @Override
    public SendMessageVO send(Long user, SendMessageRequest req) {
        return sender.send(new SendMessageCommand(user, req.toUserId(), req.content(),
                req.clientMessageId(), req.messageType()));
    }

    @Override
    public List<MessageVO> history(Long user, Long peer, Long before, Integer size) {
        return queries.history(user, peer, before, size);
    }

    @Override
    public List<MessageVO> undelivered(Long user, Integer size) {
        return queries.undelivered(user, size);
    }

    @Override
    public void markRead(Long user, ReadMessageRequest req) {
        receipts.markRead(user, req);
    }

    @Override
    public void markDelivered(Long user, List<Long> ids) {
        receipts.markDelivered(user, ids);
    }
}
