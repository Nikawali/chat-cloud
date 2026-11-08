package org.example.connection.client;

import org.example.common.constant.ServiceNames;
import org.example.common.dto.DeliveredReport;
import org.example.common.dto.ReadReport;
import org.example.common.dto.SendMessageCommand;
import org.example.common.dto.SendMessageResult;
import org.example.common.result.Result;
import org.example.common.support.InternalAuth;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;

/** 通过内部接口处理消息收据；连接服务不访问消息数据库。 */
@FeignClient(name = ServiceNames.CHAT_MESSAGE)
public interface MessageClient {
    @PostMapping("/internal/message/group/receipt")
    Result<org.example.common.dto.GroupReceiptState> groupReceipt(@RequestHeader(InternalAuth.HEADER) String token,@RequestBody org.example.common.dto.GroupReceiptCommand command);
    @PostMapping("/internal/message/group/send")
    Result<org.example.common.dto.SendGroupMessageResult> sendGroup(
            @RequestHeader(InternalAuth.HEADER) String token,
            @RequestBody org.example.common.dto.SendGroupMessageCommand command);

    @PostMapping("/internal/message/send")
    Result<SendMessageResult> send(@RequestHeader(InternalAuth.HEADER) String token,
                                  @RequestBody SendMessageCommand command);

    @PostMapping("/internal/message/delivered")
    Result<Void> delivered(@RequestHeader(InternalAuth.HEADER) String token,
                          @RequestBody DeliveredReport report);

    @PostMapping("/internal/message/read")
    Result<Void> read(@RequestHeader(InternalAuth.HEADER) String token,
                     @RequestBody ReadReport report);
}
