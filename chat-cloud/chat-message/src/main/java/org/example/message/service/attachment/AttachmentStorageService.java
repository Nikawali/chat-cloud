package org.example.message.service.attachment;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import org.example.common.exception.BizException;
import org.example.message.vo.AttachmentVO;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import lombok.extern.slf4j.Slf4j;

/**
 * 聊天附件（图片 / 文件）的落盘与类型判定。
 *
 * <p>整体照搬 chat-user 的 {@code AvatarStorageService}：那套"UUID 命名 + 魔术字节嗅探 +
 * 白名单扩展名"的写法在头像上已经验证过，这里不重新发明。但聊天附件比头像多了两个难点：
 * <ol>
 *   <li><b>文件类型无法靠文件头全部识别</b>：PDF/ZIP/DOCX 各有各的文件头，但绝大多数办公文档
 *       我们没法可靠嗅探。所以策略是"<b>图片必须嗅探通过、其余按扩展名白名单</b>"；
 *       而白名单里<b>刻意不含任何能被浏览器执行/渲染的类型</b>（html / svg / js / xml）——
 *       这些文件被当静态资源直出时就是存储型 XSS。</li>
 *   <li><b>文件名不能用客户端的</b>：{@code ../../application.yml} 拼进路径就是任意文件覆盖。
 *       磁盘上一律用 UUID，客户端的原始文件名只回给界面当"显示名"。</li>
 * </ol>
 *
 * <p><b>大小上限 50MB</b>（{@code chat.upload.max-attachment-bytes}）：
 * 真正挡住超大请求的是 {@code spring.servlet.multipart.max-file-size}（50MB，请求级硬闸），
 * 这里再判一次是为了给出一句人话的错误、以及挡住 0 字节。
 *
 * <p><b>存进数据库的是相对路径</b>（{@link #URL_PREFIX} 开头）：写死 {@code http://localhost:8080/...}
 * 的话，换机器/换端口所有历史图片全坏。前端渲染时再用自己的 API 基址拼完整地址
 * （见 chat-web 的 {@code api.assetUrl}）。
 */
@Slf4j
@Service
public class AttachmentStorageService {

    /**
     * 对外的 URL 前缀。<b>经网关</b>的地址：网关把 {@code /api/message/**} 去掉 {@code /api}
     * 转发给本服务，所以浏览器请求 {@code /api/message/uploads/x.png} 时，
     * 本服务收到的是 {@code /message/uploads/x.png}（和 UploadWebConfig 的映射对上）。
     */
    public static final String URL_PREFIX = "/api/message/uploads/";

    /** 附件在"上传根目录"下的子目录（和头像的 {@code avatars/} 并列） */
    private static final String SUB_DIR = "attachments";

    /** 消息类型：和 {@code message.message_type} 的取值一致 */
    public static final int TYPE_TEXT = org.example.message.service.send.MessageContentPolicy.TYPE_TEXT;
    public static final int TYPE_IMAGE = org.example.message.service.send.MessageContentPolicy.TYPE_IMAGE;
    public static final int TYPE_FILE = org.example.message.service.send.MessageContentPolicy.TYPE_FILE;
    public static final int TYPE_VOICE = org.example.message.service.send.MessageContentPolicy.TYPE_VOICE;

    /** 文件头 → 扩展名：只认这四种图片（{@code svg} 刻意不在里面，理由见类注释） */
    private static final Map<String, String> MAGIC_TO_EXT = Map.of(
            "png", "png",
            "jpeg", "jpg",
            "gif", "gif",
            "webp", "webp");

    /** 能被当图片直出的扩展名（嗅探失败时用来识破"改名成 .png 的东西"） */
    private static final Set<String> IMAGE_EXTS = Set.of("png", "jpg", "gif", "webp");

    /**
     * 允许当"文件"发送的扩展名。
     *
     * <p><b>这是一份白名单，不是黑名单</b>：不在表里的一律拒绝。
     * 特别地，下列类型<b>故意不支持</b>（它们被静态资源处理器直出时能在我们域名下执行）：
     * {@code html/htm/svg/xml/js/mjs/css}；以及 {@code exe/bat/cmd/ps1/sh/jar/msi} 这类
     * 客户端可执行文件 —— 聊天软件传过去也只会变成"让对方双击运行"的诈骗素材。
     */
    private static final Set<String> FILE_EXTS = Set.of(
            "pdf", "zip", "rar", "7z",
            "doc", "docx", "xls", "xlsx", "ppt", "pptx",
            "txt", "md", "csv",
            "mp3", "wav", "m4a", "mp4", "mov");

    /**
     * "我们自己签发的图片 URL"长什么样：前缀 + 32 位十六进制 + 图片扩展名，
     * 后面允许带一小段查询串（界面用它携带原始文件名/大小，见 {@link #queryOf}）。
     *
     * <p>查询串给到 700 个字符：中文文件名按 UTF-8 百分号编码后大约膨胀 9 倍，
     * 60 个字的文件名就有 500 多字符 —— 卡太紧会把正常的中文附件名判成非法。
     */


