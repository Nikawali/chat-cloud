package org.example.user.service;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Pattern;

import org.example.common.exception.BizException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import lombok.extern.slf4j.Slf4j;

/**
 * 头像文件的落盘与删除。
 *
 * <p><b>为什么单独一个类，而不是在 controller 里写几行</b>：上传这件事的危险点很集中，
 * 而且几乎每一条都是"写错了会被利用"的那种：
 * <ol>
 *   <li><b>文件名不能用客户端给的</b>：{@code ../../application.yml} 这种名字一旦拼进路径，
 *       就是任意文件覆盖。这里一律用 UUID 重新命名，扩展名也只从<b>我们认出来的类型</b>里取。</li>
 *   <li><b>类型不能信 Content-Type</b>：那是客户端说的，随便改。真正能证明"这是一张 PNG"的
 *       只有文件头那几个字节，所以下面按魔术字节认类型（这也顺手挡掉了改名成 .png 的脚本）。</li>
 *   <li><b>不接收 SVG</b>：SVG 是 XML，里面能塞 {@code <script>}，当图片直出就是存储型 XSS。
 *       它不在白名单里，所以传不进来 —— 这是刻意排除，不是漏了。</li>
 *   <li><b>大小要自己判</b>：Spring 的 multipart 限额（spring.servlet.multipart.max-file-size）
 *       只会挡住"超大请求"，业务上还需要更小的上限（头像用不着 5MB）。</li>
 * </ol>
 *
 * <p>存进数据库的是<b>相对路径</b>（{@link #URL_PREFIX} 开头），不是带域名的绝对地址：
 * 数据库里写死 {@code http://localhost:8080/...} 的话，换台机器/换个端口所有头像就全坏了。
 * 前端渲染时再用自己那份 API 基址拼成完整 URL（见 api/index.js 的 assetUrl）。
 */
@Slf4j
@Service
public class AvatarStorageService {

    /**
     * 对外的 URL 前缀。注意：它是<b>经网关</b>的地址
     * （网关把 {@code /api/user/**} 去掉 {@code /api} 转发给本服务，见路由配置）。
     */
    public static final String URL_PREFIX = "/api/user/uploads/avatars/";

    /** 头像在"上传根目录"下的子目录；以后加聊天图片就是再开一个子目录 */
    private static final String AVATAR_SUB_DIR = "avatars";

    /**
     * 文件头 → 扩展名。只认这四种：PNG / JPEG / GIF / WEBP。
     *
     * <p>{@code bmp} 之类没放进来：现代浏览器显示没问题，但体积大、也少有人用；
     * 真需要时在这里加一行即可（加之前先确认它没有可执行内容）。
     */
    private static final Map<String, String> MAGIC_TO_EXT = Map.of(
            "png", "png",
            "jpeg", "jpg",
            "gif", "gif",
            "webp", "webp");

    /**
     * 我们自己的文件名长什么样（32 位十六进制 + 白名单扩展名）。
     *
     * <p>删文件时用它校验：这样即使数据库里的 avatar_url 被人塞了奇怪的值，
     * 也不可能删到目录之外的东西。
     */
    private static final Pattern OURS = Pattern.compile("^[0-9a-f]{32}\\.(png|jpg|gif|webp)$");

    /** 上传根目录（配置项 chat.upload.dir），下面按子目录分类存放 */
    private final Path rootDir;

    /** 单张头像的大小上限（配置项 chat.upload.max-avatar-bytes） */
    private final long maxAvatarBytes;

    public AvatarStorageService(
            @Value("${chat.upload.dir:data/uploads}") String dir,
            @Value("${chat.upload.max-avatar-bytes:2097152}") long maxAvatarBytes) {
        // 转成绝对路径再规范化：日志里打出来的路径要是能直接复制去打开的
        this.rootDir = Paths.get(dir).toAbsolutePath().normalize();
        this.maxAvatarBytes = maxAvatarBytes;
        log.info("头像上传目录: {}（单张上限 {} 字节）", rootDir, maxAvatarBytes);
    }

