package org.example.relation.dto;

import jakarta.validation.constraints.Size;

/**
 * 修改好友备注的请求体。
 *
 * <p>备注是<b>单向</b>的：我改备注只影响我自己的好友列表，对方看不到、也不受影响。
 * 所以这个接口改的永远是"我的那一行"。
 *
 * <p>传空串/空白 = <b>清除备注</b>（回到显示对方昵称）。
 * 注意这和 {@code UpdateProfileRequest} 的约定<b>相反</b> ——
 * 那边"空串 = 不改"，因为资料是"部分更新"，而这里备注只有一个字段，
 * 而且"没有备注"本身就是一个合法状态（默认状态）。
 *
 * @param remark 新备注；传 null 或空串表示清除备注
 */
public record UpdateRemarkRequest(

        @Size(max = 50, message = "备注不能超过 50 个字符")
        String remark
) {
}
