package org.example.message.service.send;
import java.util.regex.Pattern;
import org.example.common.exception.BizException;
/** Shared text/type and signed attachment URL policy; keeps validation order at callers unchanged. */
public final class MessageContentPolicy {
 public static final int TYPE_TEXT=1;
 public static final int TYPE_IMAGE=2;
 public static final int TYPE_FILE=3;
 public static final int TYPE_VOICE=4;
 private MessageContentPolicy() {}
    private static final Pattern IMAGE_URL = Pattern.compile(
            "^/api/message/uploads/[0-9a-f]{32}\\.(png|jpg|gif|webp)(\\?[A-Za-z0-9=&%._+-]{0,700})?$");

    /** 同上，面向"文件"的扩展名白名单 */
    private static final Pattern FILE_URL = Pattern.compile(
            "^/api/message/uploads/[0-9a-f]{32}\\.(pdf|zip|rar|7z|doc|docx|xls|xlsx|ppt|pptx"
                    + "|txt|md|csv|mp3|wav|m4a|mp4|mov)(\\?[A-Za-z0-9=&%._+-]{0,700})?$");
    public static int requireSupportedType(Integer messageType) {
        if (messageType == null) {
            return TYPE_TEXT;
        }
        if (messageType != TYPE_TEXT && messageType != TYPE_IMAGE
                && messageType != TYPE_FILE && messageType != TYPE_VOICE) {
            throw new BizException(400, "不支持的消息类型：" + messageType);
        }
        return messageType;
    }
    public static void requireValidContent(int messageType, String content) {
        if (messageType != TYPE_IMAGE && messageType != TYPE_FILE) {
            return;
        }
        if (content == null || content.isBlank()) {
            throw new BizException(400, "图片/文件消息必须有附件地址");
        }
        Pattern expected = (messageType == TYPE_IMAGE) ? IMAGE_URL : FILE_URL;
        if (!expected.matcher(content).matches()) {
            throw new BizException(400,
                    messageType == TYPE_IMAGE
                            ? "图片消息的内容必须是本服务上传接口返回的地址"
                            : "文件消息的内容必须是本服务上传接口返回的地址");
        }
    }
}
