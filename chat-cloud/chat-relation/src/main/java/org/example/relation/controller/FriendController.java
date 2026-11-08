package org.example.relation.controller;

import java.util.List;

import org.example.common.constant.AuthHeaders;
import org.example.common.result.Result;
import org.example.common.support.RequestUser;
import org.example.relation.dto.AddFriendRequest;
import org.example.relation.dto.UpdateRemarkRequest;
import org.example.relation.service.FriendService;
import org.example.relation.vo.FriendRequestVO;
import org.example.relation.vo.FriendVO;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;

/**
 * 好友接口（经网关 {@code /api/relation/**} 暴露给前端）。
 *
 * <p><b>⚠️ 路径前缀必须是 {@code /relation/friend}</b>，不是 {@code /friend}。
 * 因为网关那条路由是 {@code Path=/api/relation/**} + {@code StripPrefix=1}：
 * 它会把 {@code /api} 去掉，服务收到的是 {@code /relation/friend/...}。
 * （这个类以前写的是 {@code /friend}，那时候它一个方法都没有所以没人发现 ——
 * 一旦加了接口，走网关就是 404。别的服务是 {@code /api/user/** → /user/**}、
 * {@code /api/auth/** → /auth/**}，规律一样。）
 *
 * <p><b>"我是谁"只认 {@code X-User-Id}</b>：它是网关验完 token 之后注入的，
 * 前端伪造不了（网关会先把客户端传的 {@code X-User-*} 全部剥掉）。
 * 所有接口的入参里都<b>没有</b> "我是谁"，只有"对方是谁" —— 这就是防越权的根本。
 */
@RestController
@RequestMapping("/relation/friend")
@RequiredArgsConstructor
public class FriendController {

    private final FriendService friendService;

    /**
     * 【加好友】发起申请。
     *
     * <p>前端在"添加好友"页搜到人之后调它。注意传的是 {@code toUserId}，
     * 不是手机号 —— 手机号只是搜索用的关键词，搜到之后就换成 userId 了。
     */
    @PostMapping("/request")
    public Result<Void> addFriend(
            @RequestHeader(value = AuthHeaders.USER_ID, required = false) Long userId,
            @Valid @RequestBody AddFriendRequest req) {
        friendService.addFriend(RequestUser.requireId(userId), req);
        return Result.success();
    }

    /**
     * 【我和他是什么关系】—— 加好友页据此决定按钮显示什么。
     *
     * <p>返回值：{@code NONE / PENDING_OUT / PENDING_IN / FRIEND / SELF}（见 RelationStatus）。
     * 没有它的话，前端只能永远显示"添加到通讯录"，点了才发现"已经是好友了" —— 体验很差。
     */
    @GetMapping("/status")
    public Result<String> status(
            @RequestHeader(value = AuthHeaders.USER_ID, required = false) Long userId,
            @RequestParam("userId") Long targetUserId) {
        return Result.success(friendService.relationStatus(RequestUser.requireId(userId), targetUserId));
    }

    /** 【新的朋友】别人申请加我的、还没处理的 */
    @GetMapping("/requests")
    public Result<List<FriendRequestVO>> requests(
            @RequestHeader(value = AuthHeaders.USER_ID, required = false) Long userId) {
        return Result.success(friendService.listIncomingRequests(RequestUser.requireId(userId)));
    }

    /**
     * 同意一条申请。
     *
     * <p>路径里的 {@code friendRequestId} 是<b>申请记录的 id</b>
     * （{@code relation_friend_request.friend_request_id}，也就是"新的朋友"列表每项里的
     * {@code friendRequestId}），不是用户的 id —— 两者很容易混，前端从列表里拿到哪个就传哪个。
     */
    @PostMapping("/request/{friendRequestId}/accept")
    public Result<Void> accept(
            @RequestHeader(value = AuthHeaders.USER_ID, required = false) Long userId,
            @PathVariable("friendRequestId") Long requestId) {
        friendService.handleRequest(RequestUser.requireId(userId), requestId, true);
        return Result.success();
    }

    /** 拒绝一条申请（之后对方还可以再申请） */
    @PostMapping("/request/{friendRequestId}/reject")
    public Result<Void> reject(
            @RequestHeader(value = AuthHeaders.USER_ID, required = false) Long userId,
            @PathVariable("friendRequestId") Long requestId) {
        friendService.handleRequest(RequestUser.requireId(userId), requestId, false);
        return Result.success();
    }

    /** 【好友列表】我所有的好友（用户卡片 + 备注）。排序/分组由前端做 */
    @GetMapping("/list")
    public Result<List<FriendVO>> list(
            @RequestHeader(value = AuthHeaders.USER_ID, required = false) Long userId) {
        return Result.success(friendService.listFriends(RequestUser.requireId(userId)));
    }

    /**
     * 改备注。
     *
     * <p>备注是<b>单向</b>的：只影响我自己的好友列表。传空串 = 清除备注。
     */
    @PutMapping("/{friendId}/remark")
    public Result<Void> updateRemark(
            @RequestHeader(value = AuthHeaders.USER_ID, required = false) Long userId,
            @PathVariable("friendId") Long friendId,
            @Valid @RequestBody UpdateRemarkRequest req) {
        friendService.updateRemark(RequestUser.requireId(userId), friendId, req.remark());
        return Result.success();
    }

    /**
     * 【查单个好友】用户卡片 + 我给他的备注。
     *
     * <p>聊天页的标题要它（"备注，没有就显示昵称"），而这个备注只有本服务知道。
     *
     * <p>⚠️ 路径 {@code /{friendId}} 和上面的 {@code /status}、{@code /list}、{@code /requests}
     * 是能共存的：Spring 的路径匹配里<b>字面量段优先于变量段</b>，
     * 所以 {@code /relation/friend/status} 走上面那个方法，{@code /relation/friend/2} 才走这里。
     */
    @GetMapping("/{friendId}")
    public Result<FriendVO> getFriend(
            @RequestHeader(value = AuthHeaders.USER_ID, required = false) Long userId,
            @PathVariable("friendId") Long friendId) {
        return Result.success(friendService.getFriend(RequestUser.requireId(userId), friendId));
    }

    /** 删好友（双向两行一起删；想再加回来要重新申请） */
    @DeleteMapping("/{friendId}")
    public Result<Void> deleteFriend(
            @RequestHeader(value = AuthHeaders.USER_ID, required = false) Long userId,
            @PathVariable("friendId") Long friendId) {
        friendService.deleteFriend(RequestUser.requireId(userId), friendId);
        return Result.success();
    }

    /*
     * 这里以前有一个私有方法 requireUserId(Long)：从网关注入的 X-User-Id 取当前用户，
     * 取不到就 401（文案还必须逐字一致）。它和另外三个 controller 里的那份一模一样，
     * 现在统一走 chat-common 的 RequestUser#requireId —— 纯 Java 实现，
     * 不会把 spring-web 拖给 WebFlux 的网关（那是 chat-common 的红线）。
     */
}
