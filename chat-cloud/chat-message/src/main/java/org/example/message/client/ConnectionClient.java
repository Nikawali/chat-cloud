package org.example.message.client;

import org.example.common.constant.ServiceNames;
import org.example.common.dto.PushFrameRequest;
import org.example.common.result.Result;
import org.example.common.support.InternalAuth;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.*;
import java.net.URI;

@FeignClient(name = ServiceNames.CHAT_CONNECTION, url = "http://connection.invalid")
public interface ConnectionClient {
    @PostMapping("/internal/connection/push-batch")
    Result<java.util.List<Long>> pushBatch(URI target, @RequestHeader(InternalAuth.HEADER) String token,
            @RequestBody org.example.common.dto.BatchPushFrameRequest request);
    @GetMapping("/internal/connection/online/{userId}")
    Result<Boolean> online(URI target, @RequestHeader(InternalAuth.HEADER) String token,
                           @PathVariable("userId") Long userId);

    @PostMapping("/internal/connection/push")
    Result<Boolean> push(URI target, @RequestHeader(InternalAuth.HEADER) String token,
                         @RequestBody PushFrameRequest request);
}
