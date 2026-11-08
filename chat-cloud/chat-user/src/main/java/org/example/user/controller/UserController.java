package org.example.user.controller;

import org.example.common.constant.AuthHeaders;
import org.example.common.dto.UserProfileDTO;
import org.example.common.exception.BizException;
import org.example.common.result.Result;
import org.example.common.support.RequestUser;
import org.example.user.dto.UpdateProfileRequest;
import org.example.user.service.AvatarStorageService;
import org.example.user.service.UserService;
import org.example.user.vo.UserCardVO;
import org.example.user.vo.UserDetailVO;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;

/**
 * 用户对外接口（经网关 /api/user/** 暴露）。
 *
 * <p>注意这里只负责「查」和「校验」；「创建用户」是内部接口，在 InternalUserController 里。
 * 注入的是 <b>{@link UserService} 接口</b>，不是实现类 —— 这才是面向接口编程的意义。
 *
 * <p><b>controller 的分工只有两件事</b>：
 * <ol>
 *   <li>校验<b>参数格式</b>（空串、null 这种"请求本身就不合法"）</li>
 *   <li>把 service 的返回值包成 {@link Result}</li>
 * </ol>
 * 业务规则（用户不存在怎么办、查重、错误码）全在 service 里，
 * 所以这里看不到 {@code if (user == null)} 这种判断。
 *
 * <p><b>返回给前端的形状有两种</b>（都在 {@code vo} 包里）：
 * <ul>
 *   <li>{@link UserCardVO} —— "列表 / 搜索"要的最小信息（5 个字段，没有 username）</li>
 *   <li>{@link UserDetailVO} —— "点进去看某个人"的完整资料（多一个 username）</li>
 * </ul>
 * 两者都<b>不含 {@code updatedAt}</b>：那是缓存的版本号（CAS 用它比新旧），
 * 属于内部实现，没必要给前端。
 * service 层依然返回内部的 {@code UserProfileDTO}（它带着版本号，写缓存要用），
 * <b>转换只发生在 controller 这一层</b> —— 出口收口在一处，就不会漏。
 */
@RestController
@RequestMapping("/user")
@RequiredArgsConstructor
public class UserController {

    private final UserService userService;

    /**
     * 头像文件的落盘/删除。
     *
     * <p>为什么由 controller 来调，而不是让 {@code UserService} 直接依赖它：
     * 存储服务的入参是 {@code MultipartFile} —— 那是 <b>web 层</b>的类型。
     * service 只认"一个 URL 字符串"（见 {@code updateProfile} 的入参），
     * 把 web 类型挡在 controller 里，service 才能被别的调用方（内部接口、定时任务）复用而不别扭。
     */
    private final AvatarStorageService avatarStorageService;

    /**
     * 手机号是否可用。前端在手机号失焦、或点"获取验证码"之前调用。
     *
     * <p>用户名不提供查重接口 —— 因为用户名允许重复，它只是个显示名。
     *
     * @return true = 未注册（可用），false = 已注册
     */
    @GetMapping("/check-phone")
    public Result<Boolean> checkPhone(@RequestParam("phone") String phone) {
        // 参数格式校验留在 controller：这是"请求不合法"，不是业务规则。
        // 用抛 BizException 而不是 return Result.error ——
        // 这样整个 controller 只有一条错误路径（统一由 GlobalExceptionHandler 转成 Result），
        // 不会出现"有的地方 return 错误、有的地方 throw"的两种风格。
        if (phone == null || phone.isBlank()) {
            throw new BizException(400, "手机号不能为空");
        }
        return Result.success(!userService.isPhoneRegistered(phone));
    }

