package org.example.message.controller;
import org.example.message.service.group.GroupReceiptService;

import lombok.RequiredArgsConstructor;
import org.example.common.dto.*;
import org.example.common.result.Result;
import org.example.common.support.InternalAuth;
import org.example.message.service.send.MessageSender;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/internal/message/group")
@RequiredArgsConstructor
public class InternalGroupMessageController {
    private final MessageSender<SendGroupMessageCommand,SendGroupMessageResult> messages;
    private final GroupReceiptService receipts;
    @PostMapping("/receipt")
    public Result<GroupReceiptState> receipt(@RequestHeader(value=InternalAuth.HEADER,required=false) String token,@RequestBody GroupReceiptCommand command) {
        InternalAuth.requireToken(internalToken,token);
        return Result.success(receipts.report(command));
    }
    @Value("${chat.internal.token}")
    private String internalToken;

    @PostMapping("/send")
    public Result<SendGroupMessageResult> send(
            @RequestHeader(value = InternalAuth.HEADER, required = false) String token,
            @RequestBody SendGroupMessageCommand command) {
        InternalAuth.requireToken(internalToken, token);
        return Result.success(messages.send(command));
    }
}