    /**
     * 保存一张头像。
     *
     * @param file 上传的文件；<b>为 null 或空</b>时返回 null（表示"这次没换头像"，不是错误）
     * @return 可以存进数据库的相对 URL；没传文件时返回 null
     * @throws BizException 400 —— 空文件、超过大小上限、不是允许的图片类型、写入失败
     */
    public String store(MultipartFile file) {
        if (file == null || file.isEmpty()) {
            return null;
        }
        if (file.getSize() > maxAvatarBytes) {
            // 把上限换算成 MB 再报给用户，比"2097152 字节"友好
            throw new BizException(400, "头像不能超过 " + (maxAvatarBytes / 1024 / 1024) + "MB");
        }

        String ext = sniffExtension(file);

        // 用 UUID 重新命名：客户端给的文件名一概不用（它可能带路径、可能重名、可能是 .jsp）
        String fileName = UUID.randomUUID().toString().replace("-", "") + "." + ext;

        Path dir = rootDir.resolve(AVATAR_SUB_DIR);
        Path target = dir.resolve(fileName).normalize();
        // 再确认一次目标确实在目录内：UUID 是自己生成的，理论上不会越界，
        // 但"拼出来的路径必须落在预期目录内"这条检查很便宜，值得留着当护栏
        if (!target.startsWith(dir)) {
            throw new BizException(400, "文件名不合法");
        }

        try {
            Files.createDirectories(dir);
            // 直接写到最终路径。这里不需要"临时文件 + rename"那一套：
            // 文件名是全新的，不存在"读到写了一半的文件"的可能（没人知道这个名字）
            try (InputStream in = file.getInputStream()) {
                Files.copy(in, target, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException e) {
            // 磁盘满 / 权限不足：这是服务端的问题，报 500 而不是 400
            log.error("保存头像失败: target={}", target, e);
            throw new BizException(500, "头像保存失败，请稍后重试");
        }

        String url = URL_PREFIX + fileName;
        log.info("头像已保存: url={}, 大小={} 字节", url, file.getSize());
        return url;
    }

    /**
     * 删掉一张<b>我们自己的</b>头像（换头像时清理旧文件）。
     *
     * <p>三条"不做"都是有意的：
     * <ul>
     *   <li>不是我们的 URL（外部图片地址、空值）→ 直接返回，绝不去删别人的东西；</li>
     *   <li>文件名不符合我们的命名规则 → 不删（防止数据库里被塞了越界路径）；</li>
     *   <li>删失败只记日志、不抛异常 → 用户换头像已经成功了，不该因为"旧文件没删掉"报错。</li>
     * </ul>
     */
    public void deleteIfOurs(String url) {
        if (url == null || !url.startsWith(URL_PREFIX)) {
            return;
        }
        String fileName = url.substring(URL_PREFIX.length());
        if (!OURS.matcher(fileName).matches()) {
            log.warn("头像 URL 不符合本服务的命名规则，跳过删除: {}", url);
            return;
        }
        try {
            Files.deleteIfExists(rootDir.resolve(AVATAR_SUB_DIR).resolve(fileName));
        } catch (IOException e) {
            log.warn("删除旧头像失败（不影响本次修改）: {}", url, e);
        }
    }

    /**
     * 按<b>文件头</b>认类型，返回扩展名。
     *
     * <p>为什么不信 {@code file.getContentType()}：那是请求里带的字符串，改一个字节就能骗过它；
     * 而"文件头"是文件内容本身，要伪造就得真的构造出一个合法图片
     * （构造出来了，那它确实能被当图片解析，也就没有执行风险）。
     */
    private static String sniffExtension(MultipartFile file) {
        byte[] head = new byte[12];
        int read;
        try (InputStream in = file.getInputStream()) {
            read = in.readNBytes(head, 0, head.length);
        } catch (IOException e) {
            throw new BizException(400, "读取上传文件失败");
        }
        if (read < 12) {
            throw new BizException(400, "图片内容不完整");
        }

        String kind = null;
        if (head[0] == (byte) 0x89 && head[1] == 0x50 && head[2] == 0x4E && head[3] == 0x47) {
            kind = "png";           // 89 50 4E 47 .PNG
        } else if (head[0] == (byte) 0xFF && head[1] == (byte) 0xD8 && head[2] == (byte) 0xFF) {
            kind = "jpeg";          // FF D8 FF
        } else if (head[0] == 'G' && head[1] == 'I' && head[2] == 'F' && head[3] == '8') {
            kind = "gif";           // GIF8
        } else if (head[0] == 'R' && head[1] == 'I' && head[2] == 'F' && head[3] == 'F'
                && head[8] == 'W' && head[9] == 'E' && head[10] == 'B' && head[11] == 'P') {
            kind = "webp";          // RIFF....WEBP
        }

        String ext = kind == null ? null : MAGIC_TO_EXT.get(kind);
        if (ext == null) {
            // 报出来的是"允许哪几种"，而不是"你传的是什么"：
            // 后者等于告诉攻击者他在哪一步被认出（也容易把内部细节带出去）
            throw new BizException(400, "只支持 PNG / JPG / GIF / WEBP 格式的图片");
        }
        return ext;
    }
}
