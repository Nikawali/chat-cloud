package org.example.common.dto;

import java.util.List;

/**
 * 批量查用户资料的请求体（chat-relation → chat-user 的内部契约）。
 *
 * <p><b>为什么要有"批量"这个接口？</b> 好友列表是这样的场景：
 * 关系表里只存了 userId，要显示昵称/头像就得问 chat-user。
 * 如果一个个问，20 个好友就是 <b>20 次网络往返</b>（这就是经典的 N+1 问题）——
 * 慢不说，chat-user 那边还要处理 20 个请求。
 * 一次把 id 列表发过去、一次把资料拿回来，是最省的做法。
 *
 * <p>用 POST + 请求体而不是 {@code GET /batch?ids=1,2,3}：
 * URL 长度有限制（好友多了会超），而且 id 列表放进 body 更自然。
 *
 * @param userIds 要查的 userId 列表；可以传空列表（返回空结果，不会报错）
 */
public record UserBatchRequest(List<Long> userIds) {
}