    /**
     * 【搜人】按手机号精确查用户资料。加好友前先调这个，把手机号"翻译"成 userId。
     *
     * <p><b>为什么需要这个接口？</b> 用户记不住（也不该记）userId，他们只会输手机号。
     * 所以在"添加好友"这条链路上，手机号只是<b>搜索词</b>，userId 才是<b>操作标识</b>：
     * <pre>
     *   GET  /api/user/search?phone=138...   → { id: 2, username: "张三", ... }
     *   POST /api/relation/friend/request    { toUserId: 2 }   ← 后续都用 userId
     * </pre>
     *
     * <p>用手机号而不是用户名搜：用户名允许重复（只是显示名），搜出来可能是三个"张三"，
     * 定位不到具体的人。
     *
     * <p><b>⚠️ 安全：这个接口等于"手机号枚举器"</b> ——
     * 拿手机号列表批量问，就能摸出"谁在本平台注册了、叫什么"。
     * 目前已有的防护是"必须登录"（此路径不在网关白名单里）。
     * <b>还缺限流</b>，建议在网关加一个"每用户/IP 每分钟 N 次"的限制，
     * 否则它比破解密码更容易得手。同时这里坚持<b>精确匹配</b>、绝不做模糊查询。
     */
    @GetMapping("/search")
    public Result<UserCardVO> searchByPhone(@RequestParam("phone") String phone) {
        // 参数格式校验留在 controller：这是"请求不合法"，不是业务规则。
        // 校验格式还有个实际好处：用户少打一位时会收到"格式不正确"，
        // 而不是一个会误导人的"该手机号未注册"。
        if (phone == null || !phone.trim().matches("^1[3-9]\\d{9}$")) {
            throw new BizException(400, "手机号格式不正确");
        }
        return Result.success(UserCardVO.from(userService.getByPhone(phone.trim())));
    }

    /**
     * 【按 id 查人】查某个用户的资料。<b>走 Redis 缓存</b>。
     *
     * <p>这个是"看别人主页 / 好友列表 / 消息列表"要用的接口 —— 它们手里都只有 userId。
     * 和 {@code /search} 的区别：{@code /search} 是"用手机号把 userId 查出来"，
     * 这个接口是"已经知道 userId，查他的资料"。
     *
     * <p><b>缓存在哪来？</b> 有两个来源（都在本服务内部）：
     * <ol>
     *   <li>对方<b>登录时</b>由 {@code verifyPassword} 预热写入</li>
     *   <li>缓存没命中、查完数据库之后，这里会<b>回填</b></li>
     * </ol>
     * 所以缓存在 → 直接返回并顺手续期；缓存不在 → 查数据库 + 回填，
     * 下一次读就能命中了（不会一直查库）。
     *
     * <p>注意路径 {@code /{id}} 和上面的 {@code /search} 不冲突：
     * Spring 的路径匹配里"字面量段"优先级高于"变量段"，
     * 所以 {@code /user/search} 走上面那个方法，{@code /user/123} 才走这里。
     */
    @GetMapping("/{id}")
    public Result<UserDetailVO> getById(@PathVariable("id") Long id) {
        return Result.success(UserDetailVO.from(userService.getUserById(id)));
    }

    /**
     * 【看自己】查当前登录用户自己的资料。
     *
     * <p><b>它和 {@code GET /user/{id}} 走的是同一个 service 方法</b>
     * （{@code userService.getUserById}），区别只在「userId 从哪来」：
     * <ul>
     *   <li>{@code /user/{id}} —— 路径参数，<b>由前端指定</b>，用来看<b>别人</b></li>
     *   <li>{@code /user/me} —— 网关注入的 {@code X-User-Id}，用来看<b>自己</b>，伪造不了</li>
     * </ul>
     * 所以 service 层<b>一行都不用改</b>，这里只是个薄薄的适配器。
     *
     * <p><b>为什么必须有这个接口？</b> 因为前端<b>不知道自己的 userId</b>
     * （登录只返回 token，不返回 userId），它没法调 {@code /user/{id}}。
     * 有了这个接口，前端登录后调一次就知道"我是谁"，可以显示自己的头像昵称、
     * 做"我的主页"，而且拿到的永远是数据库里最新的那份资料。
     *
     * <p>路径 {@code /me} 和 {@code /{id}} 不冲突：Spring 里字面量段优先于变量段，
     * 所以 {@code /user/me} 走这里，{@code /user/123} 走上面那个。
     */
    @GetMapping("/me")
    public Result<UserDetailVO> me(
            @RequestHeader(value = AuthHeaders.USER_ID, required = false) Long userId) {
        return Result.success(UserDetailVO.from(userService.getUserById(RequestUser.requireId(userId))));
    }

