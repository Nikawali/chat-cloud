package org.example.message.mapper;

import org.apache.ibatis.annotations.Mapper;
import org.example.message.entity.ConversationMemberEntity;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;

/**
 * 会话成员的数据访问层。
 *
 * <p>注意这个实体<b>没有单列主键</b>（主键是 conversation_id + user_id 复合键），
 * 所以 {@code selectById} / {@code updateById} 用不了，一律用 {@code selectOne(wrapper)} /
 * {@code update(null, wrapper)} 按这两个字段定位。
 */
@Mapper
public interface ConversationMemberMapper extends BaseMapper<ConversationMemberEntity> {
}
