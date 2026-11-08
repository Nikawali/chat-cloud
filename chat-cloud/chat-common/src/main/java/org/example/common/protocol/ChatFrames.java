package org.example.common.protocol;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * WebSocket 帧类型、构造及字段读取约定；JSON 编解码由连接服务负责。
 *
 * <h3>帧格式</h3>
 * 全部是 JSON 文本帧，都有一个 {@code type} 字段：
 * <pre>
 * 服务端 → 客户端：
 *   { "type": "welcome", "userId": 2, "serverTime": "..." }      连接建立后立刻发，客户端可据此确认身份
 *   { "type": "pong",    "userId": 2, "serverTime": "..." }      心跳响应
 *   { "type": "error",   "message": "..." }                      协议错误（不认识的 type、JSON 解析失败）
 *   { "type": "message", "data": { …MessageVO } }                新消息推送
 *   { "type": "delivered", "conversationId": 1, "upToMessageId": 2 }  送达回执
 *   { "type": "read", "conversationId": 1, "upToMessageId": 2 }   已读回执
 *
 * 客户端 → 服务端：
 *   { "type": "ping" }                                           心跳（客户端每 30 秒发一次）
 *   { "type": "ack", "messageIds": [1, 2] }                      收到推送、存好本地之后的确认
 *   { "type": "read-report", "peerUserId": 3, "upToMessageId": 12 }  我已经读到哪条了
 * </pre>
 *
 * <h3>为什么"已读上报"也走 WS，却还留着 {@code POST /message/read}</h3>
 * 已读上报是纯通知语义（不需要返回值、失败可以重发、服务端只取 max 所以天然幂等），
 * 和 {@code ack} 是同一类东西 —— 既然 ack 走 WS，它没理由单独占一条 HTTP 请求。
 * HTTP 那条**必须保留**：纯浏览器里根本没有 WS（收推送的通道建在 Electron 主进程里），
 * 而"聊天时把自己的已读推上去"这件事在浏览器里也得能用。
 * 所以客户端是"WS 优先、发不出去退回 HTTP"，两条路服务端走的是同一段
 * {@code MessageService#markRead} 逻辑，重复上报无害。
 *
 */
public final class ChatFrames {
    public static final String TYPE_SEND_GROUP_MESSAGE = "send-group-message";
    public static final String TYPE_GROUP_SEND_RESULT = "group-send-result";

    /** 帧类型常量（服务端 → 客户端） */
    public static final String TYPE_WELCOME = "welcome";
    public static final String TYPE_PONG = "pong";
    public static final String TYPE_ERROR = "error";
    public static final String TYPE_MESSAGE = "message";
    /** 发送方的业务受理结果，不等于接收方送达回执。 */
    public static final String TYPE_SEND_RESULT = "send-result";
    public static final String TYPE_DELIVERED = "delivered";
    public static final String TYPE_READ = "read";

    /** 帧类型常量（客户端 → 服务端） */
    public static final String TYPE_PING = "ping";
    public static final String TYPE_SEND_MESSAGE = "send-message";
    public static final String TYPE_ACK = "ack";
    /** 已读上报（客户端告诉我读到哪条了）。注意和服务端 → 客户端的 {@link #TYPE_READ} 是两回事 */
    public static final String TYPE_READ_REPORT = "read-report";


    private ChatFrames() {
    }

    /**
     * 起一个帧。
     *
     * <p>参数是"键值对"形式：{@code of("pong", "userId", 2, "serverTime", "...")}。
     * 写成可变参数而不是返回 Map 让人链式 {@code put}：{@code Map.put} 返回的是<b>旧值</b>，
     * 链式调用会得到 null —— 那是个很容易写出来又很难看出来的坑，索性不给这个机会。
     */
    public static Map<String, Object> of(String type, Object... keyValues) {
        Map<String, Object> frame = new LinkedHashMap<>();
        frame.put("type", type);
        for (int i = 0; i + 1 < keyValues.length; i += 2) {
            frame.put(String.valueOf(keyValues[i]), keyValues[i + 1]);
        }
        return frame;
    }

    /** 取帧的 type（没有就返回空串，调用方按"不认识的帧"处理） */
    public static String typeOf(Map<String, Object> frame) {
        Object type = frame.get("type");
        return type == null ? "" : type.toString();
    }

    /**
     * 把帧里的一个数值字段读成 {@code Long}。
     *
     * <p>和 {@link #longList} 是同一个坑：JSON 里的整数反序列化成 {@code Map} 时是
     * <b>Integer</b>，直接 {@code (Long)} 强转会 ClassCastException，所以统一走
     * {@link Number#longValue()} 转一道。
     *
     * @return 字段不存在或不是数字时返回 null（调用方回一个 error 帧，别抛异常把连接搞崩）
     */
    public static Long longOrNull(Object value) {
        return value instanceof Number number ? number.longValue() : null;
    }

    /**
     * 把帧里的一个数组字段读成 {@code List<Long>}。
     *
     * <p><b>注意元素类型是 Integer 不是 Long</b>：JSON 里的 {@code 1} 反序列化成
     * {@code Map} 时是 Integer（实测确认过），直接强转 Long 会 ClassCastException。
     * 所以统一走 {@link Number#longValue()} 转一道 —— 这个小坑不踩一次是不会想到的。
     *
     * @return 字段不存在或不是数组时返回空列表（调用方当作"没什么可确认的"处理，不报错）
     */
    public static java.util.List<Long> longList(Object value) {
        if (!(value instanceof java.util.List<?> list)) {
            return java.util.List.of();
        }
        java.util.List<Long> result = new java.util.ArrayList<>(list.size());
        for (Object item : list) {
            if (item instanceof Number number) {
                result.add(number.longValue());
            }
        }
        return result;
    }
}
