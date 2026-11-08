package org.example.message.dto;

import java.util.List;

import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Size;

/**
 * 送达确认（HTTP 版）。
 *
 * <p><b>为什么 WS 上已经有 ack 了，还要一个 HTTP 接口？</b>
 * 因为 WebSocket 只在 Electron 主进程里有（浏览器不能自定义握手请求头，见 chat-socket.cjs）。
 * 纯浏览器里跑（{@code npm run dev}）时客户端<b>根本发不出 ack</b>，于是：
 * 补拉能拿到消息、能读、能回消息，但发送方永远看不到"已送达" ——
 * 直到接收方碰巧打开了那个会话（{@code /message/read} 里的自愈会补上 delivered_at）。
 *
 * <p>这个接口就是那条兜底路径：只要 HTTP 通，送达确认就能落地。
 * 两条路都指向同一个 {@code MessageService.markDelivered}，服务端是幂等的，
 * 所以"WS 发了、HTTP 又发一遍"完全无害。
 *
 * <p><b>身份同样只认网关注入的 {@code X-User-Id}</b>：请求体里没有 userId，
 * 而且 {@code markDelivered} 只会更新 {@code receiver_user_id = 我自己} 的收据 ——
 * 谁也没法替别人确认送达。
 *
 * @param messageIds 要确认送达的消息 id；一次别传太多（客户端按页回，一页 50 条）
 */
public record DeliveredAckRequest(

        @NotEmpty(message = "messageIds 不能为空")
        @Size(max = 200, message = "一次最多确认 200 条")
        List<Long> messageIds
) {
}
