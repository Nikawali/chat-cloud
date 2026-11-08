package org.example.common.dto;

import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * 用户资料 —— 用于"把一个人展示出来"的场景：搜人、好友列表、群成员、个人主页。
 *
 * <p><b>它和 {@link UserDTO} 的区别（别混用）</b>：
 * <ul>
 *   <li>{@link UserDTO}（id + username）是<b>最小身份</b>，给认证流程用：
 *       auth 只需要知道"是几号用户、叫什么"就够签发 token 了。</li>
 *   <li>{@code UserProfileDTO} 是<b>完整展示信息</b>：界面要显示"这是谁"，
 *       光有 id 和用户名不够，还要昵称、性别、生日、头像。</li>
 * </ul>
 *
 * <p><b>为什么不含 phone？</b> 手机号是个人敏感信息，不该在服务之间和响应体里到处传。
 * 调用方如果需要按手机号找人，应该用"按手机号查"的接口，而不是让对方把手机号带回来
 * —— 搜人的那个界面里，用户本来就已经知道自己输的是什么手机号。
 *
 * <p><b>为什么不含 password？</b> 那是底线，任何 DTO 都不该带。
 *
 * <p><b>字段说明</b>：
 * <ul>
 *   <li>{@code userId} —— 用户 id。全项目统一叫 {@code userId}（库列 user_id → 实体 userId →
 *       这个 DTO → Redis 字段 → JSON，一路同名，不留 {@code id} 这种模棱两可的写法）</li>
 *   <li>{@code username} —— 登录用户名，也就是"真名"（库里 user_name 列）</li>
 *   <li>{@code nickName} —— 昵称（库里 nick_name 列），注册时默认等于用户名，之后可以改。
 *       <b>注意大小写是 {@code nickName}</b>：数据库那一层按 SQL 惯例用下划线
 *       （和同表的 user_name / avatar_url 保持一致），到 Java / JSON / Redis 这一层统一用
 *       {@code nickName}，中间的转换由 MyBatis 的 map-underscore-to-camel-case 自动完成</li>
 *   <li>{@code gender} —— 性别。用单字符存：{@code M} / {@code F}，未填为 null</li>
 *   <li>{@code birthDate} —— 出生日期。只有年月日，序列化后形如 {@code "2000-01-01"}</li>
 *   <li>{@code updatedAt} —— <b>最后更新时间，同时充当缓存的版本号</b>。
 *       它跟着数据一路传（库 → DTO → 缓存），缓存写入时用它做 CAS 比较，
 *       防止"旧数据覆盖新数据"。
 *       <b>它是内部字段，不该出现在给前端的响应里</b> —— 所以对外返回的是
 *       chat-user 的 {@code UserCardVO} / {@code UserDetailVO}（都不含它），
 *       这个 DTO 只用于服务内部和 Redis 缓存</li>
 * </ul>
 */
public record UserProfileDTO(
        Long userId,
        String username,
        String nickName,
        String gender,
        LocalDate birthDate,
        String avatarUrl,
        LocalDateTime updatedAt
) {
}
