package org.example.relation.controller;

import lombok.RequiredArgsConstructor;
import org.example.common.constant.AuthHeaders;
import org.example.common.result.Result;
import org.example.common.support.RequestUser;
import org.example.relation.service.RequestReminderService;
import org.example.relation.vo.RequestReminderVO;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/relation/requests")
@RequiredArgsConstructor
public class RequestReminderController {
    private final RequestReminderService reminders;

    @GetMapping("/summary")
    public Result<RequestReminderVO> summary(@RequestHeader(value=AuthHeaders.USER_ID, required=false) Long user) {
        return Result.success(reminders.summary(RequestUser.requireId(user)));
    }
}
