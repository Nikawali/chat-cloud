package org.example.message.mapper;
import org.example.message.service.single.MessageVoAssembler;

import java.util.List;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.example.message.entity.MessageReceiptEntity;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;

/**
 * 消息收据的数据访问层。
 *
 * <p>复合主键 (message_id, receiver_user_id)，所以也用不了 {@code selectById}。
 * "我有哪些消息还没送到"就是在这里查：{@code receiver_user_id = 我 and delivered_at is null}，
 * 走 {@code idx_receipt_receiver_delivered}（实测：这条查询优化器会选它，
 * {@code type=ref} 且 {@code ref=const,const}）。
 *
 * <p>关于这个索引容易说错的两点，这里写清楚，免得以后照着错的结论去"优化"：
 * <ol>
 *   <li><b>不会 filesort</b>：索引第三列就是 {@code message_id}，而等值条件把
 *       {@code (receiver_user_id, delivered_at)} 都固定住了，所以区间内天然按 message_id 有序，
 *       {@code order by message_id} 直接吃索引顺序。</li>
 *   <li><b>但【不是】覆盖索引、是要回表的</b>：实测同一条件下列全列时 {@code Extra=Using index condition}，
 *       只列 {@code message_id} 时才是 {@code Using index}。而 MyBatis-Plus 查的是整个实体
 *       （conversation_id / read_at / delivery_status 都要），所以每条命中都要按主键回表。
 *       这条注释以前写的是"覆盖索引 = 不回表"，是错的。</li>
 * </ol>
 *
 * <p>（这条注释以前写的是 {@code idx_receipt_receiver_status} —— 那个索引是在建表时留下的，
 * 代码里没有查询用得到它。指名要小心：注释里的索引名和人名一样，写错了会让人查半天。）
 */
@Mapper
public interface MessageReceiptMapper extends BaseMapper<MessageReceiptEntity> {

    /**
     * 一次查出多个会话的<b>回执水位</b>：我发的消息在这些会话里分别被送到/读到哪条为止。
     *
     * <p><b>⚠️ 这里最容易写错的是"查谁的收据"。</b> 收据一行是 {@code (message_id, receiver_user_id)}，
     * 我发的消息那条收据的 {@code receiver_user_id} 是<b>对方</b> —— 所以条件必须是
     * "收据的接收者 = 对方"，不能图省事写成 {@code receiver_user_id = 我}。
     * 后者查出来的是"<b>别人发给我的</b>消息被我送到/读到哪了"，方向和要的正好相反
     * （那个值对我们一点用都没有：它记的是我自己的行为）。
     * 同一个坑在 {@code MessageVoAssembler#assemble} 里也差点踩到 ——
     * 那边之所以是对的，是因为它把 {@code receiver_user_id} 显式绑成了 {@code peerUserId}。
     * 两处要表达的是同一件事，写法上也应该一眼看得出是同一件事。
     *
     * <p><b>为什么要传一个 receiverUserIds 列表</b>：单聊里每个会话的对方都不一样，
     * 而索引 {@code idx_receipt_receiver_conv (receiver_user_id, conversation_id)} 的<b>第一列</b>
     * 是 receiver_user_id。只写 {@code conversation_id in (...)} 的话这个索引整条都用不上
     * （表上没有以 conversation_id 打头的索引）→ 全表扫收据再回表，比 N+1 还糟。
     * 写成 {@code receiver_user_id in (...) and conversation_id in (...)} 才能用上索引前缀。
     * （实测：{@code EXPLAIN FORMAT=TREE} + {@code FORCE INDEX} 下，
     * 范围优化器会把这种写法展开成 36 个 {@code (receiver_user_id, conversation_id)} 单点区间
     * —— "36" 就是 6 个会话 × 6 个对方的组合数。表里行少时优化器仍会选全表扫，
     * 那是它算出来的成本更低，不是索引用不上。）
     *
     * <p>正确性：单聊里一个会话只有两个人，会话内的收据要么收件人是我、要么是对方，
     * 所以"按 (对方, 会话) 取最大值"得到的正好是"我发出去的消息被送到/读到哪条"。
     *
     * <p>⚠️ {@code max(case when ... end)}：某个会话一条都没送达时结果是 <b>NULL</b>，
     * 所以这两个字段必须是包装类型 {@code Long}（用 long 会直接炸）。
     *
     * <p>为什么用 {@code is not null} 而不是 {@code <> null}：后者恒为 NULL，是 SQL 里最常见的静默错误。
     */
    @Select("""
            <script>
            select r.conversation_id as conversationId,
                   max(case when r.delivered_at is not null then r.message_id end) as deliveredUpTo,
                   max(case when r.read_at is not null then r.message_id end) as readUpTo
              from message_receipt r
             where r.receiver_user_id in
                   <foreach collection="receiverUserIds" item="id" open="(" separator="," close=")">#{id}</foreach>
               and r.conversation_id in
                   <foreach collection="conversationIds" item="id" open="(" separator="," close=")">#{id}</foreach>
             group by r.conversation_id
            </script>
            """)
    List<ReceiptWatermark> selectWatermarks(@Param("receiverUserIds") List<Long> receiverUserIds,
                                            @Param("conversationIds") List<Long> conversationIds);

    /**
     * 上面的聚合结果：某个会话里"我发的消息被送到/读到哪条为止"。
     *
     * <p>两个字段都可能为 null（一条都没送达 / 一条都没读）。
     */
    record ReceiptWatermark(Long conversationId, Long deliveredUpTo, Long readUpTo) {
    }
}