    /** 上传根目录（配置项 chat.upload.dir），下面按子目录分类存放 */
    private final Path rootDir;

    /** 单个附件的大小上限（配置项 chat.upload.max-attachment-bytes，默认 50MB） */
    private final long maxAttachmentBytes;

    public AttachmentStorageService(
            @Value("${chat.upload.dir:data/uploads}") String dir,
            @Value("${chat.upload.max-attachment-bytes:52428800}") long maxAttachmentBytes) {
        this.rootDir = Paths.get(dir).toAbsolutePath().normalize();
        this.maxAttachmentBytes = maxAttachmentBytes;
        log.info("聊天附件目录: {}（单个上限 {} 字节）", attachmentDir(), maxAttachmentBytes);
    }

    /** 附件实际落盘的目录（UploadWebConfig 也用它做静态映射） */
    public Path attachmentDir() {
        return rootDir.resolve(SUB_DIR);
    }

    /**
     * 保存一个附件。
     *
     * @param file 上传的文件
     * @return 落盘结果（相对 URL / 原始文件名 / 字节数 / 服务端判定的消息类型）
     * @throws BizException 400 —— 空文件、超过上限、类型不允许、写入失败
     */
    public AttachmentVO store(MultipartFile file) {
        if (file == null || file.isEmpty() || file.getSize() <= 0) {
            throw new BizException(400, "上传的文件是空的");
        }
        if (file.getSize() > maxAttachmentBytes) {
            // 换算成 MB 再报，比"52428800 字节"友好
            throw new BizException(400, "文件不能超过 " + (maxAttachmentBytes / 1024 / 1024) + "MB");
        }

        String declaredExt = extensionOf(file.getOriginalFilename());
        String sniffed = sniffImageExtension(file);

        String ext;
        int messageType;
        if (sniffed != null) {
            // 文件头确实是一张图片 → 不管客户端说的扩展名是什么，都按图片存
            ext = sniffed;
            messageType = TYPE_IMAGE;
        } else if (declaredExt != null && IMAGE_EXTS.contains(declaredExt)) {
            /*
              名字是 .png/.jpg 却嗅探不出图片头 —— 这是典型的"伪装成图片"，
              直接拒掉。不能退化成"当文件存"：那样它会被当成图片类型直出，
              浏览器按扩展名猜类型，正是我们想避免的事。
            */
            throw new BizException(400, "这个文件不是有效的 PNG / JPG / GIF / WEBP 图片");
        } else if (declaredExt != null && FILE_EXTS.contains(declaredExt)) {
            ext = declaredExt;
            messageType = TYPE_FILE;
        } else {
            // 报"支持哪些"而不是"你传的是什么"：少给对方一点试探信息
            throw new BizException(400,
                    "不支持这种文件类型（支持图片与 pdf/zip/rar/7z/doc/xls/ppt/txt/md/csv/音视频）");
        }

        String storedName = UUID.randomUUID().toString().replace("-", "") + "." + ext;
        Path dir = attachmentDir();
        Path target = dir.resolve(storedName).normalize();
        // UUID 是自己生成的，理论上越不出目录；这条护栏很便宜，留着
        if (!target.startsWith(dir)) {
            throw new BizException(400, "文件名不合法");
        }

        try {
            Files.createDirectories(dir);
            // 文件名是全新的，不存在"读到写了一半"的可能，所以不需要临时文件 + rename
            try (InputStream in = file.getInputStream()) {
                Files.copy(in, target, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException e) {
            log.error("保存聊天附件失败: target={}", target, e);
            throw new BizException(500, "文件保存失败，请稍后重试");
        }

        String url = URL_PREFIX + storedName;
        String originalName = safeOriginalName(file.getOriginalFilename(), storedName);
        // 原始文件名和大小挂在查询串上：数据库里只有 content 一列，
        // 不收在这里的话，接收方就只能看到一串 UUID，不知道下载的是什么
        String contentUrl = url + queryOf(originalName, file.getSize());

        log.info("聊天附件已保存: url={}, 原始名={}, 大小={} 字节, 类型={}",
                url, originalName, file.getSize(), messageType);
        return new AttachmentVO(contentUrl, originalName, file.getSize(), messageType);
    }

    // ==================== 发消息时的校验 ====================

    /**
     * 校验消息类型（1 文本 / 2 图片 / 3 文件 / 4 语音）。不在白名单里直接 400。
     *
     * <p>为什么要白名单：{@code message_type} 是客户端传上来的整数，
     * 不校验就能塞 99、-1、任意值进去，之后每个读它的地方（前端渲染、预览、
     * 统计）都得跟着写一遍兜底分支。
     */
    public static int requireSupportedType(Integer type) {return org.example.message.service.send.MessageContentPolicy.requireSupportedType(type);}

    /**
     * 校验消息内容：<b>图片/文件消息的 content 必须是我们自己签发的 URL</b>。
     *
     * <p><b>为什么必须卡死这一点</b>：附件消息的内容会被前端直接塞进
     * {@code <img src>} / 下载链接。如果放任任意地址（{@code http://evil/x.png}），
     * 这个聊天就变成了别人的图床；更糟的是 {@code javascript:} 之类的伪协议、
     * 或者指向内网的地址，都可能被利用来探测/钓鱼。
     * 所以规则很简单：<b>只接受本服务上传接口产出的那种形状</b>，其余一律拒。
     *
     * <p>文本（1）和语音（4）不做额外限制：文本本来就是自由内容，
     * 语音目前还没有上传入口（以后按 2/3 同样的规则接）。
     */
    public static void requireValidContent(int type,String content) {org.example.message.service.send.MessageContentPolicy.requireValidContent(type,content);}

    // ==================== 内部工具 ====================

    /** 取小写扩展名；没有扩展名返回 null */
    private static String extensionOf(String name) {
        if (name == null) {
            return null;
        }
        int dot = name.lastIndexOf('.');
        if (dot < 0 || dot == name.length() - 1) {
            return null;
        }
        String ext = name.substring(dot + 1).toLowerCase(Locale.ROOT);
        // 扩展名里出现别的东西（路径分隔符、空格……）一律不认
        return ext.matches("[a-z0-9]{1,8}") ? ext : null;
    }

    /**
     * 按文件头认图片类型；不是图片返回 null。
     *
     * <p>和头像那边同一套判据：不信 {@code Content-Type}（客户端随便写），
     * 只信内容本身。要伪造就得真的构造出合法图片 —— 而那样它确实能被当图片解析，
     * 也就没有执行风险了。
     */
    private static String sniffImageExtension(MultipartFile file) {
        byte[] head = new byte[12];
        int read;
        try (InputStream in = file.getInputStream()) {
            read = in.readNBytes(head, 0, head.length);
        } catch (IOException e) {
            throw new BizException(400, "读取上传文件失败");
        }
        if (read < 12) {
            return null;   // 太短，不可能是我们支持的图片（交给后面的扩展名分支判）
        }

        String kind = null;
        if (head[0] == (byte) 0x89 && head[1] == 0x50 && head[2] == 0x4E && head[3] == 0x47) {
            kind = "png";           // 89 50 4E 47
        } else if (head[0] == (byte) 0xFF && head[1] == (byte) 0xD8 && head[2] == (byte) 0xFF) {
            kind = "jpeg";          // FF D8 FF
        } else if (head[0] == 'G' && head[1] == 'I' && head[2] == 'F' && head[3] == '8') {
            kind = "gif";           // GIF8
        } else if (head[0] == 'R' && head[1] == 'I' && head[2] == 'F' && head[3] == 'F'
                && head[8] == 'W' && head[9] == 'E' && head[10] == 'B' && head[11] == 'P') {
            kind = "webp";          // RIFF....WEBP
        }
        return kind == null ? null : MAGIC_TO_EXT.get(kind);
    }

    /**
     * 把客户端给的文件名洗成"可以安全回显"的形状：只取最后一段、去掉控制字符、限长。
     *
     * <p>它<b>只用于界面显示</b>（磁盘名是 UUID），但也不能原样回显：
     * 一个含换行/超长/路径的名字会让界面错乱，长到极致还会把 URL 撑爆。
     *
     * <p>超长时保留<b>头尾</b>（中间用省略号）：结尾的扩展名是"这是什么文件"的关键，
     * 开头才是用户认得出的部分 —— 只留其中一头都会让卡片变得没用。
     */
    private static String safeOriginalName(String original, String fallback) {
        if (original == null) {
            return fallback;
        }
        // 有些浏览器会带上客户端完整路径（老 IE 遗留行为），这里只留文件名
        String name = original.replace('\\', '/');
        int slash = name.lastIndexOf('/');
        if (slash >= 0) {
            name = name.substring(slash + 1);
        }
        name = name.replaceAll("[\\p{Cntrl}]", "").trim();
        if (name.isEmpty()) {
            return fallback;
        }
        final int max = 60;
        if (name.length() > max) {
            name = name.substring(0, 45) + "…" + name.substring(name.length() - 12);
        }
        return name;
    }

    /**
     * 拼查询串：{@code ?n=<URL 编码的文件名>&s=<字节数>}。
     *
     * <p>为什么要挂在 URL 上（而不是加数据库列）：{@code message} 表只有
     * {@code message_content} 一个内容列，为两个纯展示字段加列、加迁移不划算。
     * 查询串对静态资源读取无影响（Spring 会忽略它），而前端能从中解析出显示名和大小。
     */
    private static String queryOf(String name, long size) {
        String encoded = java.net.URLEncoder.encode(name, java.nio.charset.StandardCharsets.UTF_8)
                // 查询串里 '+' 会被解码成空格，必须还原成 %20，否则中文名里会出现多余空格
                .replace("+", "%20");
        return "?n=" + encoded + "&s=" + size;
    }
}
