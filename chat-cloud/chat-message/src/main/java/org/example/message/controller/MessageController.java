package org.example.message.controller;

import java.util.List;

import org.example.common.constant.AuthHeaders;
import org.example.common.result.Result;
import org.example.common.support.RequestUser;
import org.example.message.dto.DeliveredAckRequest;
import org.example.message.dto.ReadMessageRequest;
import org.example.message.dto.SendMessageRequest;
import org.example.message.service.attachment.AttachmentStorageService;
import org.example.message.service.single.ConversationService;
import org.example.message.service.MessageService;
import org.example.message.vo.AttachmentVO;
import org.example.message.vo.ConversationVO;
import org.example.message.vo.MessageVO;
import org.example.message.vo.SendMessageVO;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;

/**
 * 消息接口（经网关 {@code /api/message/**} 暴露给前端）。
 *
 * <p>路径规律和别的服务一样：网关去掉 {@code /api} 再转发，
 * 所以这里写 {@code /message}，前端写 {@code /api/message}。
 *
 * <p>当前客户端发送及回执主要走 connection 的 WebSocket 通道，转内部 HTTP
 * 调用相同业务。本控制器保留 HTTP 发送兼容入口、附件上传、查询和回执兜底。
 *
 * <p>"我是谁"只认网关注入的 {@code X-User-Id}，前端传不了也伪造不了 ——
 * 所有接口的入参里都只有"对方是谁"。
 */
@RestController
@RequestMapping("/message")
@RequiredArgsConstructor
public class MessageController {

    private final MessageService messageService;
    private final ConversationService conversationService;
    private final AttachmentStorageService attachmentStorageService;

