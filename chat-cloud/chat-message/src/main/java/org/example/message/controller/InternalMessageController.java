package org.example.message.controller;

import lombok.RequiredArgsConstructor;
import org.example.common.dto.DeliveredReport;
import org.example.common.dto.ReadReport;
import org.example.common.exception.BizException;
import org.example.common.result.Result;
import org.example.common.support.InternalAuth;
import org.example.message.dto.ReadMessageRequest;
import org.example.message.dto.SendMessageRequest;
import org.example.common.dto.SendMessageCommand;
import org.example.common.dto.SendMessageResult;
import jakarta.validation.Validator;
import org.springframework.beans.factory.annotation.Autowired;
import org.example.message.service.MessageService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/internal/message")
@RequiredArgsConstructor
public class InternalMessageController {
    private final MessageService messageService;

    @Value("${chat.internal.token}")
    private String internalToken;

    @PostMapping("/send")
    public Result<SendMessageResult> send(@RequestHeader(value = InternalAuth.HEADER, required = false) String token,
                                          @RequestBody SendMessageCommand command) {
        InternalAuth.requireToken(internalToken, token);
        if (command == null || command.userId() == null || command.userId() <= 0) {
            throw new BizException(400, "发送身份不合法");
        }
        var request = new SendMessageRequest(command.toUserId(), command.content(),
                command.clientMessageId(), command.messageType());
        var violations = validator.validate(request);
        if (!violations.isEmpty()) throw new BizException(400, violations.iterator().next().getMessage());
        var result = messageService.send(command.userId(), request);
        return Result.success(new SendMessageResult(result.messageId(), result.conversationId(),
                result.createdAt(), result.duplicated()));
    }

    @Autowired
    private Validator validator;

    @PostMapping("/delivered")
    public Result<Void> delivered(@RequestHeader(value = InternalAuth.HEADER, required = false) String token,
                                  @RequestBody DeliveredReport request) {
        InternalAuth.requireToken(internalToken, token);
        if (request == null || request.userId() == null || request.userId() <= 0
                || request.messageIds() == null || request.messageIds().isEmpty()) {
            throw new BizException(400, "送达确认参数不合法");
        }
        messageService.markDelivered(request.userId(), request.messageIds());
        return Result.success();
    }

    @PostMapping("/read")
    public Result<Void> read(@RequestHeader(value = InternalAuth.HEADER, required = false) String token,
                             @RequestBody ReadReport request) {
        InternalAuth.requireToken(internalToken, token);
        if (request == null || request.userId() == null || request.userId() <= 0
                || request.peerUserId() == null || request.upToMessageId() == null) {
            throw new BizException(400, "已读上报参数不合法");
        }
        messageService.markRead(request.userId(), new ReadMessageRequest(request.peerUserId(), request.upToMessageId()));
        return Result.success();
    }
}
