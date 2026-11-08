package org.example.message.service.single;

import java.time.LocalDateTime;
import org.example.common.exception.BizException;
import org.example.common.result.Result;
import org.example.message.client.RelationClient;
import org.example.message.dto.SendMessageRequest;
import org.example.message.entity.ConversationEntity;
import org.example.message.entity.MessageEntity;
import org.example.message.entity.MessageReceiptEntity;
import org.example.message.mapper.MessageMapper;
import org.example.message.mapper.MessageReceiptMapper;
import org.example.message.service.send.MessageContentPolicy;
import org.example.message.vo.SendMessageVO;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import feign.FeignException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.example.common.dto.SendMessageCommand;
import org.example.message.service.send.MessageSender;
/** 单聊发送：权限和幂等检查、事务写入，提交后委托投递。 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SingleMessageSender implements MessageSender<SendMessageCommand,SendMessageVO> {
    private static final int DELIVERY_PENDING=2;
    private final MessageMapper messageMapper;
    private final MessageReceiptMapper receiptMapper;
    private final ConversationService conversationService;
    private final RelationClient relationClient;
    private final TransactionTemplate transactionTemplate;
    private final SingleMessageDelivery delivery;

    public SendMessageVO send(SendMessageCommand command) {
        Long myUserId=command.userId();
        var req=new SendMessageRequest(command.toUserId(),command.content(),command.clientMessageId(),command.messageType());
        Long toUserId = req.toUserId();

        // ① 加自己没意义（前端也会拦，但后端不能依赖前端）
        if (myUserId.equals(toUserId)) {
            throw new BizException(400, "不能给自己发消息");
        }

        // ② 消息类型 + 内容校验：
        //    类型必须是 1~4 之一；图片/文件消息的内容必须是【本服务上传接口签发的地址】。
        //    这一条不能省 —— 放任任意 URL 就等于把这个聊天开成别人的图床/钓鱼载体。
        int messageType = MessageContentPolicy.requireSupportedType(req.messageType());
        MessageContentPolicy.requireValidContent(messageType, req.content());

        // ③ 只能给好友发消息。这条规则属于关系域，问 chat-relation
        if (!areFriends(myUserId, toUserId)) {
            throw new BizException(403, "对方不是你的好友，不能发消息");
        }

        // ④ 幂等：先查一次。客户端"没收到受理 ack 就重发"是正常行为，
        //    绝大多数重发都会命中这里（连异常都不用走）
        MessageEntity existing = findByClientMessageId(myUserId, req.clientMessageId());
        if (existing != null) {
            log.info("重复提交，返回已存在的消息: senderUserId={}, clientMessageId={}, messageId={}",
                    myUserId, req.clientMessageId(), existing.getMessageId());
            // 不能只"返回已有的那条"就完了：上一次可能只跑到"落库"就中断了，
            // 登记待送达那一步没做成 —— 见 repairDeliveryIfNeeded 的注释
            delivery.repairDeliveryIfNeeded(existing);
            return toSendVO(existing, true);
        }

        // ⑤ 会话：查找或创建（单聊）。并发靠 conversation.single_key 的唯一索引兜底
        ConversationEntity conversation = conversationService.findOrCreateSingle(myUserId, toUserId);

        String content = req.content().trim();
        MessageEntity saved;
        try {
            saved = transactionTemplate.execute(
                    status -> writeMessage(myUserId, toUserId, conversation, req, content));
        } catch (DuplicateKeyException e) {
            // 并发重发：两个请求同时进来，唯一索引拦下了我这一个。
            // 这里是【正常情况】而不是错误 —— 返回已存在的那条。
            //
            // 注意这个 catch 在【事务外】：TransactionTemplate 已经把事务回滚了，
            // 所以下面这次查询用的是新快照，一定看得见对方刚提交的行。
            // （如果还在同一个事务里查，MySQL 的 REPEATABLE READ 可能看不到 → 会误报 500）
            MessageEntity duplicated = findByClientMessageId(myUserId, req.clientMessageId());
            if (duplicated == null) {
                throw e;
            }
            return toSendVO(duplicated, true);
        }

        // ⑥ 落库之后才推（见下面方法注释）——这一步失败不影响"发送成功"
        delivery.deliver(saved, toUserId);

        log.info("消息已落库: messageId={}, conversationId={}, {} -> {}",
                saved.getMessageId(), saved.getConversationId(), myUserId, toUserId);
        return toSendVO(saved, false);
    }

    private MessageEntity writeMessage(Long myUserId, Long toUserId, ConversationEntity conversation,
                                       SendMessageRequest req, String content) {
        MessageEntity message = new MessageEntity();
        message.setConversationId(conversation.getConversationId());
        message.setSenderUserId(myUserId);
        message.setClientMessageId(req.clientMessageId());
        message.setMessageContent(content);
        message.setMessageType(req.typeOrDefault());
        // 时间由应用生成而不是靠数据库 DEFAULT：受理结果要返回它、会话冗余字段也要用它，
        // 需要在插入【之前】就拿到这个值（否则插完还得再查一次）
        message.setCreatedAt(LocalDateTime.now());
        messageMapper.insert(message);

        MessageReceiptEntity receipt = new MessageReceiptEntity();
        receipt.setMessageId(message.getMessageId());
        receipt.setConversationId(conversation.getConversationId());
        receipt.setReceiverUserId(toUserId);
        // delivered_at 留空 = 还没送达（这是唯一判据）
        receipt.setDeliveryStatus(DELIVERY_PENDING);
        receiptMapper.insert(receipt);

        conversationService.touchLastMessage(
                conversation.getConversationId(), message.getMessageId(), message.getCreatedAt());
        return message;
    }

    private MessageEntity findByClientMessageId(Long senderUserId, String clientMessageId) {
        return messageMapper.selectOne(Wrappers.<MessageEntity>lambdaQuery()
                .eq(MessageEntity::getSenderUserId, senderUserId)
                .eq(MessageEntity::getClientMessageId, clientMessageId));
    }

    private SendMessageVO toSendVO(MessageEntity message, boolean duplicated) {
        return new SendMessageVO(
                message.getMessageId(), message.getConversationId(), message.getCreatedAt(), duplicated);
    }

    private boolean areFriends(Long userId, Long otherUserId) {
        Result<Boolean> result;
        try {
            result = relationClient.areFriends(userId, otherUserId);
        } catch (FeignException e) {
            // 【为什么只接 FeignException，而不是 catch (Exception)】
            //
            // 先看全局异常处理器管不着什么：它只负责"异常 → Result"的最后一跳，
            // 不知道这个异常是"依赖挂了"还是"我们自己有 Bug"。把这句定性放在调用点，
            // 才可能给出正确的 503 和带双方 userId 的日志。
            //
            // 再确认"只接 FeignException 够不够"——Feign 的传输层失败全都是它的子类：
            //   · 连不上 / 超时      → RetryableException（extends FeignException）
            //   · 非 2xx            → FeignException$Xxx（默认 ErrorDecoder 抛的）
            //   · 响应体解不开       → DecodeException（extends FeignException）
            // 连"服务一个实例都没有"这种也是：FeignBlockingLoadBalancerClient 不抛异常，
            // 而是造一个 503 的 Response，最后变成 FeignException$ServiceUnavailable
            //（已核对该类的字节码：getstatic HttpStatus.SERVICE_UNAVAILABLE → Response.status）。
            // 所以这三种情况的覆盖是完整的。
            //
            // 为什么不写 catch (Exception)：我们自己的 Bug（NPE、参数拼错）会一起被
            // 报成"关系服务暂时不可用"，把排查方向直接带偏到对方服务上。
            // 接不下的就让它冒到 GlobalExceptionHandler 变成 500 —— 那才是实话。
            log.error("调用 chat-relation 校验好友关系失败: {} <-> {}", userId, otherUserId, e);
            throw new BizException(503, "关系服务暂时不可用，请稍后重试");
        }
        // 这一段仍然必须有，而且和上面的 catch 是【互补】关系：
        // 我们的服务把业务错误也包成 "HTTP 200 + Result.code != 200"，
        // 而 Feign 只在"非 2xx"时抛异常 —— 所以"调用成功但业务失败"根本不会进 catch，
        // 只会走到这里。少了它，"用户不存在"会被当成"不是好友"（Boolean.TRUE.equals(null) = false），
        // 用户会莫名其妙发现消息发不出去。
        if (result == null || !result.isSuccess() || result.getData() == null) {
            log.warn("校验好友关系返回异常: {} <-> {}", userId, otherUserId);
            throw new BizException(503, "关系服务暂时不可用，请稍后重试");
        }
        return Boolean.TRUE.equals(result.getData());
    }
}
