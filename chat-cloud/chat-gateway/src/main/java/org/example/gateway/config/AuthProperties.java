package org.example.gateway.config;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import org.example.common.constant.CacheKeys;
import org.springframework.boot.context.properties.ConfigurationProperties;

import jakarta.annotation.PostConstruct;
import lombok.Data;

/**
 * 网关鉴权配置，对应 application.yml 里的 {@code chat.security.*}。
 *
 * <p>用配置类而不是把白名单写死在过滤器里，原因是：
 * <ul>
 *   <li>加一个免登录接口不用改代码、不用重新编译</li>
 *   <li>测试环境和生产环境可以用不同的白名单</li>
 *   <li>白名单是安全边界，集中在一个文件里更容易审查（散在代码里最容易漏）</li>
 * </ul>
 */
@Data
@ConfigurationProperties(prefix = "chat.security")
public class AuthProperties {

    /**
     * token 在 Redis 里的 key 前缀。
     *
     * <p><b>默认值直接引用 chat-common 的常量</b>，而不是在这里再写一遍 {@code "auth:token:"} ——
     * 这个字符串 chat-auth 写、网关读，两边对不上就是"登录成功但所有接口都 401"，
     * 而且不报任何错。所以宁可"看不见这个字符串"，也不让它出现两次。
     *
     * <p>确实需要临时改成别的（比如灰度期间读旧前缀），才在 yml 里覆盖它。
     */
    private String tokenKeyPrefix = CacheKeys.AUTH_TOKEN_PREFIX;

    /**
     * 免登录路径（Ant 风格通配，如 {@code /api/user/**}）。
     * <p>默认空集合 —— 也就是"全都要登录"，安全的方向是默认拒绝。
     */
    private List<String> whitelist = new ArrayList<>();

    /**
     * 去掉每条配置首尾的空白（yml 里手写列表很容易多打空格），
     * 顺便去重。这种配置写错的后果是"某个接口莫名其妙需要登录"，
     * 排查起来很费时间，所以在启动时就规整好。
     */
    @PostConstruct
    void normalize() {
        whitelist = cleanPaths(whitelist);

        if (tokenKeyPrefix == null || tokenKeyPrefix.isBlank()) {
            tokenKeyPrefix = CacheKeys.AUTH_TOKEN_PREFIX;
        }
    }

    private static List<String> cleanPaths(List<String> paths) {
        if (paths == null) {
            return new ArrayList<>();
        }
        Set<String> cleaned = new LinkedHashSet<>();
        for (String path : paths) {
            if (path != null && !path.isBlank()) {
                cleaned.add(path.trim());
            }
        }
        return new ArrayList<>(cleaned);
    }
}
