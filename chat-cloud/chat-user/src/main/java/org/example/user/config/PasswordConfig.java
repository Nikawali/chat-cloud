package org.example.user.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;

/**
 * 密码加密器。
 *
 * <p>只引了 spring-security-crypto，没有引 spring-boot-starter-security，
 * 所以这里纯粹是一个工具 Bean，不会引入任何登录过滤器。
 */
@Configuration
public class PasswordConfig {

    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }
}
