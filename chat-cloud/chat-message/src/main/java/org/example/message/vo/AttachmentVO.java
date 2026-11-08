package org.example.message.vo;

/**
 * 上传附件的结果（{@code POST /message/upload} 的响应体）。
 *
 * <p><b>为什么上传和发消息是两步</b>：上传只负责"把字节落到我们的盘上、给我一个地址"，
 * 发消息仍是普通的 JSON 请求（{@code content} 放这个地址）。
 * 这样发消息那条路完全不用为"带文件"做特例：幂等（clientMessageId）、重推、补拉、
 * 未读统计统统照旧 —— 附件在服务端眼里只是"内容是一段 URL 的文本消息"。
 *
 * @param url         可以存进 {@code message.message_content} 的<b>相对路径</b>
 *                    （形如 {@code /api/message/uploads/<32位十六进制>.<扩展名>}）。
 *                    存相对路径而不是带域名的绝对地址：换机器/换端口时不会全坏。
 * @param name        原始文件名（只用于界面上显示"下载卡片"，不参与磁盘命名）
 * @param size        文件字节数（界面显示大小）
 * @param messageType 服务端判定的消息类型：2=图片 3=文件。
 *                    <b>由服务端决定，不听客户端的</b> —— 客户端说是图片，但文件头不是，
 *                    那就只能是文件。
 */
public record AttachmentVO(
        String url,
        String name,
        long size,
        int messageType
) {
}
