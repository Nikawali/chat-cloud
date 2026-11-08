package org.example.relation.mapper;

import org.apache.ibatis.annotations.Mapper;
import org.example.relation.entity.FriendRequestEntity;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;

/** 好友申请表的数据访问层。同样只用 BaseMapper 的现成方法 */
@Mapper
public interface FriendRequestMapper extends BaseMapper<FriendRequestEntity> {
}
