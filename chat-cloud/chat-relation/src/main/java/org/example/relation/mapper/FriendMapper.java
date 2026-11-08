package org.example.relation.mapper;

import org.apache.ibatis.annotations.Mapper;
import org.example.relation.entity.FriendEntity;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;

/**
 * 好友关系表的数据访问层。
 *
 * <p>继承 {@code BaseMapper} 就够了：查列表、按条件 count、插入、删除都有现成的，
 * 一句 SQL 都不用写。（需要复杂统计时再往上加方法。）
 */
@Mapper
public interface FriendMapper extends BaseMapper<FriendEntity> {
}