    /**
     * 【改资料】修改当前登录用户的昵称 / 性别 / 生日 / 头像。
     *
     * <p><b>「改的是谁」由网关决定，不由前端决定。</b>
     * userId 从 {@code X-User-Id} 请求头取 —— 那是网关验完 token 后注入的。
     * 前端请求体里<b>没有</b> userId 字段，这是故意的：
     * 如果让前端自己传 userId，谁都能改别人的资料。
     *
     * <p><b>只传要改的字段</b>（部分更新）：不传的字段保持原样。
     * 空字符串也当"不改"，所以这个接口<b>不能用来清空字段</b>。
     *
     * <p><b>返回更新后的完整资料</b>，前端可以直接拿来刷新界面，
     * 不用再调一次 {@code GET /user/me}（缓存刚被删掉，那一次会白查一遍库）。
     *
     * <h3>为什么是 multipart（{@code @RequestPart}）而不是 JSON</h3>
     * 因为要能<b>直接传一张图片</b>。JSON 装不了二进制；base64 塞进 JSON 会让请求体膨胀 33%，
     * 而且要在内存里多留一份字符串副本。multipart 是两个独立的"部分"：
     * <pre>
     *   profile : application/json  →  { "nickName": "...", "gender": "M", ... }
     *   avatar  : image/png         →  图片字节本身
     * </pre>
     * 两个部分都<b>可选</b>：只改昵称就只传 profile；只换头像就只传 avatar。
     *
     * <p>⚠️ 前端的坑：用 {@code FormData} 时<b>不能手动设 Content-Type</b> ——
     * multipart 的 {@code boundary} 是浏览器自动生成并写在 Content-Type 里的，
     * 手写一个 {@code multipart/form-data} 反而会让服务端解析不出边界（见 api/index.js）。
     */
    @PutMapping(value = "/profile", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public Result<UserDetailVO> updateProfile(
            @RequestHeader(value = AuthHeaders.USER_ID, required = false) Long userId,
            @RequestPart(value = "profile", required = false) @Valid UpdateProfileRequest req,
            @RequestPart(value = "avatar", required = false) MultipartFile avatar) {

        Long me = RequestUser.requireId(userId);

        // ① 先把图存下来。顺序是【先存图、再更新库】：
        //    万一中间失败，最坏是磁盘上多一个没人引用的文件（脏文件，可以清理）；
        //    反过来先更新库再存图，失败时数据库里会留下一个指向不存在文件的 URL ——
        //    那是用户直接看得见的坏头像，而且不会自愈。
        String newAvatarUrl = avatarStorageService.store(avatar);

        // ② 取旧头像用于换图后的清理（只有真传了新图才需要查这一次）
        String oldAvatarUrl = null;
        if (newAvatarUrl != null) {
            UserProfileDTO before = userService.getUserById(me);
            oldAvatarUrl = before == null ? null : before.avatarUrl();
        }

        // ③ 把新头像的 URL 合成到请求里交给 service：
        //    记录是不可变的，所以这里造一个新的；avatarUrl 以文件为准（传了图就忽略请求里那个字段）
        UpdateProfileRequest merged = mergeAvatarUrl(req, newAvatarUrl);

        UserProfileDTO updated = userService.updateProfile(me, merged);

        // ④ 数据库已经指向新图了，旧文件才能删 —— 顺序反了会出现"库里还指着旧图、文件已经没了"
        if (newAvatarUrl != null) {
            avatarStorageService.deleteIfOurs(oldAvatarUrl);
        }

        return Result.success(UserDetailVO.from(updated));
    }

    /**
     * 把"上传得到的头像 URL"合进请求体。
     *
     * <p>三种情况：
     * <ul>
     *   <li>传了图 → 用图的 URL（请求里那个 avatarUrl 字段被忽略：以实际上传的文件为准）；</li>
     *   <li>没传图、只传了别的字段 → 原样返回（可能为 null）；</li>
     *   <li>什么都没传 → 返回 null，下一行的 service 会报 400「没有需要更新的字段」。</li>
     * </ul>
     */
    private static UpdateProfileRequest mergeAvatarUrl(UpdateProfileRequest req, String newAvatarUrl) {
        if (newAvatarUrl == null) {
            return req;
        }
        return new UpdateProfileRequest(
                req == null ? null : req.nickName(),
                req == null ? null : req.gender(),
                req == null ? null : req.birthDate(),
                newAvatarUrl);
    }

}
