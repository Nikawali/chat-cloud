package org.example.relation.service;

import java.util.List;

import org.example.relation.dto.AddFriendRequest;
import org.example.relation.vo.FriendRequestVO;
import org.example.relation.vo.FriendVO;
/**
 * 好友关系业务接口。
 *
 * <p>规矩和别的服务一样：controller 只做参数校验 + 包 {@code Result}，
 * 业务规则（谁能不能加谁、状态怎么流转）全在这里，失败一律抛 {@code BizException}。
 *
 * <p><b>每个方法的第一个参数都是 {@code myUserId}</b>：它是网关注入的
 * {@code X-User-Id}（"我是谁"），绝不是前端传的。所有"只能操作自己的数据"的校验
 * 都基于它 —— 这是这个服务里唯一的安全边界。
 */
public interface FriendService {

    /**
     * 发起好友申请。几种情况都有明确结果，不会"默默成功"：
     * <ul>
     *   <li>加自己 → 400</li>
     *   <li>对方不存在 → 404（先问 chat-user 确认）</li>
     *   <li>已经是好友 → 409</li>
     *   <li>已经申请过、还在等 → 409</li>
     *   <li><b>对方也申请过我 → 直接互相成为好友</b>（不用再互相点同意）</li>
     *   <li>之前被拒绝过 → 允许重新申请（把老那行改回待处理）</li>
     * </ul>
     */
    void addFriend(Long myUserId, AddFriendRequest req);

    /**
     * 「我和他是什么关系」—— 加好友页据此决定按钮显示什么。
     *
     * @return {@code NONE / PENDING_OUT / PENDING_IN / FRIEND / SELF}，见 {@code RelationStatus}
     */
    String relationStatus(Long myUserId, Long targetUserId);

    /** 别人申请加我的、还没处理的（"新的朋友"页） */
    List<FriendRequestVO> listIncomingRequests(Long myUserId);

    /**
     * 处理一条申请。
     *
     * @param accept true = 同意（同时写入双向好友关系），false = 拒绝
     */
    void handleRequest(Long myUserId, Long requestId, boolean accept);

    /**
     * 查单个好友（用户卡片 + 我给他的备注）。
     *
     * <p>聊天页要它：标题要显示"备注，没有就显示昵称"，而这个备注只有本服务知道。
     * 没有这个接口的话，前端为了一个人的备注得把整个好友列表拉下来。
     *
     * @throws org.example.common.exception.BizException 404 —— 你们还不是好友
     */
    FriendVO getFriend(Long myUserId, Long friendId);

    /**
     * 判断两个人是不是好友（<b>服务间</b>用：发消息前校验"只能给好友发消息"）。
     *
     * <p>和 {@link #relationStatus} 的区别：那个是给前端页面的（要五种状态），
     * 这个只回答一个是非题，给别的服务用，不需要身份上下文之外的东西。
     */
    boolean areFriends(Long userId, Long otherUserId);

    /**
     * 我的好友列表。
     *
     * <p>返回的顺序只是"稳定的"，<b>最终排序（按备注首字母分组）在前端做</b> ——
     * 因为前端已经有中文排序（{@code Intl.Collator}）和分组的逻辑，
     * 后端再排一遍只会出现两套规则、早晚不一致。
     */
    List<FriendVO> listFriends(Long myUserId);

    /**
     * 改备注（只影响"我"这一行）。
     *
     * @param remark 传 null / 空串 = 清除备注
     */
    void updateRemark(Long myUserId, Long friendId, String remark);

    /**
     * 删好友（双向的两行一起删）。
     *
     * <p>删掉之后想再加回来，需要<b>重新申请、对方同意</b>（关系没了，申请记录还在，
     * 会被改回待处理状态）。
     */
    void deleteFriend(Long myUserId, Long friendId);
}
