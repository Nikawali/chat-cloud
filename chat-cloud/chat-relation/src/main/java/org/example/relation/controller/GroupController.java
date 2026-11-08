package org.example.relation.controller;


import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import org.springframework.validation.annotation.Validated;
import org.example.common.constant.AuthHeaders;
import org.example.common.support.RequestUser;
import org.example.common.result.Result;
import org.example.relation.dto.*;
import org.example.relation.vo.*;
import org.example.relation.service.GroupService;
import java.util.List;

@RestController
@RequestMapping("/relation/group")
@RequiredArgsConstructor
@Validated
public class GroupController {
    private final GroupService groups;
    @PostMapping
    public Result<GroupVO> create(@RequestHeader(value=AuthHeaders.USER_ID, required=false) Long user,
                                  @Valid @RequestBody CreateGroupRequest request) {
        return Result.success(groups.create(RequestUser.requireId(user), request));
    }
    @GetMapping("/mine")
    public Result<List<GroupVO>> mine(@RequestHeader(value=AuthHeaders.USER_ID, required=false) Long user,
                                      @RequestParam(defaultValue="0") @PositiveOrZero long after) {
        return Result.success(groups.mine(RequestUser.requireId(user), after));
    }
    @GetMapping("/search")
    public Result<List<GroupVO>> search(@RequestHeader(value=AuthHeaders.USER_ID, required=false) Long user,
            @RequestParam @NotBlank @Size(max=100) String keyword) {
        return Result.success(groups.search(RequestUser.requireId(user), keyword));
    }
    @GetMapping("/{groupId}")
    public Result<GroupVO> detail(@RequestHeader(value=AuthHeaders.USER_ID, required=false) Long user,
                                  @PathVariable @Positive Long groupId) {
        return Result.success(groups.detail(RequestUser.requireId(user), groupId));
    }
    @PostMapping("/{groupId}/join")
    public Result<GroupVO> join(@RequestHeader(value=AuthHeaders.USER_ID, required=false) Long user,
                                @PathVariable @Positive Long groupId) {
        return Result.success(groups.join(RequestUser.requireId(user), groupId));
    }
    @PostMapping("/{groupId}/invitations")
    public Result<Integer> invite(@RequestHeader(value=AuthHeaders.USER_ID, required=false) Long user,
            @PathVariable @Positive Long groupId, @Valid @RequestBody InviteGroupRequest request) {
        return Result.success(groups.invite(RequestUser.requireId(user), groupId, request.userIds()));
    }
    @GetMapping("/invitations")
    public Result<List<GroupInvitationVO>> invitations(@RequestHeader(value=AuthHeaders.USER_ID, required=false) Long user,
            @RequestParam(defaultValue="0") @PositiveOrZero long after) {
        return Result.success(groups.invitations(RequestUser.requireId(user), after));
    }
    @GetMapping("/invitations/count")
    public Result<Long> invitationCount(@RequestHeader(value=AuthHeaders.USER_ID, required=false) Long user) {
        return Result.success(groups.invitationCount(RequestUser.requireId(user)));
    }
    @PostMapping("/invitations/{invitationId}/accept")
    public Result<GroupVO> accept(@RequestHeader(value=AuthHeaders.USER_ID, required=false) Long user,
                                  @PathVariable @Positive Long invitationId) {
        return Result.success(groups.accept(RequestUser.requireId(user), invitationId));
    }
    @PostMapping("/invitations/{invitationId}/reject")
    public Result<Void> reject(@RequestHeader(value=AuthHeaders.USER_ID, required=false) Long user,
                               @PathVariable @Positive Long invitationId) {
        groups.reject(RequestUser.requireId(user), invitationId);
        return Result.success();
    }
    @GetMapping("/{groupId}/members")
    public Result<List<GroupMemberVO>> members(@RequestHeader(value=AuthHeaders.USER_ID, required=false) Long user,
            @PathVariable @Positive Long groupId, @RequestParam(defaultValue="0") @PositiveOrZero long after) {
        return Result.success(groups.members(RequestUser.requireId(user), groupId, after));
    }
}
