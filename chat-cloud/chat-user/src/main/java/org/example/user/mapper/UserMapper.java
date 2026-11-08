package org.example.user.mapper;

import org.apache.ibatis.annotations.Mapper;
import org.example.user.entity.UserEntity;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;

/**
 * 用户表的数据访问层。
 *
 * <p>继承 MyBatis-Plus 的 {@link BaseMapper} 后，基础的增删改查（insert/selectById/...）
 * 全部自带，不用写 XML，也不用写 SQL。
 * 需要复杂查询时再往上加方法（可以配 @Select 注解或 XML）。
 */
@Mapper
public interface UserMapper extends BaseMapper<UserEntity> {
}
