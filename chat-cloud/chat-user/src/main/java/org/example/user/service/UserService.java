package org.example.user.service;

import java.util.List;

import org.example.common.dto.UserCreateRequest;
import org.example.common.dto.UserDTO;
import org.example.common.dto.UserProfileDTO;
import org.example.user.dto.UpdateProfileRequest;

/**
 * 用户业务接口。
 *
 * <p>两条铁律：
 * <ol>
 *   <li>controller 只依赖这个接口，不依赖 {@code UserServiceImpl} ——
 *       以后想换实现（加缓存、换存储），controller 一行都不用改。</li>
 *   <li><b>返回值只能是 DTO，不能是 Entity</b> ——
 *       Entity 里带着 password 哈希，一旦出现在返回值里，
 *       就随时可能被人顺手塞进响应体泄露出去。这里直接把它挡在编译期。</li>
 * </ol>
 *
 * <p>业务失败一律 **抛 {@code BizException}**（不是返回 null / false），
 * 这样调用方忘记处理异常时会立刻暴露，而不是默默把 null 传下去。
 * 异常由 {@code GlobalExceptionHandler} 统一翻译成 {@code Result}。
 *
 * <p><b>第三个规矩：这一层返回的是内部资料 {@link UserProfileDTO}（带 {@code updatedAt}
 * 缓存版本号），不是给前端看的形状。</b> 对外的形状是 {@code vo} 包里的
 * {@code UserCardVO} / {@code UserDetailVO}，由 controller 在出口处转一层。
 * 这么分是因为 {@code updatedAt} 不能丢 —— 写缓存时要用它做 CAS 比较；
 * 但它也不该出现在响应里。
 */
public interface UserService {

    /** 手机号是否已注册 */
    boolean isPhoneRegistered(String phone);

    /**
     * 创建用户。密码在实现里做 BCrypt 加密。
     *
     * <p><b>只检查手机号是否重复</b>（手机号是登录凭证，必须唯一）。
     * <b>用户名不查重</b> —— 它只是显示名，允许重复；登录靠手机号，不靠用户名。
     *
     * @return 落库后的用户信息（只含 id + 用户名）
     * @throws org.example.common.exception.BizException 409 —— 手机号已注册
     */
    UserDTO createUser(UserCreateRequest req);

    /**
     * 校验登录密码，通过后<b>预热用户资料缓存</b>，返回 userId。
     *
     * <p><b>密码哈希不出这个服务</b>：调用方传明文进来，这里用 BCrypt.matches 比对，
     * 对外的返回值里连哈希、连手机号都没有。
     *
     * <p><b>为什么返回 userId 就够了？</b> 登录接口（chat-auth）拿到它只为签 token。
     * 至于"用户资料" —— 那是本服务自己的缓存要用的东西，在这里查库时顺手写掉就行
     * （见 {@code UserServiceImpl#verifyPassword}），不必随着响应传到别的服务去。
     * 这既少一次网络往返，也让缓存格式不外泄。
     *
     * @param phone       手机号
     * @param rawPassword 明文密码
     * @return 校验通过的用户 id
     * @throws org.example.common.exception.BizException 401 —— 手机号不存在或密码错误
     *         （故意不区分两者，避免被人拿来枚举手机号）
     */
    Long verifyPassword(String phone, String rawPassword);

    /**
     * 按手机号<b>精确</b>查用户资料（"搜人"用：加好友前先找到这个人）。
     *
     * <p><b>为什么用手机号而不是用户名？</b> 用户名允许重复（它只是显示名），
     * 搜出来可能是三个"张三"，没法唯一定位一个人。手机号在库里是唯一的，能精确到一个人。
     *
     * <p><b>为什么必须是"精确匹配"？</b> 这个接口等于"某个手机号注册过吗 + 他叫什么"，
     * 一旦支持模糊查询（{@code like '138%'}），整张用户表就能被批量拖走。
     *
     * <p>⚠️ 注意：这个接口有<b>手机号枚举风险</b>（拿手机号列表批量试，
     * 就能摸出谁在本平台注册了）。所以外部调用方必须加限流，见 UserController 的说明。
     *
     * @param phone 完整手机号（11 位）
     * @return 用户资料（id / 用户名 / 昵称 / 性别 / 生日 / 头像，<b>不含手机号本身</b>）
     * @throws org.example.common.exception.BizException 404 —— 该手机号未注册
     */
    UserProfileDTO getByPhone(String phone);

