package org.example.user.config;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.ResourceHandlerRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import lombok.extern.slf4j.Slf4j;

/**
 * 把上传目录挂成静态资源，让 &lt;img src&gt; 能直接读到头像。
 *
 * <p><b>为什么需要一个 WebMvcConfigurer</b>：Spring Boot 默认只从 classpath 下的
 * {@code static/} 提供静态文件；我们上传的图片在<b>磁盘上的一个目录</b>里，
 * 必须显式告诉它"这个 URL 前缀对应磁盘上哪个目录"。
 *
 * <p><b>URL 为什么是 {@code /user/uploads/**}</b>：网关会把 {@code /api/user/**}
 * 里的 {@code /api} 去掉再转发，所以浏览器请求 {@code /api/user/uploads/avatars/x.png}，
 * 到本服务时就是 {@code /user/uploads/avatars/x.png} —— 和这里的映射对上。
 *
 * <p>另外两条安全性质由 Spring 保证，但要知道它们存在（不然很容易自己写坏）：
 * <ul>
 *   <li>{@code ResourceHttpRequestHandler} 会拒绝带 {@code ..} 的路径 —— 出不了这个目录；</li>
 *   <li>目录里只放我们自己生成的文件名，没有任何可执行内容（类型在入库前就按文件头卡死了）。</li>
 * </ul>
 */
@Slf4j
@Configuration
public class UploadWebConfig implements WebMvcConfigurer {

    /** 上传根目录的静态资源位置，形如 {@code file:///D:/.../data/uploads/} */
    private final String location;

    public UploadWebConfig(@Value("${chat.upload.dir:data/uploads}") String dir) throws IOException {
        Path root = Paths.get(dir).toAbsolutePath().normalize();
        /*
          启动时就把目录建出来。
          不建的话，映射会指向一个不存在的目录：读图时 404，而日志里一点痕迹都没有
          （静态资源找不到不会打异常）—— 这种"静默 404"最难查。
        */
        Files.createDirectories(root);
        // toUri() 对已存在的目录会带上结尾斜杠，addResourceLocations 要求必须有
        this.location = root.toUri().toString();
        log.info("静态资源（上传目录）映射: /user/uploads/** -> {}", location);
    }

    @Override
    public void addResourceHandlers(ResourceHandlerRegistry registry) {
        registry.addResourceHandler("/user/uploads/**")
                .addResourceLocations(location);
    }
}
