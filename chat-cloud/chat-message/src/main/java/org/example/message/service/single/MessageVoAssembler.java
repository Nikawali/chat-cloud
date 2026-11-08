package org.example.message.service.single;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.example.common.dto.UserBatchRequest;
import org.example.common.dto.UserProfileDTO;
import org.example.common.result.Result;
import org.example.message.client.UserClient;
import org.example.message.entity.MessageEntity;
import org.example.message.entity.MessageReceiptEntity;
import org.example.message.mapper.MessageReceiptMapper;
import org.example.message.vo.MessageVO;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;

import lombok.RequiredArgsConstructor;

/**
 * 把消息实体组装成给前端看的 {@link MessageVO}。
 *
 * <p>抽出来是因为<b>有两个地方要用</b>：HTTP 拉历史/补拉，和 WebSocket 推送（重试任务也要推）。
 * 各写一份的话，"发送者昵称从哪来""送达状态什么时候该带上"这种规则早晚会不一致。
 *
 * <p>这里也是本服务唯一去问 chat-user 要昵称/头像的地方 —— 统一走<b>批量</b>接口，
 * 避免"每条消息一次 Feign"的 N+1。
 */
@Component
@RequiredArgsConstructor
public class MessageVoAssembler {

    private static final Logger log = LoggerFactory.getLogger(MessageVoAssembler.class);

    private final MessageReceiptMapper receiptMapper;
    private final UserClient userClient;

    /**
     * 批量组装。
     *
     * @param myUserId             以谁的视角看（决定"我发的消息"要不要带送达/已读状态）
     * @param peerUserIdForReceipt 只对这个人的收据感兴趣（单聊里的"对方"）；
     *                             传 null 表示不需要状态（比如补拉的都是发给我的消息）
     */
    public List<MessageVO> assemble(Long myUserId, Long peerUserIdForReceipt, List<MessageEntity> messages) {
        if (messages.isEmpty()) {
            return List.of();
        }

        Map<Long, UserProfileDTO> profiles = fetchProfiles(
                messages.stream().map(MessageEntity::getSenderUserId).distinct().toList());

        // "对方何时收到/已读"只对【我发的】消息有意义：
        // 收据是 (消息, 接收者) 一行，我发的消息那条收据的接收者是对方，那才是我要展示的勾
        Map<Long, MessageReceiptEntity> peerReceipts = Map.of();
        if (peerUserIdForReceipt != null) {
            List<Long> myMessageIds = messages.stream()
                    .filter(message -> myUserId.equals(message.getSenderUserId()))
                    .map(MessageEntity::getMessageId)
                    .toList();
            if (!myMessageIds.isEmpty()) {
                peerReceipts = receiptMapper.selectList(Wrappers.<MessageReceiptEntity>lambdaQuery()
                                .in(MessageReceiptEntity::getMessageId, myMessageIds)
                                .eq(MessageReceiptEntity::getReceiverUserId, peerUserIdForReceipt))
                        .stream()
                        .collect(Collectors.toMap(
                                MessageReceiptEntity::getMessageId, Function.identity(), (a, b) -> a));
            }
        }

        List<MessageVO> result = new ArrayList<>(messages.size());
        for (MessageEntity message : messages) {
            UserProfileDTO sender = profiles.get(message.getSenderUserId());
            MessageReceiptEntity receipt = peerReceipts.get(message.getMessageId());
            result.add(MessageVO.of(
                    message,
                    sender == null ? null : sender.nickName(),
                    sender == null ? null : sender.avatarUrl(),
                    receipt == null ? null : receipt.getDeliveredAt(),
                    receipt == null ? null : receipt.getReadAt()));
        }
        return result;
    }

    /**
     * 组装单条（推送用）。
     *
     * <p>以<b>接收者</b>的视角组装，且不带送达/已读状态 ——
     * 那些是"发送方看自己消息"的东西，推给接收者没有意义。
     *
     * <p><b>也刻意不查发送者的昵称/头像</b>：这条路径很热 —— 每条消息推一次，
     * 而服务端收不到 ack 时会按指数退避重推（单条最多约 10 次）。
     * 而接收方客户端渲染"这条是谁发的"用的是它自己本地的 me / peer
     * （见 {@code Chat.vue#toClientMessage}），VO 里的 senderNickName / senderAvatarUrl
     * 它根本不读。为了两个没人看的字段，每次都打一次 chat-user 的批量接口，是纯粹的浪费。
     *
     * <p>{@link #assemble} 仍然为history/补拉批量补充发送者资料。
     */
    public MessageVO assembleOne(Long receiverUserId, MessageEntity message) {
        return MessageVO.of(message, null, null, null, null);
    }

    /**
     * 批量查用户资料。查不到就返回空 Map —— <b>不让整个消息列表因为一个人查不到而失败</b>
     * （界面会退化成只显示 userId）。
     */
    private Map<Long, UserProfileDTO> fetchProfiles(List<Long> userIds) {
        if (userIds.isEmpty()) {
            return Map.of();
        }
        try {
            Result<List<UserProfileDTO>> result = userClient.batch(new UserBatchRequest(userIds));
            if (result == null || !result.isSuccess() || result.getData() == null) {
                log.warn("批量查用户资料返回异常，消息列表将缺少昵称头像: userIds={}", userIds);
                return Map.of();
            }
            return result.getData().stream()
                    .collect(Collectors.toMap(UserProfileDTO::userId, Function.identity(), (a, b) -> a));
        } catch (Exception e) {
            // ⚠️ 这里【故意】保持 catch (Exception)，不学那些"依赖失败就 503"的调用点：
            //    本方法的契约是"<b>永不失败</b>"——查不到昵称头像就让界面退化成只显示 userId，
            //    绝不能让消息列表因为一次 Feign 抖动而整页打不开。
            //    （判据很简单：失败之后是"报错阻断"还是"降级继续"——前者收窄，后者放宽。）
            log.error("批量查用户资料失败: userIds={}", userIds, e);
            return Map.of();
        }
    }
}
