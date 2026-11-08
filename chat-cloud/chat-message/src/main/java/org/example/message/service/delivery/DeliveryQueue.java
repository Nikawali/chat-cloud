package org.example.message.service.delivery;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.UUID;
import org.springframework.data.redis.core.script.DefaultRedisScript;

import org.example.common.constant.CacheKeys;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;


/**
 * 待送达队列（Redis）—— "谁的消息还没被确认收到"。
 *
 * <h3>它解决什么问题</h3>
 * 消息推给对方之后，对方要回一个 <b>ack</b> 才算真的收到了。收不到 ack 就要重推，
 * 于是需要一个"待确认清单"和"什么时候该重推"的调度信息。
 *
 * <h3>三个刻意的设计</h3>
 * <ol>
 *   <li><b>用 ZSET 存，score = 下次重试时间</b>：这样"哪些该重推了"是一次
 *       {@code ZRANGEBYSCORE 0 现在} 查询。换成"每条消息一个定时器"，
 *       几千条未确认消息就是几千个任务，先把 JVM 拖死。</li>
 *   <li><b>只存 id，不存消息内容</b>：内容以数据库为准（重推时按 id 查库）。
 *       好处是 Redis 里不会出现"和库里不一致的副本"，也不怕内容很大。</li>
 *   <li><b>丢了也不影响正确性</b>：队列整个没了，最坏结果是"实时推送不重试"，
 *       消息和收据还在库里，对方上线时"补拉"照旧能拿到。
 *       所以这里不需要和 MySQL 做任何分布式事务。</li>
 * </ol>
 */
@Component
public class DeliveryQueue {

    private static final Logger log = LoggerFactory.getLogger(DeliveryQueue.class);

    private final StringRedisTemplate redisTemplate;

    // 多 key Lua 使用当前单机/哨兵 Redis；Redis Cluster 需要先迁移到相同槽的 key。
    private static final String CLAIM_HASH = CacheKeys.PENDING_CLAIM_HASH;
    private static final String CLOCK_FOR_ENQUEUE = """
            local t = redis.call('TIME')
            local now = t[1] * 1000 + math.floor(t[2] / 1000)
            """;
    private final List<String> scriptKeys;
    @org.springframework.beans.factory.annotation.Autowired
    public DeliveryQueue(StringRedisTemplate redisTemplate) {
        this(redisTemplate, List.of(CacheKeys.PENDING_DELIVERY_ZSET, CacheKeys.PENDING_ATTEMPT_HASH, CLAIM_HASH));
    }
    public DeliveryQueue(StringRedisTemplate redisTemplate, List<String> keys) {
        this.redisTemplate = redisTemplate;
        this.scriptKeys = List.copyOf(keys);
    }

