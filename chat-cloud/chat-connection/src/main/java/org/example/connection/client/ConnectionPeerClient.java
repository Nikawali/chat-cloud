package org.example.connection.client;

import java.net.URI;
import org.example.common.dto.KickConnectionRequest;
import org.example.common.result.Result;
import org.example.common.support.InternalAuth;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.*;

/** URI 来自服务发现，显式 URL 禁用随机负载均衡；每次由 URI 参数指定目标。 */
@FeignClient(name = "connection-peer", url = "http://connection.invalid")
public interface ConnectionPeerClient {
    @PostMapping("/internal/connection/kick")
    Result<Boolean> kick(URI target, @RequestHeader(InternalAuth.HEADER) String token,
                         @RequestBody KickConnectionRequest request);
}
