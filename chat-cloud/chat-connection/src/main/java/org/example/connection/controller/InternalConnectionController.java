package org.example.connection.controller;

import lombok.RequiredArgsConstructor;
import org.example.common.dto.PushFrameRequest;
import org.example.common.dto.KickConnectionRequest;
import org.example.connection.session.ConnectionSessions;
import org.example.connection.session.OnlineUserRegistry;
import org.example.common.exception.BizException;
import org.example.common.result.Result;
import org.example.common.support.InternalAuth;
import org.example.connection.service.ConnectionSender;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/internal/connection")
@RequiredArgsConstructor
public class InternalConnectionController {
    private final ConnectionSender sender;
    private final ConnectionSessions sessions;

    @Value("${chat.internal.token}")
    private String internalToken;

    @GetMapping("/online/{userId}")
    public Result<Boolean> online(@RequestHeader(value = InternalAuth.HEADER, required = false) String token,
                                  @PathVariable("userId") Long userId) {
        InternalAuth.requireToken(internalToken, token);
        requireUserId(userId);
        return Result.success(sender.isOnline(userId));
    }

    @PostMapping("/push")
    public Result<Boolean> push(@RequestHeader(value = InternalAuth.HEADER, required = false) String token,
                                @RequestBody PushFrameRequest request) {
        InternalAuth.requireToken(internalToken, token);
        if (request == null || request.frame() == null || !(request.frame().get("type") instanceof String)) {
            throw new BizException(400, "缺少有效的 WS 帧");
        }
        requireUserId(request.userId());
        if (request.sessionId() == null || request.sessionId().isBlank()) {
            throw new BizException(400, "缺少目标连接标识");
        }
        return Result.success(sender.send(request.userId(), request.sessionId(), request.frame()));
    }

    @PostMapping("/push-batch")
    public Result<java.util.List<Long>> pushBatch(@RequestHeader(value=InternalAuth.HEADER,required=false) String token,
            @RequestBody org.example.common.dto.BatchPushFrameRequest request) {
        InternalAuth.requireToken(internalToken,token);
        if(request==null || request.frame()==null || !(request.frame().get("type") instanceof String)
                || request.targets()==null || request.targets().isEmpty() || request.targets().size()>100)
            throw new BizException(400,"批量推送参数不合法");
        var users=new java.util.HashSet<Long>();
        for(var target:request.targets()) {
            if(target==null) throw new BizException(400,"目标不能为空");
            requireUserId(target.userId());
            if(!users.add(target.userId()) || target.sessionId()==null || target.sessionId().isBlank())
                throw new BizException(400,"目标连接不合法");
        }
        return Result.success(sender.sendBatch(request));
    }

    @PostMapping("/kick")
    public Result<Boolean> kick(@RequestHeader(value = InternalAuth.HEADER, required = false) String token,
                                @RequestBody KickConnectionRequest request) {
        InternalAuth.requireToken(internalToken, token);
        if (request == null || request.sessionId() == null || request.sessionId().isBlank()) {
            throw new BizException(400, "缺少目标连接标识");
        }
        requireUserId(request.userId());
        return Result.success(sessions.kick(request.userId(), request.sessionId(),
                OnlineUserRegistry.CLOSE_CODE_KICKED));
    }

    private static void requireUserId(Long userId) {
        if (userId == null || userId <= 0) {
            throw new BizException(400, "userId 不合法");
        }
    }
}
