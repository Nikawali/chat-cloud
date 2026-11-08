package org.example.user.entity;

import java.time.LocalDate;
import java.time.LocalDateTime;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import lombok.Data;

/**
 * user 表对应的实体，字段和数据库【一一对应】。
 *
 * <p><b>这是 chat-user 的私有实现，绝对不能放进 chat-common。</b>
 * 它含密码哈希等敏感字段，别的服务只应该通过 UserDTO（对外契约）拿数据。
 */
@Data
// ⚠️ 表名用反引号包起来：USER 是 MySQL 的关键字（USER() 是内置函数），
// 不包反引号在某些语句里会产生歧义甚至语法错误。
@TableName("`user`")
public class UserEntity {

    /** 数据库主键列名是 user_id（不是 id），必须显式指定 */
    @TableId(value = "user_id", type = IdType.AUTO)
    private Long userId;

    /** 对应 user_name（下划线转驼峰由 map-underscore-to-camel-case 自动完成） */
    private String userName;

    /**
     * 对应 password 列。
     * ⚠️ 存的是 <b>BCrypt 哈希值</b>，绝不是明文 —— 列名叫 password 有点误导，
     * 但这是既有的表结构，代码里务必只往这里写哈希。
     */
    private String password;

    /** 对应 nick_name，注册时默认填用户名 */
    private String nickName;

    /** 对应 avatar_url，注册时不设置，留 null */
    private String avatarUrl;

    private String phone;

    /**
     * 对应 created_at / updated_at。
     * 这两个是【数据库自己管】的技术字段（建表时有 DEFAULT CURRENT_TIMESTAMP），
     * 代码里不设置，MyBatis-Plus 默认策略下 null 字段不会进 INSERT 语句。
     */
    private LocalDateTime createdAt;

    private LocalDateTime updatedAt;

    private LocalDate birthDate;

    private String gender;
}
