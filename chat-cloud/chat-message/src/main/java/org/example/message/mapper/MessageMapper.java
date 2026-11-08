package org.example.message.mapper;

import java.util.List;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.example.message.entity.MessageEntity;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;

@Mapper
public interface MessageMapper extends BaseMapper<MessageEntity> {

    /**
     * "某个发送者在某会话里、到某条为止"的最大消息 id —— 已读回执用。
     *
     * <p>为什么要写这条 SQL：用户上报"我读到第 500 条"时，需要通知<b>每个发送者</b>
     * "你的消息被读到哪了"。用 Java 干这件事就得把这个范围内的消息全查出来再分组 ——
     * 一个放了几个月没看的会话可能有上万条。交给数据库做聚合，返回的行数
     * 等于"发送者个数"（单聊就 1 行）。
     *
     * <p>列别名故意和 record 的属性名一致（{@code senderUserId} / {@code maxMessageId}），
     * MyBatis 会按名字映射到 record 的构造参数上。
     */
    @Select("""
            select sender_user_id as senderUserId, max(message_id) as maxMessageId
              from message
             where conversation_id = #{conversationId}
               and message_id <= #{upToMessageId}
               and sender_user_id <> #{myUserId}
             group by sender_user_id
            """)
    List<SenderMaxMessageId> selectMaxMessageIdBySender(@Param("conversationId") Long conversationId,
                                                       @Param("upToMessageId") Long upToMessageId,
                                                       @Param("myUserId") Long myUserId);

    /** 上面的聚合结果：某个发送者 + 他"被读到"的最大消息 id */
    record SenderMaxMessageId(Long senderUserId, Long maxMessageId) {
    }
}
