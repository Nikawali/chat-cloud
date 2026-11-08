package org.example.message.mapper;

import org.apache.ibatis.annotations.Mapper;
import org.example.message.entity.ConversationEntity;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;

@Mapper
public interface ConversationMapper extends BaseMapper<ConversationEntity> {
}
