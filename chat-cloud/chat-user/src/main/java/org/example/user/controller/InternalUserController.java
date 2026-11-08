package org.example.user.controller;

import java.util.List;

import org.example.common.dto.UserBatchRequest;
import org.example.common.dto.UserCreateRequest;
import org.example.common.dto.UserDTO;
import org.example.common.dto.UserProfileDTO;
import org.example.common.dto.UserVerifyRequest;
import org.example.common.exception.BizException;
import org.example.common.result.Result;
import org.example.user.service.UserService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import lombok.RequiredArgsConstructor;

/**
 * 【内部接口】只给<b>其他服务</b>通过 Feign 调用（现在有 chat-auth 和 chat-message），
 * 不对公网开放。
 *
 * <p>路径故意用 {@code /internal/**}：网关只配了 {@code /api/user/**} → chat-user 的路由，
 * 所以外部请求打不到 {@code /internal/**}，避免有人绕过短信验证码直接注册。
 *
 * <p><b>为什么内部调用不走对外的 {@code /user/**}？</b> 两个原因：
 * <ol>
 *   <li>对外接口是给前端设计的，将来给它加限流/权限校验会连带把内部调用卡死</li>
 *   <li>语义清楚：看到 {@code /internal/} 就知道"这个不该暴露到公网"，
 *       加网关路由时会下意识避开它</li>
 * </ol>
 *
 * <p>这里<b>只剩参数格式校验</b>：查重、查不到怎么办，全在 {@code UserService} 里。
 * 少了这几行，将来从别的入口（MQ、定时任务）创建用户时规则也不会漏。
 */
@RestController
@RequestMapping("/internal/user")
@RequiredArgsConstructor
public class InternalUserController {

    private final UserService userService;

    /**
     * 创建用户。auth 校验完验证码后调这里落库。
     *
     * <p><b>只查手机号，不查用户名</b>（用户名允许重复，它只是显示名）。
     * 并发下的真正兜底是数据库唯一索引 {@code uk_user_phone}
     * （实现里捕获 DuplicateKeyException 翻译成 409）。
     *
     * @return 新建用户信息（只含 id + 用户名）
     */
    @PostMapping("/create")
    public Result<UserDTO> create(@RequestBody UserCreateRequest req) {
        requireText(req.username(), "用户名不能为空");
        requireText(req.phone(), "手机号不能为空");
        requireText(req.password(), "密码不能为空");

        return Result.success(userService.createUser(req));
    }

    /**
     * 校验登录密码。auth 解出明文密码后调这里比对，<b>成功则返回 userId</b>。
     *
     * <p><b>密码哈希不会返回给 auth</b> —— 它只在这里和明文比对。
     *
     * <p><b>为什么只返回 userId？</b> auth 拿到它只为签 token。
     * 用户资料缓存由本服务在 {@code verifyPassword} 里顺手预热
     * （登录后前端马上要调 {@code GET /user/me}，那里正是按 id 查资料），
     * 所以不需要把资料传到 auth 再让它写一遍 —— 少一次网络往返，
     * 缓存格式（字段名 / TTL / CAS）也不出本服务。
     *
     * @return 成功返回 userId；失败返回 401（<b>不区分</b>手机号不存在还是密码错误）
     */
    @PostMapping("/verify")
    public Result<Long> verify(@RequestBody UserVerifyRequest req) {
        if (isBlank(req.phone()) || isBlank(req.password())) {
            throw new BizException("手机号或密码不能为空");
        }

        return Result.success(userService.verifyPassword(req.phone(), req.password()));
    }

    /**
     * 按 id 查用户资料，<b>给其他服务通过 Feign 调用</b>（比如 chat-message 要显示发送者昵称）。
     *
     * <p><b>为什么服务间调用走 /internal/** 而不是对外的 /user/{id}？</b>
     * <ul>
     *   <li>语义清楚：这是"内部接口"，将来给 /user/{id} 加权限（比如限流、登录校验）
     *       不会连带把内部调用一起卡死</li>
     *   <li>对外接口的契约是给前端设计的，内部调用需要什么字段是另一回事，两者耦合会互相牵制</li>
     * </ul>
     *
     * <p>走的是 {@code getUserById}，所以<b>会用 Redis 缓存</b> —— 服务间调用也享受缓存。
     *
     * @return 用户资料；用户不存在抛 404
     */
    @GetMapping("/{id}")
    public Result<UserProfileDTO> getById(@PathVariable("id") Long id) {
        return Result.success(userService.getUserById(id));
    }

    /**
     * 按 id 列表<b>批量</b>查用户资料，给 chat-relation 拼好友列表用。
     *
     * <p><b>为什么要有批量接口？</b> 好友列表里只有 userId，要显示昵称头像就得问本服务。
     * 一个个问的话，20 个好友 = 20 次网络往返（经典的 N+1），慢且浪费。
     * 一次把 id 列表发过来、一次返回，是这里唯一的正确做法。
     *
     * <p>走的是 {@code selectBatchIds}（一条 {@code where user_id in (...)}），
     * <b>不经过 Redis 缓存</b>：批量场景一次 IN 查询比 N 次 Redis 往返更快，
     * 而缓存本来是为"单个人被反复读"设计的。
     *
     * <p>注意返回的是<b>内部 DTO</b>（带 {@code updatedAt}），和对外的 VO 不一样 ——
     * 这是故意的，服务之间不需要"给前端看的展示形状"。
     *
     * @param req 要查的 userId 列表；空列表返回空数组，不报错
     * @return 找到了的用户资料；<b>没找到的 id 不会出现在结果里</b>（调用方自己判断缺了谁）
     */
    @PostMapping("/batch")
    public Result<List<UserProfileDTO>> batch(@RequestBody UserBatchRequest req) {
        return Result.success(userService.getUserByIds(req == null ? null : req.userIds()));
    }

    /** 参数格式校验的小工具，只为省掉重复的 if */
    private static void requireText(String value, String message) {
        if (isBlank(value)) {
            throw new BizException(message);
        }
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}