    /** MQ consumer: propagate Redis failure; duplicate events must not reset lease or attempts. */
    public boolean enqueueIfAbsent(Long messageId, Long receiverUserId, long delayMillis) {
        Long result = redisTemplate.execute(ENQUEUE_ONCE, scriptKeys, member(messageId, receiverUserId),
                Long.toString(Math.max(0, delayMillis)));
        if (result == null) throw new IllegalStateException("Redis did not confirm delivery registration");
        return result == 1;
    }
    private static final DefaultRedisScript<List> ENQUEUE_BATCH = new DefaultRedisScript<>(CLOCK_FOR_ENQUEUE + """
            local added = {}
            for i=2,#ARGV do
                if redis.call('ZADD',KEYS[1],'NX',now+tonumber(ARGV[1]),ARGV[i]) == 1 then
                    table.insert(added,ARGV[i])
                end
            end
            return added
            """,List.class);
    public List<Long> enqueueBatch(Long messageId,List<Long> users,long delayMillis) {
        if(users.isEmpty()) return List.of();
        if(users.size()>100) throw new IllegalArgumentException("Delivery batch exceeds 100");
        var args=new ArrayList<String>();args.add(Long.toString(Math.max(0,delayMillis)));
        users.forEach(user->args.add(member(messageId,user)));
        List<?> added=redisTemplate.execute(ENQUEUE_BATCH,scriptKeys,args.toArray());
        if(added==null) throw new IllegalStateException("Redis did not confirm batch registration");
        return added.stream().map(value->parse(value.toString()).receiverUserId()).toList();
    }
    private static final DefaultRedisScript<Long> ENQUEUE_ONCE = new DefaultRedisScript<>(CLOCK_FOR_ENQUEUE + """
            return redis.call('ZADD', KEYS[1], 'NX', now + tonumber(ARGV[2]), ARGV[1])
            """, Long.class);
    private static final String CLOCK = """
            local t = redis.call('TIME')
            local now = t[1] * 1000 + math.floor(t[2] / 1000)
            """;
    @SuppressWarnings("rawtypes")
    private static final DefaultRedisScript<List> CLAIM = new DefaultRedisScript<>(CLOCK + """
            local members = redis.call('ZRANGEBYSCORE', KEYS[1], '-inf', now, 'LIMIT', 0, ARGV[1])
            local result = {}
            for _, member in ipairs(members) do
                redis.call('ZADD', KEYS[1], now + tonumber(ARGV[2]), member)
                redis.call('HSET', KEYS[3], member, ARGV[3])
                local attempt = redis.call('HINCRBY', KEYS[2], member, 1)
                table.insert(result, member)
                table.insert(result, tostring(attempt))
            end
            return result
            """, List.class);
    private static final DefaultRedisScript<Long> FINISH = new DefaultRedisScript<>(CLOCK + """
            if redis.call('HGET', KEYS[3], ARGV[1]) ~= ARGV[2] then return 0 end
            local deadline = redis.call('ZSCORE', KEYS[1], ARGV[1])
            if not deadline or tonumber(deadline) <= now then return 0 end
            if ARGV[3] == 'check' then return 1 end
            if ARGV[3] == 'remove' then
                redis.call('ZREM', KEYS[1], ARGV[1])
                redis.call('HDEL', KEYS[2], ARGV[1])
            else
                redis.call('ZADD', KEYS[1], 'XX', now + tonumber(ARGV[4]), ARGV[1])
            end
            redis.call('HDEL', KEYS[3], ARGV[1])
            return 1
            """, Long.class);
    private static final DefaultRedisScript<Long> REMOVE = new DefaultRedisScript<>("""
            for _, member in ipairs(ARGV) do
                redis.call('ZREM', KEYS[1], member)
                redis.call('HDEL', KEYS[2], member)
                redis.call('HDEL', KEYS[3], member)
            end
            return #ARGV
            """, Long.class);
    private static final DefaultRedisScript<Long> ENQUEUE = new DefaultRedisScript<>(CLOCK + """
            redis.call('HDEL', KEYS[3], ARGV[1])
            return redis.call('ZADD', KEYS[1], now + tonumber(ARGV[2]), ARGV[1])
            """, Long.class);

    public record ClaimedDelivery(PendingDelivery pending, String token, int attempt) { }

    /** Reserving moves the score to the lease deadline, so crashed claims become due again. */
    public List<ClaimedDelivery> claimDue(int limit, long leaseMillis) {
        if (limit <= 0 || leaseMillis <= 0) return List.of();
        String token = UUID.randomUUID().toString();
        try {
            List<?> rows = redisTemplate.execute(CLAIM, scriptKeys, Integer.toString(limit),
                    Long.toString(leaseMillis), token);
            if (rows == null) return List.of();
            List<ClaimedDelivery> result = new ArrayList<>();
            for (int i = 0; i < rows.size(); i += 2) {
                String value = (String) rows.get(i);
                PendingDelivery pending = parse(value);
                if (pending == null) {
                    redisTemplate.execute(FINISH, scriptKeys, value, token, "remove", "0");
                } else {
                    result.add(new ClaimedDelivery(pending, token,
                            Integer.parseInt((String) rows.get(i + 1))));
                }
            }
            return result;
        } catch (Exception e) {
            log.warn("Cannot claim deliveries; leases will recover: {}", e.getMessage());
            return List.of();
        }
    }

    public boolean reschedule(ClaimedDelivery claim, long delayMillis) {
        return finish(claim, "retry", Math.max(0, delayMillis));
    }

    /** Avoid starting work for claims expired during batch prefetch/processing. */
    public boolean isClaimActive(ClaimedDelivery claim) {
        return finish(claim, "check", 0);
    }

    public boolean removeClaimed(ClaimedDelivery claim) {
        return finish(claim, "remove", 0);
    }

