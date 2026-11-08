package org.example.relation.service.impl;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.example.common.dto.UserBatchRequest;
import org.example.common.dto.UserProfileDTO;
import org.example.common.exception.BizException;
import org.example.common.result.Result;
import org.example.common.support.Strings;
import org.example.relation.client.UserClient;
import org.example.relation.constant.FriendRequestStatus;
import org.example.relation.constant.RelationStatus;
import org.example.relation.dto.AddFriendRequest;
import org.example.relation.entity.FriendEntity;
import org.example.relation.entity.FriendRequestEntity;
import org.example.relation.mapper.FriendMapper;
import org.example.relation.mapper.FriendRequestMapper;
import org.example.relation.service.FriendService;
import org.example.relation.vo.FriendRequestVO;
import org.example.relation.vo.FriendVO;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;

import feign.FeignException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * 好友关系实现。
 *
 * <p>三个容易踩的点，代码里都处理了：
 * <ol>
 *   <li><b>双向两行必须一起写/一起删</b> —— 两条 INSERT（或两条 DELETE）要么都成功要么都不做，
 *       否则会出现"我列表里有他、他列表里没我"的鬼状态。实现方式两处不同，是刻意的：
 *       {@link #deleteFriend} 用 {@code @Transactional}；{@link #addFriend} 用
 *       {@code TransactionTemplate} —— 因为它中间有一次跨服务调用，而那一步必须留在事务外
 *       （不能让数据库连接陪着等网络，理由见那个方法的注释）。</li>
 *   <li><b>"谁有权限操作"永远是 {@code myUserId} 说了算</b> —— 它来自网关注入的请求头。
 *       同意申请时必须校验 {@code toUserId == myUserId}，不然任何人都能替别人同意。</li>
 *   <li><b>并发</b>：两个请求同时同意同一条申请 → 用带条件（{@code status = PENDING}）
 *       的 UPDATE 做兜底，只有一个人能改成功；同时关系表上的唯一索引保证
 *       不会插出重复关系。</li>
 * </ol>
 *
 * <p>另一个约定：<b>本服务不存用户资料</b>。需要昵称/头像时用 Feign 问 chat-user，
 * 而且拼列表时必须用<b>批量</b>接口（一次 IN 查询），一个个问就是 N+1。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class FriendServiceImpl implements FriendService {

    private final FriendMapper friendMapper;
    private final FriendRequestMapper friendRequestMapper;
    private final UserClient userClient;
    private final TransactionTemplate transactionTemplate;

    // ==================== 发起申请 ====================

    /**
     * 发起好友申请（或"互相申请直接成为好友"）。
     *
     * <p><b>为什么这里用 {@link TransactionTemplate} 而不是 {@code @Transactional}？</b>
     * 因为这个方法中间有一次<b>跨服务调用</b>（{@code fetchProfile} 校验对方存在），
     * 而那次调用必须在事务<b>外面</b>：
     * <pre>
     *   一旦包在事务里，一个数据库连接就要陪着等网络 ——
     *   Feign 配的是 connect 3s + read 5s，最长 8 秒；而 Hikari 池只有 10 个连接。
     *   10 个并发"加好友"（对方 chat-user 正慢）就能把连接池抽干，
     *   本服务其它接口全部排队 —— 一次上游变慢放大成整服务不可用。
     * </pre>
     * 这和 chat-message 里"事务提交之后再推送"是同一条纪律：<b>事务里只做数据库操作</b>。
     * （{@link #deleteFriend} 里没有网络调用，所以那边继续用 {@code @Transactional} 就好。）
     */
    @Override
    public void addFriend(Long myUserId, AddFriendRequest req) {
        Long toUserId = req.toUserId();

        // ① 加自己没有任何意义，直接拦掉（前端也会拦，但后端不能依赖前端）
        if (myUserId.equals(toUserId)) {
            throw new BizException(400, "不能添加自己为好友");
        }

        // ② 对方必须真实存在。这一步同时承担了"验证 chat-user 还活着"的作用：
        //    如果 chat-user 挂了，这里会抛 503，而不是插一条指向不存在用户的关系。
        //    ⚠️ 它是跨服务调用，所以待在事务外面（原因见上面的方法注释）
        fetchProfile(toUserId);

        // ③ 剩下的都是数据库操作，放进一个事务里 ——
        //    "互相申请"那条路要同时写两行好友关系，必须一起成功或一起不做
        transactionTemplate.executeWithoutResult(status -> doAddFriend(myUserId, toUserId, req));
    }

    /** {@link #addFriend} 的数据库部分（在事务里跑）；拆出来只是为了让那次 Feign 留在事务外 */
    private void doAddFriend(Long myUserId, Long toUserId, AddFriendRequest req) {
        // ④ 已经是好友 → 明确告诉用户（不做"默默成功"，那种假成功最难查）
        if (isFriend(myUserId, toUserId)) {
            throw new BizException(409, "你们已经是好友了");
        }

        // ⑤ 对方已经申请过我 → 互相申请 = 直接成为好友。
        //    真实软件都这么做：否则会留下两条互相等待的申请，谁也不知道该点同意
        FriendRequestEntity reverse = findRequest(toUserId, myUserId);
        if (reverse != null && FriendRequestStatus.PENDING.equals(reverse.getStatus())) {
            acceptRequest(reverse, myUserId);
            log.info("互相申请，直接成为好友: {} <-> {}", myUserId, toUserId);
            return;
        }

        // ⑥ 我之前申请过
        FriendRequestEntity mine = findRequest(myUserId, toUserId);
        if (mine != null) {
            if (FriendRequestStatus.PENDING.equals(mine.getStatus())) {
                throw new BizException(409, "已发送申请，等待对方验证");
            }
            // 被拒绝过（或曾经是好友、后来删了）→ 允许重新申请。
            //
            // 注意这里【只能用带 set 的 update】，不能先 set 再 updateById：
            // MyBatis-Plus 的 updateById 会跳过 null 字段，而我们要把 handled_at
            // 从"有值"改回 null（这次还没被处理）—— 用 updateById 这句会被跳过，
            // 数据库里就留着上次的处理时间了。
            friendRequestMapper.update(null, Wrappers.<FriendRequestEntity>lambdaUpdate()
                    .eq(FriendRequestEntity::getFriendRequestId, mine.getFriendRequestId())
                    .set(FriendRequestEntity::getStatus, FriendRequestStatus.PENDING)
                    .set(FriendRequestEntity::getMessage, Strings.trimToNull(req.message()))
                    .set(FriendRequestEntity::getHandledAt, null));
            log.info("重新发起好友申请: {} -> {}", myUserId, toUserId);
            return;
        }

        // ⑦ 全新的申请
        FriendRequestEntity entity = new FriendRequestEntity();
        entity.setFromUserId(myUserId);
        entity.setToUserId(toUserId);
        entity.setMessage(Strings.trimToNull(req.message()));
        entity.setStatus(FriendRequestStatus.PENDING);
        friendRequestMapper.insert(entity);
        log.info("发起好友申请: {} -> {}", myUserId, toUserId);
    }

    // ==================== 关系状态 ====================

    @Override
    public String relationStatus(Long myUserId, Long targetUserId) {
        if (targetUserId == null) {
            throw new BizException(400, "对方 userId 不能为空");
        }
        if (myUserId.equals(targetUserId)) {
            return RelationStatus.SELF;
        }
        if (isFriend(myUserId, targetUserId)) {
            return RelationStatus.FRIEND;
        }
        FriendRequestEntity out = findRequest(myUserId, targetUserId);
        if (out != null && FriendRequestStatus.PENDING.equals(out.getStatus())) {
            return RelationStatus.PENDING_OUT;
        }
        FriendRequestEntity in = findRequest(targetUserId, myUserId);
        if (in != null && FriendRequestStatus.PENDING.equals(in.getStatus())) {
            return RelationStatus.PENDING_IN;
        }
        return RelationStatus.NONE;
    }

    // ==================== 新的朋友 ====================

    @Override
    public List<FriendRequestVO> listIncomingRequests(Long myUserId) {
        List<FriendRequestEntity> requests = friendRequestMapper.selectList(
                Wrappers.<FriendRequestEntity>lambdaQuery()
                        .eq(FriendRequestEntity::getToUserId, myUserId)
                        .eq(FriendRequestEntity::getStatus, FriendRequestStatus.PENDING)
                        // 最近的排最上（被拒后重新申请会刷新 updated_at）
                        .orderByDesc(FriendRequestEntity::getUpdatedAt));
        if (requests.isEmpty()) {
            return List.of();
        }
        Map<Long, UserProfileDTO> profiles = fetchProfiles(
                requests.stream().map(FriendRequestEntity::getFromUserId).toList());
        return requests.stream()
                .map(request -> FriendRequestVO.of(request, profiles.get(request.getFromUserId())))
                .toList();
    }

    /**
     * 处理一条申请（同意 / 拒绝）。
     *
     * <p><b>为什么这里需要 {@code @Transactional}</b>：同意那条路要写
     * <b>双向两行关系 + 一条申请状态</b>（见 {@link #acceptRequest}），三件事必须一起成功。
     * 不放在一个事务里的话，两条 INSERT 之间任何异常都会留下
     * <b>单向好友</b> —— 界面上显示"已是好友"，而对方那边没有你，消息也发不出去
     * （{@code areFriends} 两边都查）。
     *
     * <p>这个注解能生效，是因为它由 controller 经代理调用（不是同类内部自调用 ——
     * 那种情况下 {@code @Transactional} 会静默失效，项目里 ConversationServiceImpl 的注释专门记过这个坑）。
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public void handleRequest(Long myUserId, Long requestId, boolean accept) {
        FriendRequestEntity request = friendRequestMapper.selectById(requestId);
        if (request == null) {
            throw new BizException(404, "申请不存在或已被撤销");
        }
        if (!request.getToUserId().equals(myUserId)) {
            // 只有被申请人能处理。换个说法：别人收到的申请，你不能替他同意
            throw new BizException(403, "只有被申请人能处理这条申请");
        }

        if (accept) {
            acceptRequest(request, myUserId);
            log.info("同意好友申请: requestId={}, {} <-> {}", requestId, myUserId, request.getFromUserId());
        } else {
            int rows = friendRequestMapper.update(null, Wrappers.<FriendRequestEntity>lambdaUpdate()
                    .eq(FriendRequestEntity::getFriendRequestId, requestId)
                    // 条件里再卡一次状态：并发的两个请求只有一个能改成功
                    .eq(FriendRequestEntity::getStatus, FriendRequestStatus.PENDING)
                    .set(FriendRequestEntity::getStatus, FriendRequestStatus.REJECTED)
                    .set(FriendRequestEntity::getHandledAt, LocalDateTime.now()));
            if (rows == 0) {
                throw new BizException(409, "这条申请已经处理过了");
            }
            log.info("拒绝好友申请: requestId={}, {} -> {}", requestId, request.getFromUserId(), myUserId);
        }
    }

    /**
     * 同意申请：<b>写双向关系 + 改申请状态</b>，三件事必须在同一个事务里。
     *
     * <p>抽成方法是因为有两个入口会走到这里：被申请人点同意、以及"互相申请"。
     */
    private void acceptRequest(FriendRequestEntity request, Long myUserId) {
        if (!request.getToUserId().equals(myUserId)) {
            throw new BizException(403, "只有被申请人能同意这条申请");
        }
        if (!FriendRequestStatus.PENDING.equals(request.getStatus())) {
            throw new BizException(409, "这条申请已经处理过了");
        }

        // ① 双向两行（哪一行都不能少，少一行就是"单向好友"）
        insertFriendRowIfAbsent(myUserId, request.getFromUserId());
        insertFriendRowIfAbsent(request.getFromUserId(), myUserId);

        // ② 状态改成已同意。带 status = PENDING 条件 = 乐观锁，
        //    保证"同一时刻只有一个人能同意成功"
        int rows = friendRequestMapper.update(null, Wrappers.<FriendRequestEntity>lambdaUpdate()
                .eq(FriendRequestEntity::getFriendRequestId, request.getFriendRequestId())
                .eq(FriendRequestEntity::getStatus, FriendRequestStatus.PENDING)
                .set(FriendRequestEntity::getStatus, FriendRequestStatus.ACCEPTED)
                .set(FriendRequestEntity::getHandledAt, LocalDateTime.now()));
        if (rows == 0) {
            throw new BizException(409, "这条申请刚被处理过了");
        }
    }

    // ==================== 单个好友 / 服务间校验 ====================

    @Override
    public FriendVO getFriend(Long myUserId, Long friendId) {
        if (friendId == null) {
            throw new BizException(400, "好友 userId 不能为空");
        }
        FriendEntity relation = findRelation(myUserId, friendId);
        if (relation == null) {
            throw new BizException(404, "你们还不是好友");
        }
        // 单个查用 getById（走 chat-user 的缓存），比批量接口更合适
        return FriendVO.of(relation, fetchProfileOrNull(friendId));
    }

    @Override
    public boolean areFriends(Long userId, Long otherUserId) {
        if (userId == null || otherUserId == null) {
            return false;
        }
        // 关系是双向写的，但为了不依赖"写的时候没漏"，两边都查一下更稳
        // （代价是两次索引查询，对发消息这种低频操作完全可以接受）
        return isFriend(userId, otherUserId) && isFriend(otherUserId, userId);
    }

    // ==================== 好友列表 / 备注 / 删除 ====================

    @Override
    public List<FriendVO> listFriends(Long myUserId) {
        List<FriendEntity> relations = friendMapper.selectList(
                Wrappers.<FriendEntity>lambdaQuery()
                        .eq(FriendEntity::getUserId, myUserId)
                        // 顺序先按 id 稳定下来，真正的排序/分组由前端做（见接口注释）
                        .orderByAsc(FriendEntity::getFriendRelationId));
        if (relations.isEmpty()) {
            return List.of();
        }
        Map<Long, UserProfileDTO> profiles = fetchProfiles(
                relations.stream().map(FriendEntity::getFriendId).toList());
        return relations.stream()
                .map(relation -> FriendVO.of(relation, profiles.get(relation.getFriendId())))
                .toList();
    }

    @Override
    public void updateRemark(Long myUserId, Long friendId, String remark) {
        if (friendId == null) {
            throw new BizException(400, "好友 userId 不能为空");
        }
        // 先确认"他确实是我的好友"，这样能给出明确的 404；
        // 真正的安全边界是下面 SQL 里的 user_id 条件（只改自己那一行）
        if (!isFriend(myUserId, friendId)) {
            throw new BizException(404, "你们还不是好友");
        }
        friendMapper.update(null, Wrappers.<FriendEntity>lambdaUpdate()
                .eq(FriendEntity::getUserId, myUserId)
                .eq(FriendEntity::getFriendId, friendId)
                // 空串 = 清除备注（set 成 null，显示时就会退回对方昵称）
                .set(FriendEntity::getRemark, Strings.trimToNull(remark)));
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void deleteFriend(Long myUserId, Long friendId) {
        if (friendId == null) {
            throw new BizException(400, "好友 userId 不能为空");
        }
        if (!isFriend(myUserId, friendId)) {
            throw new BizException(404, "你们还不是好友");
        }
        // 两行一起删。为什么不用一条 or 条件？两条意图更清楚，而且都能吃到唯一索引
        friendMapper.delete(Wrappers.<FriendEntity>lambdaQuery()
                .eq(FriendEntity::getUserId, myUserId)
                .eq(FriendEntity::getFriendId, friendId));
        friendMapper.delete(Wrappers.<FriendEntity>lambdaQuery()
                .eq(FriendEntity::getUserId, friendId)
                .eq(FriendEntity::getFriendId, myUserId));
        log.info("删除好友: {} <-> {}", myUserId, friendId);
    }

    // ==================== 内部小工具 ====================

    /** 这两人是不是已经是好友（查"我这一行"就够了，因为关系是双向写的） */
    private boolean isFriend(Long userId, Long friendId) {
        Long count = friendMapper.selectCount(Wrappers.<FriendEntity>lambdaQuery()
                .eq(FriendEntity::getUserId, userId)
                .eq(FriendEntity::getFriendId, friendId));
        return count != null && count > 0;
    }

    /** 取"我这一行"的关系记录；不是好友返回 null（和 isFriend 的区别是它要拿到 remark） */
    private FriendEntity findRelation(Long userId, Long friendId) {
        return friendMapper.selectOne(Wrappers.<FriendEntity>lambdaQuery()
                .eq(FriendEntity::getUserId, userId)
                .eq(FriendEntity::getFriendId, friendId));
    }

    /** 查某个人发给另一个人的申请（表上有唯一索引，最多一行） */
    private FriendRequestEntity findRequest(Long fromUserId, Long toUserId) {
        return friendRequestMapper.selectOne(Wrappers.<FriendRequestEntity>lambdaQuery()
                .eq(FriendRequestEntity::getFromUserId, fromUserId)
                .eq(FriendRequestEntity::getToUserId, toUserId));
    }

    /**
     * 插入一行好友关系（已经有了就跳过）。
     *
     * <p>先查再插是为了<b>幂等</b>：同意申请时可能出现"关系其实已经存在"的情况
     * （比如重复点了同意）。真正的并发兜底是表上的唯一索引
     * {@code uk_user_friend} —— 真撞上了数据库会拦下来，事务整体回滚，不会出现半条关系。
     */
    private void insertFriendRowIfAbsent(Long userId, Long friendId) {
        if (isFriend(userId, friendId)) {
            return;
        }
        FriendEntity entity = new FriendEntity();
        entity.setUserId(userId);
        entity.setFriendId(friendId);
        friendMapper.insert(entity);
    }

    /** 单个查用户资料；查不到抛 404 */
    private UserProfileDTO fetchProfile(Long userId) {
        Result<UserProfileDTO> result;
        try {
            result = userClient.getById(userId);
        } catch (FeignException e) {
            // 只接 FeignException（传输层失败的公共父类），不接 Exception：
            // 完整理由见 chat-message 的 MessageServiceImpl#areFriends 上方那段注释。
            // 一句话：写宽了会把我们自己的 Bug 报成"用户服务不可用"。
            log.error("调用 chat-user 查用户失败: userId={}", userId, e);
            throw new BizException(503, "用户服务暂时不可用，请稍后重试");
        }
        // 注意这里是 404 而不是 503：调用成功、只是"这个人不存在"，属于业务结论
        if (result == null || !result.isSuccess() || result.getData() == null) {
            throw new BizException(404, "用户不存在: " + userId);
        }
        return result.getData();
    }

    /**
     * 单个查用户资料，<b>查不到就返回 null</b>（不抛异常）。
     *
     * <p>和 {@link #fetchProfile} 的区别是用途不同：那个用于"必须存在"的场景
     * （比如加好友前校验对方存在）；这个用于"拼展示数据"——
     * 对方资料查不到，我至少还能把 userId 和备注返回给他，不该整个接口报错。
     */
    private UserProfileDTO fetchProfileOrNull(Long userId) {
        try {
            Result<UserProfileDTO> result = userClient.getById(userId);
            if (result == null || !result.isSuccess()) {
                return null;
            }
            return result.getData();
        } catch (Exception e) {
            // ⚠️ 这里【故意】保持 catch (Exception)，和上面 fetchProfile 收窄成 FeignException
            //    是有意的不一致：那个的契约是"查不到就报错"，所以要把"我们的 Bug"和
            //    "依赖挂了"分开；这个的契约是"<b>永不失败</b>，最多少个昵称"，
            //    所以任何异常都该降级——写窄了反而会让一个意外异常把整个好友列表打崩。
            log.warn("查用户资料失败，返回时缺少昵称头像: userId={}", userId, e);
            return null;
        }
    }

    /**
     * <b>批量</b>查用户资料，返回 userId → 资料的 Map。
     *
     * <p>为什么必须批量：好友列表有 20 个人就调 20 次 Feign，是典型的 N+1，
     * 又慢又浪费（chat-user 那边要多处理 20 个请求）。
     *
     * <p>查不到的 id 直接不在 Map 里 —— 调用方（拼 VO）会得到名字为空的好友项，
     * <b>而不是让整个列表失败</b>。一个人查不到不该让用户看不到所有好友。
     */
    private Map<Long, UserProfileDTO> fetchProfiles(List<Long> userIds) {
        if (userIds.isEmpty()) {
            return Map.of();
        }
        try {
            Result<List<UserProfileDTO>> result = userClient.batch(new UserBatchRequest(userIds));
            if (result == null || !result.isSuccess() || result.getData() == null) {
                log.warn("批量查用户资料返回异常，好友列表将缺少昵称头像: userIds={}", userIds);
                return Map.of();
            }
            return result.getData().stream()
                    .collect(Collectors.toMap(UserProfileDTO::userId, Function.identity(), (a, b) -> a));
        } catch (Exception e) {
            // 注意这里【故意不抛异常】：查不到昵称头像，好友列表还能显示 userId，
            // 比整个页面报错好得多（和 chat-message 取发送者昵称时"取不到就降级"一个道理）
            // ——也正因为契约是"永不失败"，这里保持 catch (Exception) 不收窄
            //（判据：失败之后是"报错阻断"还是"降级继续"；前者收窄成 FeignException，后者放宽）。
            log.error("批量查用户资料失败: userIds={}", userIds, e);
            return Map.of();
        }
    }
}