    /**
     * 按 userId 查用户资料。<b>先查 Redis 缓存，没有再查数据库。</b>
     *
     * <p>和 {@link #getByPhone} 的区别在于<b>走不走缓存</b>：
     * <ul>
     *   <li>{@code getByPhone} —— 按手机号找人，<b>不走缓存</b>。
     *       因为缓存的 key 是 {@code user:info:{userId}}，按手机号查时手里还没有 userId，
     *       缓存根本命中不了。</li>
     *   <li>{@code getUserById} —— 按 id 查人，<b>走缓存</b>。
     *       这正是缓存的用武之地：看主页、好友列表、消息列表都是"按 id 查人"。</li>
     * </ul>
     *
     * <p><b>缓存命中时会顺手续期</b>（滑动过期），让一直被看的用户不必反复查库。
     *
     * <p><b>缓存未命中时会查库并回填</b> —— 所以缓存过期后能自己长回来，
     * 不会出现"过期一次就永久失效"的情况。
     *
     * @param userId 用户 id
     * @return 用户资料（id / 用户名 / 昵称 / 性别 / 生日 / 头像）
     * @throws org.example.common.exception.BizException 404 —— 用户不存在
     */
    UserProfileDTO getUserById(Long userId);

    /**
     * 按 id 列表<b>批量</b>查资料（给 chat-relation 拼好友列表用）。
     *
     * <p>存在的理由只有一个：<b>避免 N+1</b>。好友列表里只有 userId，
     * 一个个调 {@link #getUserById} 就是 20 次网络往返；这里一次 IN 查询解决。
     *
     * <p>和 {@code getUserById} 的另一个区别：<b>不走 Redis 缓存</b>。
     * 批量场景一次 IN 查询比 N 次 Redis 往返更快，而缓存是为"单个人被反复读"设计的。
     *
     * @param userIds 要查的 id 列表；可以为 null / 空（返回空列表，不报错）
     * @return 查到的资料；<b>没查到的 id 不会出现在结果里</b>（调用方自己判断缺了谁）
     */
    List<UserProfileDTO> getUserByIds(List<Long> userIds);

    /**
     * 修改当前登录用户的资料（昵称 / 性别 / 生日 / 头像）。
     *
     * <p><b>部分更新语义</b>：只改传了的字段，没传的保持原样。
     *
     * <p><b>空字符串当"不改"处理</b>，所以这个方法<b>不能用来清空字段</b> ——
     * 想把头像删掉得另外设计（专门的"清空"接口，或约定一个特殊值）。
     *
     * <p>内部会依次做三件事，顺序不能乱：
     * <ol>
     *   <li>更新数据库（只 set 传了的字段）</li>
     *   <li>重新查出最新资料（顺便拿到新的 {@code updated_at}，它就是缓存版本号）</li>
     *   <li><b>把新资料 CAS 写入 Redis 缓存</b></li>
     * </ol>
     *
     * <p><b>为什么是"写新值"而不是"删缓存"？</b> 删掉的话，并发读可能把旧值又回填回来
     * （读-回填竞态）；写新值的版本一定比缓存里的新，CAS 会稳压旧值一头。
     *
     * <p><b>"改的是谁"由网关决定</b>：userId 来自网关注入的 {@code X-User-Id}，
     * 绝不从请求体里取（否则可以改别人的资料）。
     *
     * @param userId 要修改的用户 id。<b>必须由网关从 token 解析后传入</b>，不接收前端指定
     * @param req    新资料（字段可选，只传要改的）
     * @return 更新后的用户资料（数据库里的最新状态）
     * @throws org.example.common.exception.BizException
     *         400 —— 一个字段都没传；404 —— 用户不存在
     */
    UserProfileDTO updateProfile(Long userId, UpdateProfileRequest req);
}