    private boolean finish(ClaimedDelivery claim, String action, long delayMillis) {
        try {
            return Long.valueOf(1).equals(redisTemplate.execute(FINISH, scriptKeys,
                    member(claim.pending().messageId(), claim.pending().receiverUserId()),
                    claim.token(), action, Long.toString(delayMillis)));
        } catch (Exception e) {
            log.warn("Cannot finish delivery claim; lease will recover: {}", e.getMessage());
            return false;
        }
    }

    /** 一条待送达记录：消息 id + 接收者 */
    public record PendingDelivery(Long messageId, Long receiverUserId) {
    }

    /**
     * 登记一条待送达（如果已存在就更新它的重试时间）。
     *
     * @param delayMillis 多久之后允许重试；发消息时刚推过一次，所以传退避间隔而不是 0
     */
    public void enqueue(Long messageId, Long receiverUserId, long delayMillis) {
        String member = member(messageId, receiverUserId);
        try {
            redisTemplate.execute(ENQUEUE, scriptKeys, member, Long.toString(Math.max(0, delayMillis)));
        } catch (Exception e) {
            // 入队失败不抛：消息已经落库了，最多是"这次不重试"，补拉还在
            log.warn("登记待送达失败（不影响消息本身）: messageId={}, receiver={}, 原因={}",
                    messageId, receiverUserId, e.getMessage());
        }
    }

    /** 送达确认（或者决定放弃重试）之后出队 */
    public void remove(Long messageId, Long receiverUserId) {
        removeAll(List.of(messageId), receiverUserId);
    }
    public void removeReceivers(Long messageId,List<Long> users) {
        if(users.isEmpty()) return;
        try {redisTemplate.execute(REMOVE,scriptKeys,users.stream().map(user->member(messageId,user)).toArray());}
        catch(Exception e) {log.warn("Batch receiver cleanup failed; retry scanner will recover",e);}
    }

    /**
     * 一次出队同一接收者确认的多条消息。
     *
     * <p>ACK 常常是一页一批（当前前端一次最多 50 条）。如果逐条调用 {@link #remove(Long, Long)}，
     * 每一条都会执行一次 {@code ZREM} 和一次 {@code HDEL}，50 条 ACK 就是 100 次 Redis 命令。
     * Lua 原子删除调度、次数和 claim，防止并发重试重新入队。
     *
     * <p>和单条出队一样，失败只记日志：收据的 {@code delivered_at} 已经是数据库真相，
     * 下次重试扫描发现消息已送达时会自行清理遗留的队列项。
     */
    public void removeAll(Collection<Long> messageIds, Long receiverUserId) {
        if (messageIds == null || messageIds.isEmpty() || receiverUserId == null) {
            return;
        }
        List<String> members = messageIds.stream()
                .filter(java.util.Objects::nonNull)
                .distinct()
                .map(messageId -> member(messageId, receiverUserId))
                .toList();
        if (members.isEmpty()) {
            return;
        }
        Object[] memberArray = members.toArray();
        try {
            redisTemplate.execute(REMOVE, scriptKeys, memberArray);
        } catch (Exception e) {
            // 没删掉也不要紧：重试任务发现"收据上 delivered_at 已经有值"会自己把它清掉
            log.warn("批量待送达出队失败（重试任务会自愈）: 条数={}, receiver={}, 原因={}",
                    members.size(), receiverUserId, e.getMessage());
        }
    }

    /** 当前有多少条待送达（调试用；生产别拿它做业务判断，ZSET 可能很大） */
    public long size() {
        try {
            Long size = redisTemplate.opsForZSet().size(scriptKeys.get(0));
            return size == null ? 0 : size;
        } catch (Exception e) {
            // 不抛是对的（它只是个观测值），但【不能一声不响】：静默的 catch
            // 会让"Redis 挂了"在日志里毫无痕迹，排查时只剩一个恒为 0 的数字。
            log.debug("读待送达队列长度失败，按 0 返回: {}", e.getMessage());
            return 0;
        }
    }

    private String member(Long messageId, Long receiverUserId) {
        // 用 "messageId:receiverUserId" 当 ZSET 的 member：同一对 (消息, 接收者) 天然唯一，
        // 重复入队只会更新分数，不会堆出两条
        return messageId + ":" + receiverUserId;
    }

    private PendingDelivery parse(String member) {
        int split = member.indexOf(':');
        if (split <= 0) {
            return null;
        }
        try {
            return new PendingDelivery(
                    Long.valueOf(member.substring(0, split)),
                    Long.valueOf(member.substring(split + 1)));
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
