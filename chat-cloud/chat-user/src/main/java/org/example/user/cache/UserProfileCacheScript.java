package org.example.user.cache;

/**
 * 用户资料缓存的<b>条件写入 Lua 脚本</b>（CAS）。
 *
 * <p><b>解决什么问题？</b> "读线程拿着旧数据、在别人更新之后又把它写回缓存"的那个竞态：
 * <pre>
 *   T1  读线程 R：缓存没命中 → 查库 → 拿到旧值 V_old（版本 T1）
 *   T2  写线程 W：更新库 → V_new（版本 T2）→ 写缓存
 *   T3  读线程 R：想把 V_old 写进去        ← 如果没有 CAS，这里就脏了
 * </pre>
 * 有了 CAS，T3 会先比较版本：缓存里已经是 T2（比 T1 新）→ <b>拒绝写入</b>。
 *
 * <p>它同时解决了"写-写竞态"：两个写线程无论谁先落地，<b>版本大的赢</b>。
 *
 * <p><b>为什么放在 chat-user？</b> 因为它是用户资料缓存<b>唯一的写入方</b>：
 * 登录成功时的预热、读未命中时的回填、改资料之后的写新值，三处都在这个服务里。
 *
 * <p><b>只有一个写入方了，为什么还要 CAS？</b> 因为竞态不是"跨服务"才有的 ——
 * {@code getUserById} 的回填和 {@code updateProfile} 的写新值，
 * 在同一台服务里也是两个并发线程，上面那个"旧值覆盖新值"的时序照样成立。
 *
 * <p><b>为什么必须是 Lua？</b> "读版本 → 比较 → 写入"是三步。
 * 如果让客户端分三条命令做，第 1 步和第 3 步之间别的客户端可以插进来，
 * 判断就失效了（检查-使用分离）。Redis 的 Lua 脚本<b>原子执行</b>，不会被打断。
 *
 * <p><b>为什么比较的是字符串？</b> 版本号是 {@code updated_at} 的 ISO 格式
 * （{@code 2026-10-04T21:20:40.123456}）。ISO 8601 有个好性质：
 * <b>字典序 = 时间序</b>，所以 Lua 里直接比字符串就有正确的新旧关系，
 * 不需要在 Java 里转成时间戳（那会引入时区问题）。
 */
public final class UserProfileCacheScript {

    /**
     * 条件写入脚本。
     *
     * <pre>
     * KEYS[1] = user:info:{userId}
     * ARGV[1] = 版本字段名（由调用方从 CacheKeys 传进来，不在脚本里写死）
     * ARGV[2] = 我的版本值（updatedAt 的 ISO 字符串）
     * ARGV[3] = TTL（秒）
     * ARGV[4], ARGV[5], ... = 字段名、字段值（成对出现）
     *
     * 返回 1 = 写入成功；0 = 缓存里的版本更新，放弃写入
     * </pre>
     *
     * <p><b>注意版本字段名是当参数传进来的，不是写死在脚本里。</b>
     * 写死的话有个静默失败风险：哪天有人把 {@code CacheKeys.UserInfoField.UPDATED_AT}
     * 改成了别的名字，编码那边跟着改了、脚本还找旧名字 →
     * {@code HGET} 永远拿到 nil → <b>CAS 悄悄失效</b>（变成无条件写），而且不报任何错。
     */
    public static final String CONDITIONAL_WRITE = """
            local key = KEYS[1]
            local versionField = ARGV[1]
            local myVersion = ARGV[2]
            local ttl = tonumber(ARGV[3])

            local cachedVersion = redis.call('HGET', key, versionField)

            -- 缓存里的版本【比我的新】→ 说明别人已经写了更新的数据，放弃
            -- （相等则允许写：同一个版本写同样的数据，幂等无害）
            if cachedVersion and cachedVersion > myVersion then
                return 0
            end

            -- 先删再写：否则数据库里被置空的字段，缓存里会残留旧值
            -- （HSET 只覆盖给定字段，不会删除没给的字段）
            redis.call('DEL', key)

            local i = 4
            while i < #ARGV do
                redis.call('HSET', key, ARGV[i], ARGV[i + 1])
                i = i + 2
            end

            redis.call('EXPIRE', key, ttl)
            return 1
            """;

    private UserProfileCacheScript() {
    }
}
