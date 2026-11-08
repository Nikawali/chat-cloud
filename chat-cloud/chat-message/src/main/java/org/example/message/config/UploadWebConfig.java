package org.example.message.config;
import org.example.message.service.attachment.AttachmentStorageService;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.CacheControl;
import org.springframework.web.servlet.config.annotation.ResourceHandlerRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import lombok.extern.slf4j.Slf4j;

/**
 * 把聊天附件的目录挂成静态资源，让 {@code <img src>} 和下载链接能直接读到文件。
 *
 * <p>和 chat-user 的 {@code UploadWebConfig} 是同一件事（那边挂头像），两个必须点记住：
 * <ol>
 *   <li><b>URL 是 {@code /message/uploads/**}</b>（不带 {@code /api}）：网关会把
 *       {@code /api/message/**} 里的 {@code /api} 去掉再转发，所以浏览器请求
 *       {@code /api/message/uploads/x.png}，到本服务就是 {@code /message/uploads/x.png}。</li>
 *   <li><b>只挂 {@code attachments/} 子目录</b>：上传根目录下面还有头像的 {@code avatars/}
 *       （chat-user 写的），这里要是把根目录整块挂出来，就等于把别人的头像目录也暴露在本服务的
 *       URL 下 —— 两个服务各自只该看自己那一份。</li>
 * </ol>
 *
 * <p>安全性质由 Spring 保证、但必须知道它们存在（不然容易自己写坏）：
 * {@code ResourceHttpRequestHandler} 会拒绝带 {@code ..} 的路径，出不了这个目录；
 * 而目录里只放我们生成的文件名（类型在落盘前就按白名单卡死了，见 AttachmentStorageService）。
 */
@Slf4j
@Configuration
public class UploadWebConfig implements WebMvcConfigurer {

    /** 附件目录的静态资源位置，形如 {@code file:///D:/.../data/uploads/attachments/} */
    private final String location;

    public UploadWebConfig(@Value("${chat.upload.dir:data/uploads}") String dir) throws IOException {
        Path attachmentDir = Paths.get(dir).toAbsolutePath().normalize().resolve("attachments");
        /*
          启动时就把目录建出来。
          不建的话映射会指向一个不存在的目录：读图时 404，而日志里一点痕迹都没有
          （静态资源找不到不会打异常）—— 这种"静默 404"最难查。
        */
        Files.createDirectories(attachmentDir);
        // toUri() 对已存在的目录会带结尾斜杠，addResourceLocations 要求必须有
        this.location = attachmentDir.toUri().toString();
        log.info("静态资源（聊天附件）映射: /message/uploads/** -> {}", location);
    }

    @Override
    public void addResourceHandlers(ResourceHandlerRegistry registry) {
        registry.addResourceHandler("/message/uploads/**")
                .addResourceLocations(location)
                /*
                  禁止浏览器缓存附件 —— 这一条是被一个真实 bug 逼出来的。
                  <p>
                  这里原先发的是 {@code max-age=365d, immutable}，理由听起来很对：文件名是 UUID、
                  内容永不变，缓存一年最省事。但它带来了一个谁都没想到的后果：
                  用户把本地那份删掉、断网、再打开会话，视频<b>居然还能播</b> ——
                  播的是 Chromium 磁盘缓存里的旧副本。于是"文件已经没了"这件事被浏览器
                  悄悄兜住，用户以为东西还在，实际上本地和服务器都靠不住。
                  <p>
                  改成 no-store 之后语义就干净了：<b>附件只有两个可信来源 ——
                  客户端自己的本地媒体库、或者服务器</b>。本地没有就必须真的发请求，
                  连不上就如实报错。客户端每次请求还会带上一次性令牌（见 Chat.vue 的
                  networkSrcOf），双保险：连升级前留下的旧缓存也命中不了。
                  <p>
                  代价：每次显示都得回服务器拿一次。可以接受 —— 常看的图/视频客户端会
                  存进 {@code messages/u<我>/media/}，之后走本地 blob，本来就不该依赖浏览器缓存。
                */
                .setCacheControl(CacheControl.noStore());
    }
}
