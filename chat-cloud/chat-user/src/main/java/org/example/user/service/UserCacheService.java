package org.example.user.service;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import org.example.common.constant.CacheKeys;
import org.example.common.dto.UserProfileDTO;
import org.example.user.cache.UserProfileCacheCodec;
import org.example.user.cache.UserProfileCacheScript;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.stereotype.Service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * 用户资料缓存（chat-user 侧）：负责<b>读</b>和<b>写</b>。
 *
 * <p><b>这份缓存只有 chat-user 一个读写方</b>，三个写入时机都在本服务内部：
 * <ol>
 *   <li>{@code verifyPassword} 登录校验通过时<b>预热</b> —— 让刚登录的人第一次被读就能命中</li>
 *   <li>{@link #put} 读未命中时<b>回填</b></li>
 *   <li>{@link #put} 修改资料之后的<b>写新值</b></li>
 * </ol>
 * 三处都走 {@link UserProfileCacheScript#CONDITIONAL_WRITE} 做 CAS 写入，
 * 编码统一由 {@link UserProfileCacheCodec} 负责，所以写进去的格式一致、新旧顺序也正确。
 *
 * <p><b>为什么写入要用 CAS 而不是直接写？</b> 防止这个竞态
 * （同一台服务里两个并发线程一样会发生）：
 * <pre>
 *   T1  读线程：缓存没命中 → 查库 → 拿到旧值（版本 T1）
 *   T2  写线程：更新库 → 新值（版本 T2）→ 写缓存
 *   T3  读线程：想把旧值写进去      ← 版本 T1 &lt; T2，CAS 会拒绝
 * </pre>
 * 没有 CAS 的话，T3 会把旧的覆盖上去，而且一直留着（因为滑动续期让它几乎不过期）。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class UserCacheService {

    /**
     * CAS 条件写入脚本，内容见同项目的 {@link UserProfileCacheScript}。
     *
     * <p>做成 static 字段（而不是每次 new）：脚本本身无状态，可以复用，
     * 而且 Spring Data Redis 会把脚本内容缓存起来（EVALSHA）避免每次重传。
     */
    private static final RedisScript<Long> CAS_WRITE =
            new DefaultRedisScript<>(UserProfileCacheScript.CONDITIONAL_WRITE, Long.class);

    /** 写入成功 */
    private static final long WRITE_OK = 1L;

    private final StringRedisTemplate redisTemplate;

    /**
     * 剩余 TTL 少于这个分钟数时，读命中就顺手续期。
     *
     * <p>阈值越大 = 续期越频繁 = 热门缓存越不容易过期，但写越多；
     * 越小 = 越省写，但缓存可能在两次读之间就过期。默认 30 分钟。
     */
    @Value("${chat.user-cache.refresh-threshold-minutes:30}")
    private long refreshThresholdMinutes;

    /**
     * 读用户资料缓存。
     *
     * <p><b>任何异常都当作"没命中"</b>，返回 null 让调用方去查数据库 ——
     * 缓存挂了不该让整个接口挂掉，退化成"每次都查库"也比报错好。
     *
     * @param userId 用户 id
     * @return 命中返回资料；未命中（或缓存异常、内容损坏）返回 null
     */
    public UserProfileDTO get(Long userId) {
        if (userId == null) {
            return null;
        }
        String key = CacheKeys.userInfo(userId);

        Map<Object, Object> entries;
        try {
            entries = redisTemplate.opsForHash().entries(key);
        } catch (Exception e) {
            log.warn("读用户资料缓存失败，按未命中处理: userId={}, 原因={}", userId, e.getMessage());
            return null;
        }

        if (entries == null || entries.isEmpty()) {
            return null;   // 未命中（key 不存在，或 HASH 是空的）
        }

        UserProfileDTO profile;
        try {
            profile = UserProfileCacheCodec.fromHashFields(entries);
        } catch (Exception e) {
            // 缓存内容坏了（比如手工改过、格式变了）：当未命中，别把脏数据返回出去
            log.warn("用户资料缓存内容无法解析，按未命中处理: userId={}, 原因={}", userId, e.getMessage());
            return null;
        }

        // 命中之后才续期（未命中的话 key 都不存在，没什么可续的）
        refreshIfExpiringSoon(key, userId);
        return profile;
    }

    /**
     * 写入用户资料缓存（<b>CAS 条件写入</b>）。
     *
     * <p>两种调用时机（另外还有登录预热，直接由 {@code verifyPassword} 调到 {@link #put}）：
     * <ul>
     *   <li>{@code getUserById} 读缓存未命中、查完库之后 → <b>回填</b></li>
     *   <li>{@code updateProfile} 更新完数据库之后 → <b>写新值</b></li>
     * </ul>
     *
     * <p><b>不会覆盖更新的数据</b>：脚本会比较版本号（{@code updatedAt}），
     * 缓存里的更新就放弃写入。所以并发下不会出现"旧值覆盖新值"。
     *
     * <p>写失败（包括被 CAS 拒绝）都不影响调用方 —— 数据该返回的已经返回了。
     *
     * @param profile 要写入的资料；为 null、没有 id、或没有版本号时跳过
     */
    public void put(UserProfileDTO profile) {
        if (profile == null || profile.userId() == null) {
            return;
        }
        if (profile.updatedAt() == null) {
            // 没有版本号就没法做 CAS。理论上不该发生（updated_at 是 NOT NULL），
            // 真出现了就跳过写入 —— 宁可缓存不填，也不要冒着写脏数据的风险
            log.warn("用户资料没有版本号(updatedAt)，跳过缓存写入: userId={}", profile.userId());
            return;
        }

        String key = CacheKeys.userInfo(profile.userId());

        // 参数顺序必须和 UserProfileCacheScript 里的约定一致：
        //   版本字段名、版本值、TTL、然后字段名和字段值成对
        List<String> args = new ArrayList<>();
        args.add(CacheKeys.UserInfoField.UPDATED_AT);
        args.add(profile.updatedAt().toString());
        args.add(String.valueOf(CacheKeys.USER_INFO_TTL.toSeconds()));
        UserProfileCacheCodec.toHashFields(profile).forEach((field, value) -> {
            args.add(field);
            args.add(value);
        });

        try {
            Long result = redisTemplate.execute(CAS_WRITE, List.of(key), args.toArray());
            if (result != null && result == WRITE_OK) {
                log.debug("已写入用户资料缓存: userId={}", profile.userId());
            } else {
                // 被拒绝 = 缓存里已经是更新的数据了，这是正常情况（说明别人刚改过）
                log.debug("用户资料缓存里已有更新的版本，放弃写入: userId={}", profile.userId());
            }
        } catch (Exception e) {
            log.warn("写入用户资料缓存失败（不影响本次业务）: userId={}, 原因={}",
                    profile.userId(), e.getMessage());
        }
    }

    /**
     * 删除某个用户的资料缓存。
     *
     * <p><b>现在没有地方调用它了</b> —— "修改资料"改成了"写新值（CAS）"而不是"删缓存"。
     * 保留这个方法是为了应付"必须彻底清掉"的场景（比如数据被手工改乱、要强制重来）。
     *
     * <p>注意：如果要用它，顺序仍然是"先更新数据库、再删缓存"。
     */
    public void evict(Long userId) {
        if (userId == null) {
            return;
        }
        try {
            redisTemplate.delete(CacheKeys.userInfo(userId));
            log.debug("已清除用户资料缓存: userId={}", userId);
        } catch (Exception e) {
            log.warn("清除用户资料缓存失败: userId={}, 原因={}", userId, e.getMessage());
        }
    }

    /**
     * 剩余 TTL 不足阈值时，把过期时间重置回 {@link CacheKeys#USER_INFO_TTL}。
     *
     * <p>续期失败不影响本次返回 —— 资料已经读到了，只是下次可能要多查一次库。
     */
    private void refreshIfExpiringSoon(String key, Long userId) {
        try {
            Long remainSeconds = redisTemplate.getExpire(key, TimeUnit.SECONDS);
            if (remainSeconds == null || remainSeconds < 0) {
                // -1 = 这个 key 没有设置过期时间；-2 = key 已不存在。
                // 两种都不是"即将过期"，不动它（尤其 -1，乱加 TTL 反而改变语义）。
                return;
            }
            if (remainSeconds < refreshThresholdMinutes * 60) {
                redisTemplate.expire(key, CacheKeys.USER_INFO_TTL);
                log.debug("用户资料缓存续期: userId={}, 续期前剩余={}秒", userId, remainSeconds);
            }
        } catch (Exception e) {
            log.warn("用户资料缓存续期失败（不影响本次读取）: userId={}, 原因={}", userId, e.getMessage());
        }
    }
}
