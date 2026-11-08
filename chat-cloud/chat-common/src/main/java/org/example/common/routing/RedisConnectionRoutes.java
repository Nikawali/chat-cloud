package org.example.common.routing;

import java.time.Duration;
import java.util.List;
import org.example.common.dto.ConnectionRoute;
import org.example.common.constant.CacheKeys;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;

/** 连接路由的原子替换、条件续期和条件删除；Redis 故障向调用方传播。 */
public class RedisConnectionRoutes {
    public static final String PREFIX = CacheKeys.CONNECTION_ROUTE_PREFIX;
    private final StringRedisTemplate redis;

    private static final DefaultRedisScript<List> REPLACE = new DefaultRedisScript<>("""
            local old = {redis.call('HGET', KEYS[1], 'instanceId') or '',
                         redis.call('HGET', KEYS[1], 'sessionId') or ''}
            redis.call('HSET', KEYS[1], 'userId', ARGV[1], 'instanceId', ARGV[2], 'sessionId', ARGV[3])
            redis.call('PEXPIRE', KEYS[1], ARGV[4])
            return old
            """, List.class);
    private static final DefaultRedisScript<Long> RENEW = new DefaultRedisScript<>("""
            if redis.call('HGET', KEYS[1], 'instanceId') == ARGV[1]
               and redis.call('HGET', KEYS[1], 'sessionId') == ARGV[2] then
                return redis.call('PEXPIRE', KEYS[1], ARGV[3])
            end
            return 0
            """, Long.class);
    private static final DefaultRedisScript<Long> DELETE = new DefaultRedisScript<>("""
            if redis.call('HGET', KEYS[1], 'instanceId') == ARGV[1]
               and redis.call('HGET', KEYS[1], 'sessionId') == ARGV[2] then
                return redis.call('DEL', KEYS[1])
            end
            return 0
            """, Long.class);

    public RedisConnectionRoutes(StringRedisTemplate redis) { this.redis = redis; }

    public ConnectionRoute replace(Long userId, ConnectionRoute route, Duration ttl) {
        List<?> old = redis.execute(REPLACE, List.of(PREFIX + userId), String.valueOf(userId),
                route.instanceId(), route.sessionId(), String.valueOf(ttl.toMillis()));
        if (old == null || old.size() != 2 || old.get(0).toString().isEmpty()) return null;
        return new ConnectionRoute(old.get(0).toString(), old.get(1).toString());
    }

    public ConnectionRoute find(Long userId) {
        if (userId == null) return null;
        List<Object> values = redis.opsForHash().multiGet(PREFIX + userId, List.of("instanceId", "sessionId"));
        if (values.size() != 2 || values.get(0) == null || values.get(1) == null) return null;
        return new ConnectionRoute(values.get(0).toString(), values.get(1).toString());
    }

    public boolean renew(Long userId, ConnectionRoute expected, Duration ttl) {
        return Long.valueOf(1).equals(redis.execute(RENEW, List.of(PREFIX + userId),
                expected.instanceId(), expected.sessionId(), String.valueOf(ttl.toMillis())));
    }

    public java.util.Map<Long,ConnectionRoute> findAll(List<Long> users) {
        if(users.isEmpty()) return java.util.Map.of();
        if(users.size()>100) throw new IllegalArgumentException("Route batch exceeds 100");
        var serializer=redis.getStringSerializer();
        @SuppressWarnings("unchecked")
        var keySerializer=(org.springframework.data.redis.serializer.RedisSerializer<String>)redis.getKeySerializer();
        var result=redis.executePipelined((org.springframework.data.redis.core.RedisCallback<Object>) connection->{
            for(var user:users) connection.hashCommands().hMGet(keySerializer.serialize(PREFIX+user),
                    serializer.serialize("instanceId"),serializer.serialize("sessionId"));
            return null;
        });
        if(result.size()!=users.size()) throw new IllegalStateException("Incomplete route batch");
        var routes=new java.util.LinkedHashMap<Long,ConnectionRoute>();
        for(int i=0;i<users.size();i++) {
            var values=(List<?>)result.get(i);
            if(values.size()==2 && values.get(0)!=null && values.get(1)!=null)
                routes.put(users.get(i),new ConnectionRoute(values.get(0).toString(),values.get(1).toString()));
        }
        return routes;
    }

    public boolean remove(Long userId, ConnectionRoute expected) {
        return Long.valueOf(1).equals(redis.execute(DELETE, List.of(PREFIX + userId),
                expected.instanceId(), expected.sessionId()));
    }

    public boolean matches(Long userId, ConnectionRoute expected) {
        return expected.equals(find(userId));
    }
}
