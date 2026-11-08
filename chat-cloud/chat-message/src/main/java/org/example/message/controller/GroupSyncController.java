package org.example.message.controller;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;
import org.example.common.constant.AuthHeaders;
import org.example.common.support.RequestUser;
import org.example.common.result.Result;
import org.example.message.service.group.GroupSyncService;
import org.example.message.mapper.GroupSyncMapper;
import java.util.List;
@RestController
@RequestMapping("/message/group")
@RequiredArgsConstructor
public class GroupSyncController {
 private final GroupSyncService sync;
 @GetMapping("/sync-heads")
 public Result<List<GroupSyncMapper.Head>> heads(@RequestHeader(value=AuthHeaders.USER_ID,required=false) Long user,@RequestParam(defaultValue="0") long after) {
  return Result.success(sync.heads(RequestUser.requireId(user),after));
 }
 @GetMapping("/{groupId}/messages")
 public Result<GroupSyncService.Page> pull(@RequestHeader(value=AuthHeaders.USER_ID,required=false) Long user,@PathVariable Long groupId,@RequestParam long memberVersion,@RequestParam(defaultValue="0") long afterSeq) {
  return Result.success(sync.pull(RequestUser.requireId(user),groupId,memberVersion,afterSeq));
 }
}
