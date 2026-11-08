package org.example.user.service.impl;

import java.util.List;
import java.util.Objects;

import org.example.common.dto.UserCreateRequest;
import org.example.common.dto.UserDTO;
import org.example.common.dto.UserProfileDTO;
import org.example.common.exception.BizException;
import org.example.common.support.Strings;
import org.example.user.dto.UpdateProfileRequest;
import org.example.user.entity.UserEntity;
import org.example.user.mapper.UserMapper;
import org.example.user.service.UserCacheService;
import org.example.user.service.UserService;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * 用户业务逻辑实现。controller 只做参数校验和转发，数据库操作和业务规则全在这里。
 *
 * <p>本类对外<b>只吐 DTO</b>，{@link UserEntity} 是私有实现细节。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class UserServiceImpl implements UserService {

    private final UserMapper userMapper;
    private final PasswordEncoder passwordEncoder;
    private final UserCacheService userCacheService;

    @Override
    public boolean isPhoneRegistered(String phone) {
        return userMapper.selectCount(
                Wrappers.<UserEntity>lambdaQuery().eq(UserEntity::getPhone, phone)) > 0;
    }

    /**
     * 创建用户。
     *
     * <p><b>关键：密码只以 BCrypt 哈希形式落库，明文绝不保存。</b>
     * BCrypt 自带随机盐（每个用户盐都不同）+ 慢哈希（故意算得慢，抗暴力破解）。
     *
     * <p><b>只查手机号，不查用户名</b>：手机号是登录凭证（唯一），
     * 用户名只是显示名（允许重复）。这条规则要和数据库结构一致 ——
     * <b>所以 {@code user_name} 上【不能】有唯一索引</b>，否则这里不查、数据库却会拦，
     * 用户会收到一个莫名其妙的 409。
     *
     * <p>没设置 createdAt / updatedAt —— 数据库有 DEFAULT CURRENT_TIMESTAMP 会自动填。
     */
    @Override
    public UserDTO createUser(UserCreateRequest req) {
        if (isPhoneRegistered(req.phone())) {
            throw new BizException(409, "该手机号已注册");
        }

        UserEntity entity = new UserEntity();
        entity.setUserName(req.username());
        entity.setPhone(req.phone());
        // 数据库列名是 password，但写进去的是 BCrypt 哈希，不是明文
        entity.setPassword(passwordEncoder.encode(req.password()));
        entity.setNickName(req.username()); // 默认昵称 = 用户名
        // avatarUrl 不设置，留 null

        try {
            userMapper.insert(entity);
        } catch (DuplicateKeyException e) {
            // 并发兜底：两个请求同时通过了上面的查重，数据库唯一索引会拦下第二个。
            // 现在唯一的业务唯一索引是 uk_user_phone，所以冲突只可能是手机号。
            // 把数据库的报错翻译成人话，别让前端看到 "Duplicate entry ... for key ..."。
            throw new BizException(409, "该手机号已注册");
        }
        return toDTO(entity);
    }

    /**
     * 校验登录密码，通过后<b>预热用户资料缓存</b>，返回 userId。
     *
     * <p>注意 <b>BCrypt 不能用 equals 比字符串</b>：
     * 每次加密都会生成随机盐，同一个密码两次加密结果完全不同。
     * 必须用 {@code matches()} —— 它会把哈希里存的盐取出来，重新算一遍再比对。
     *
     * <p><b>为什么会在这里写 Redis？</b> 登录成功后前端马上要调 {@code GET /user/me}
     * 拿自己的资料，而那个接口正是"按 id 查资料"，会先查这份缓存。
     * 在登录这一步顺手写一次，用户第一次读就能命中。
     * 而这次查库<b>本来就要做</b>（要拿 password 哈希比对），资料是顺带的，不额外花代价。
     *
     * <p><b>为什么不让 chat-auth 来写？</b> 那样它得为此多调一次接口，
     * 还得复制一份"缓存长什么样"的知识（字段名、TTL、CAS 脚本）到自己的服务里 ——
     * 缓存的实现细节就漏出 chat-user 了。现在它只从返回值里拿一个 userId 去签 token。
     *
     * <p>{@link UserCacheService#put} 内部吞掉所有异常（只打 warn），
     * 所以<b>缓存写不进去绝不会导致登录失败</b>；它还是 CAS 写入，
     * 缓存里已有更新的版本时会自动放弃。
     *
     * @return 校验通过返回该用户的 userId（调用方拿它签发 token）
     * @throws org.example.common.exception.BizException 401 —— 手机号不存在或密码错误
     *         （故意不区分两者，避免被人拿来枚举手机号）
     */
    @Override
    public Long verifyPassword(String phone, String rawPassword) {
        UserEntity user = userMapper.selectOne(
                Wrappers.<UserEntity>lambdaQuery().eq(UserEntity::getPhone, phone));

        // 手机号不存在 / 密码错误：对外统一报同一个错，避免被人拿来枚举手机号
        if (user == null || !passwordEncoder.matches(rawPassword, user.getPassword())) {
            throw new BizException(401, "手机号或密码错误");
        }

        // 密码对了才预热：失败路径绝不能写（否则等于"密码错了也能刷缓存"）
        UserProfileDTO profile = toProfileDTO(user);
        userCacheService.put(profile);
        return profile.userId();
    }



    /**
     * 按手机号精确查用户资料。
     *
     * <p>用 {@code selectOne} 按 phone 查。这里有个<b>隐含前提</b>：
     * {@code phone} 列上有唯一索引（{@code uk_user_phone}）。
     * 如果没有唯一索引而库里又碰巧有两条同号记录，
     * {@code selectOne} 会抛 {@code TooManyResultsException} —— 所以这个索引不只是性能问题，
     * 也是<b>正确性</b>问题。
     */
    @Override
    public UserProfileDTO getByPhone(String phone) {
        UserEntity user = userMapper.selectOne(
                Wrappers.<UserEntity>lambdaQuery().eq(UserEntity::getPhone, phone));

        if (user == null) {
            // 对"搜人"来说，查不到是正常结果；但给前端一个明确的 404 文案更好用，
            // 界面可以直接显示"该手机号未注册"，不用自己判断 data 是不是 null
            throw new BizException(404, "该手机号未注册");
        }
        return toProfileDTO(user);
    }

    /**
     * 实体 → 实体最小身份 DTO 的转换（{@code UserDTO}：只有 id 和用户名）。
     * 故意丢掉 password / phone / avatarUrl 等，保证敏感字段不外泄。
     */
    private UserDTO toDTO(UserEntity entity) {
        // 故意只取 id 和用户名，password / phone / avatarUrl 全部丢掉
        return new UserDTO(entity.getUserId(), entity.getUserName());
    }

    /**
     * 按 userId 查用户资料：<b>先查缓存，没有再查库</b>。
     *
     * <p>命中缓存 → 直接返回（并顺手续期）。
     * 未命中 → 查数据库 → <b>回填缓存</b> → 返回。
     *
     * <p><b>回填这一步很重要</b>：没有它的话，缓存一旦过期（比如低峰期没人读，
     * 或登录已经超过 2 小时）就永远空着，之后每一次读都要打数据库，
     * 直到那个用户重新登录。有了回填，过期后第一次读就把缓存补回来了。
     */
    @Override
    public UserProfileDTO getUserById(Long userId) {
        // ① 先查缓存。它内部把任何异常都当"未命中"，返回 null，所以这里不用 try/catch
        UserProfileDTO cached = userCacheService.get(userId);
        if (cached != null) {
            return cached;
        }

        // ② 缓存没有 → 查数据库
        UserEntity user = userMapper.selectById(userId);
        if (user == null) {
            throw new BizException(404, "用户不存在: " + userId);
        }

        // ③ 回填缓存，让下次读能命中。
        //    put() 内部自己吞异常，所以这一步失败不影响本次返回。
        UserProfileDTO profile = toProfileDTO(user);
        userCacheService.put(profile);
        return profile;
    }

    /**
     * 批量查资料。
     *
     * <p>{@code selectBatchIds} 就是 {@code select * from user where user_id in (...)}——
     * <b>一条 SQL</b>，不是 N 条。
     *
     * <p>先做三件事再查：<b>去掉 null、去重、空就直接返回</b>。
     * 去重是因为调用方（好友列表）可能传重复 id，重复查出来也没用；
     * 空集合直接返回可以省掉一条 {@code in ()} 的非法 SQL。
     *
     * <p>故意<b>不查缓存</b>：批量场景一次 IN 查询比 N 次 Redis 往返更快。
     * 缓存留给 {@link #getUserById}（单个人被反复读）用。
     */
    @Override
    public List<UserProfileDTO> getUserByIds(List<Long> userIds) {
        if (userIds == null || userIds.isEmpty()) {
            return List.of();
        }
        List<Long> ids = userIds.stream().filter(Objects::nonNull).distinct().toList();
        if (ids.isEmpty()) {
            return List.of();
        }
        return userMapper.selectBatchIds(ids).stream().map(this::toProfileDTO).toList();
    }

    /**
     * 实体 → 用户资料 DTO 的转换（给"展示一个人"和"缓存资料"的场景用）。
     *
     * <p>和 {@link #toDTO} 的差别：这里<b>保留</b>了 nickName / gender / birthDate / avatarUrl，
     * 因为界面要显示"这是谁"，auth 也要把它们缓存进 Redis。
     * 这些都是展示信息，不是敏感字段。
     *
     * <p>但 <b>phone 和 password 依然丢掉</b>：
     * <ul>
     *   <li>phone —— 搜人的人本来就知道自己输的手机号，没理由再传回去；
     *       少传一个敏感字段，就少一个泄露面。</li>
     *   <li>password —— 底线，任何 DTO 都不带。</li>
     * </ul>
     */
    private UserProfileDTO toProfileDTO(UserEntity entity) {
        return new UserProfileDTO(
                entity.getUserId(),
                entity.getUserName(),
                entity.getNickName(),
                entity.getGender(),
                entity.getBirthDate(),
                entity.getAvatarUrl(),
                // updatedAt 是【缓存版本号】：跟着数据一路传到缓存，CAS 写入时用它比新旧。
                // 它是 NOT NULL，所以从库里查出来一定有值。
                entity.getUpdatedAt());
    }

    /**
     * 修改用户资料。
     *
     * <p><b>① 部分更新怎么实现的？</b>
     * 只把要改的字段 set 到实体上，其它留 null。MyBatis-Plus 的 {@code updateById}
     * 默认<b>跳过 null 字段</b>（{@code FieldStrategy.NOT_NULL}），
     * 所以生成的 SQL 只会包含要改的那几列 —— 这正是我们想要的效果，
     * 顺便还保证了 <b>userName / phone / password 绝不会被误改</b>（它们根本没 set）。
     *
     * <p><b>② 缓存怎么处理？</b>
     * 更新数据库之后，<b>把最新数据写进缓存</b>（而不是删掉它），走的是 CAS（比较版本号）：
     * <ul>
     *   <li>写而不是删：让下一个读的人直接命中，省一次查库</li>
     *   <li>用 CAS 写：如果缓存里已经有更新的版本（别人也刚改过），这次写会被拒绝，
     *       所以<b>不会出现"旧值覆盖新值"</b> —— 这正是"写新值"方案唯一需要 CAS 兜底的地方</li>
     *   <li>顺序仍然是"先落库、再写缓存"：反过来的话中间那段时间读请求会拿到旧值</li>
     * </ul>
     *
     * <p>注意：写完数据库后要<b>重新查一次</b>才能拿到新的版本号（{@code updated_at}
     * 是 MySQL 自己维护的，UPDATE 语句里并不写它）。
     *
     * <p><b>③ 为什么不加 {@code @Transactional}？</b>
     * 单条 UPDATE 本身就是原子的，不需要事务。而且不加事务反而让
     * "UPDATE 提交完" 和 "删缓存" 之间没有空隙，顺序更干净 ——
     * 如果加了事务，删缓存会在提交之前发生，那时别的读请求可能把旧值回填进缓存。
     */
    @Override
    public UserProfileDTO updateProfile(Long userId, UpdateProfileRequest req) {
        // ① 一个字段都没传就直接拒绝。
        //    不拒绝的话，MyBatis-Plus 会生成 "UPDATE `user` SET WHERE user_id=?" ——
        //    SET 子句是空的，SQL 语法错误。
        if (isNoChange(req)) {
            throw new BizException(400, "没有需要更新的字段");
        }

        // ② 先确认用户存在（不存在就 404，不去白更新一次）
        if (userMapper.selectById(userId) == null) {
            throw new BizException(404, "用户不存在: " + userId);
        }

        // ③ 只 set 允许修改的 4 个字段。
        //    空串按"不改"处理（trimToNull → null → MyBatis-Plus 跳过这一列）。
        //    user_name / phone / password 一律不碰，这是"只改该改的"的技术保障。
        UserEntity update = new UserEntity();
        update.setUserId(userId);
        update.setNickName(Strings.trimToNull(req.nickName()));
        update.setGender(Strings.trimToNull(req.gender()));
        update.setBirthDate(req.birthDate());
        update.setAvatarUrl(Strings.trimToNull(req.avatarUrl()));
        userMapper.updateById(update);

        // ④ 重新查一次，拿到最新数据【和新的版本号 updated_at】。
        //    updated_at 是 MySQL 用 ON UPDATE CURRENT_TIMESTAMP 自动维护的，
        //    UPDATE 语句里并没有写它，所以只能查出来才知道新值是多少。
        UserProfileDTO latest = toProfileDTO(userMapper.selectById(userId));

        // ⑤ 把最新数据写进缓存（CAS：如果缓存里已有更新的版本，这次写会被拒绝）
        userCacheService.put(latest);

        log.info("用户资料已更新: userId={}, 新版本={}", userId, latest.updatedAt());
        return latest;
    }

    /** 4 个字段都没传（或全是空串/全空白）→ 没什么可改的 */
    private static boolean isNoChange(UpdateProfileRequest req) {
        return Strings.trimToNull(req.nickName()) == null
                && Strings.trimToNull(req.gender()) == null
                && req.birthDate() == null
                && Strings.trimToNull(req.avatarUrl()) == null;
    }

    /*
     * 这里以前有一个私有方法 trimToNull(String)：空串 / 全空白 → null，
     * 用来实现"空字符串 = 不改"。chat-relation 的改备注那里有一份一模一样的，
     * 现在统一走 chat-common 的 Strings#trimToNull（两处语义必须永远一致，
     * 各写一份的话早晚会出现"昵称能清空、备注不能"这种怪现象）。
     */
}