    /**
     * 【上传附件】图片 / 文件先传到这里，拿到一个相对 URL，再用它去发消息。
     *
     * <p><b>为什么是"先上传、再发消息"两步</b>：
     * 发消息那条链路的语义是"一条内容 + 幂等 id + 受理结果"，它不需要为"带文件"做特例。
     * 把附件变成"内容是一段 URL 的普通消息"之后，幂等重发、服务端重推、上线补拉、
     * 未读数统计全都不用改一行。
     *
     * <p><b>为什么消息类型由服务端定</b>（不看客户端说什么）：只有服务端知道
     * 落盘时的文件头长什么样 —— 客户端说是图片、实际是别的东西时，这里会把它判成文件。
     *
     * <p>上传必须是登录用户（网关已经验过 token），否则任何人都能往我们磁盘上写东西。
     *
     * @param file multipart 里的 {@code file} 部分；上限见 {@code spring.servlet.multipart}
     *             （50MB）与 {@code chat.upload.max-attachment-bytes}
     * @return 相对 URL + 原始文件名 + 字节数 + 服务端判定的消息类型
     */
    @PostMapping(value = "/upload", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public Result<AttachmentVO> upload(
            @RequestHeader(value = AuthHeaders.USER_ID, required = false) Long userId,
            @RequestParam("file") MultipartFile file) {
        RequestUser.requireId(userId);   // 没登录就别往磁盘上写
        return Result.success(attachmentStorageService.store(file));
    }

    /**
     * 【会话列表】消息页用：每个会话一行 —— 对方是谁、最后一条的摘要、未读数、回执水位。
     *
     * <p><b>它为什么和"打开会话"无关</b>：这是"一屏列出所有会话"用的接口，
     * 一次返回全部（默认 50、上限 200）。打开某个会话看记录走的是另外的路
     * （客户端本地库 + 补拉），不需要调它。
     *
     * <p><b>它顺带解决了两件事</b>：
     * <ol>
     *   <li><b>未读数的权威值</b>：客户端本地也能算一个，但只覆盖它留存的那几条
     *       （本地每个会话最多 500 条），而且换台机器就没有了；这里按
     *       {@code conversation_member.last_read_message_id} 算，是准的。</li>
     *   <li><b>"我发的消息被对方读到哪了"的对账</b>：送达/已读回执是一次性 WS 推送，
     *       我离线期间对方读了的那些永远推不到我这来 —— 响应里的
     *       {@code deliveredUpTo / readUpTo} 就是那份真相，客户端拿它推进本地水位就行，
     *       不用为此再拉一遍消息。</li>
     * </ol>
     *
     * <p>不含任何具体消息，也不改任何状态（纯读）。
     *
     * @param size 最多返回多少个会话；不传默认 50
     */
    @GetMapping("/conversations")
    public Result<List<ConversationVO>> conversations(
            @RequestHeader(value = AuthHeaders.USER_ID, required = false) Long userId,
            @RequestParam(value = "size", required = false) Integer size) {
        return Result.success(conversationService.listForUser(RequestUser.requireId(userId), size));
    }

    /**
     * 【发消息】。
     *
     * <p>请求体里必须带 {@code clientMessageId}（前端生成的 UUID）：
     * 客户端没收到受理 ack 时会重发同一条，服务端靠它保证<b>幂等</b>（不会落两条）。
     * 重复提交不会报错，而是返回<b>已经存在的那条消息</b>（{@code duplicated=true}）。
     */
    @PostMapping("/send")
    public Result<SendMessageVO> send(
            @RequestHeader(value = AuthHeaders.USER_ID, required = false) Long userId,
            @Valid @RequestBody SendMessageRequest req) {
        return Result.success(messageService.send(RequestUser.requireId(userId), req));
    }

    /**
     * 【拉历史消息】。
     *
     * <p>按<b>对方 userId</b> 定位会话（不是 conversationId）：聊天页的路由就是按人来的，
     * 而且还没聊过天的时候不该为了看一眼就建一个空会话。
     *
     * @param beforeId 翻页锚点，只取比它更早的；第一次不传 = 最新的一批
     * @param size     一次拉多少条；不传默认 20，上限由服务端配置夹住
     * @return 消息按时间<b>从旧到新</b>（界面从上往下就是时间顺序）
     */
    @GetMapping("/history")
    public Result<List<MessageVO>> history(
            @RequestHeader(value = AuthHeaders.USER_ID, required = false) Long userId,
            @RequestParam("peerUserId") Long peerUserId,
            @RequestParam(value = "beforeId", required = false) Long beforeId,
            @RequestParam(value = "size", required = false) Integer size) {
        return Result.success(
                messageService.history(RequestUser.requireId(userId), peerUserId, beforeId, size));
    }

    /**
     * 【补拉未送达】—— 用户上线/重连时第一件事就调它。
     *
     * <p>这是可靠性的兜底：实时推送失败（对方不在线、网络抖动、超过 3 分钟放弃重试）
     * 都不会让消息丢。返回的消息按 messageId 升序，客户端按顺序重放 + 去重即可。
     */
    @GetMapping("/undelivered")
    public Result<List<MessageVO>> undelivered(
            @RequestHeader(value = AuthHeaders.USER_ID, required = false) Long userId,
            @RequestParam(value = "size", required = false) Integer size) {
        return Result.success(messageService.undelivered(RequestUser.requireId(userId), size));
    }

    /**
     * 【上报已读 —— HTTP 兜底版】我读到哪条消息了。
     *
     * <p><b>正常路径现在是 WS 帧</b> {@code {type:'read-report', peerUserId, upToMessageId}}
     * （见 {@code ChatWebSocketHandler#handleReadReport}）：已读上报是纯通知、不要返回值、
     * 天然幂等，和"送达 ack"是同一类事，所以它和 ack 一样走实时通道。
     *
     * <p>这个 HTTP 接口是<b>兜底</b>，用于三种情况：纯浏览器（没有 WS）、WS 还没连上、
     * 发帧失败。两条路走的是同一个 {@link MessageService#markRead}：
     * 服务端取 max(已有值, 这次传的值)，所以乱序上报不会把游标倒退回去，重复上报也无害。
     */
    @PostMapping("/read")
    public Result<Void> read(
            @RequestHeader(value = AuthHeaders.USER_ID, required = false) Long userId,
            @Valid @RequestBody ReadMessageRequest req) {
        messageService.markRead(RequestUser.requireId(userId), req);
        return Result.success();
    }

    /**
     * 【送达确认（HTTP 版）】。
     *
     * <p>正常情况下客户端在 WebSocket 上回 ack —— 更快，而且身份天然绑在那条连接上。
     * 但 WebSocket 只建在 Electron 主进程里（浏览器不能自定义握手请求头），
     * 纯浏览器跑（{@code npm run dev}）时客户端<b>一次 ack 都发不出去</b>，
     * 发送方就永远看不到"已送达"，直到接收方碰巧打开那个会话（已读会把送达一起补上）。
     *
     * <p>这个接口就是那条兜底路径：只要 HTTP 通，送达确认就能落地。
     * 和 WS 的 ack 走的是同一个 {@link MessageService#markDelivered}：
     * <b>幂等</b>，重复确认不会把 {@code delivered_at} 覆盖成更晚的时间
     * （最多是给发送方多发一次回执，客户端那边"只升不降"，没有副作用）。
     */
    @PostMapping("/delivered")
    public Result<Void> delivered(
            @RequestHeader(value = AuthHeaders.USER_ID, required = false) Long userId,
            @Valid @RequestBody DeliveredAckRequest req) {
        messageService.markDelivered(RequestUser.requireId(userId), req.messageIds());
        return Result.success();
    }

    /*
     * 这里以前有一个私有方法 requireUserId(Long)：从网关注入的 X-User-Id 取当前用户，
     * 取不到就 401。它和另外三个 controller 里的那份逐字相同，现在统一走
     * chat-common 的 RequestUser#requireId（纯 Java，不会把 spring-web 拖给 WebFlux 的网关）。
     */
}
