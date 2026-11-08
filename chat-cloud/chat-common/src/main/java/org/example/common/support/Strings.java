package org.example.common.support;

/**
 * 字符串小工具。
 *
 * <p>目前只有一个方法，但它在 chat-user（改资料）和 chat-relation（改备注）里
 * 各写了一遍，而且两处的语义<b>必须完全一致</b>：
 * <pre>
 *   空串 / 全空白  ==  "这个字段不改"
 * </pre>
 * 因为 MyBatis-Plus 的 {@code FieldStrategy.NOT_NULL} 会把 null 当成"这一列不出现在 UPDATE 里"。
 * 两处各写一份的话，哪天有人只改了一处，用户看到的就会是"昵称能清空、备注不能"这种怪现象。
 */
public final class Strings {

    /**
     * 空串 / 全空白 → null。
     *
     * <p>它的实际作用是实现"空字符串 = 不改"这条约定（见类注释）。
     * <b>副作用</b>：没法用空串把字段清空 —— 要真的清空得用带 {@code set} 的 update
     * （{@code FriendServiceImpl#updateRemark} 清除备注就是这么做的）。
     *
     * @param value 原值，可能是 null
     * @return 去掉首尾空白后的值；原来是 null / 空串 / 全空白时返回 null
     */
    public static String trimToNull(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    private Strings() {
    }
}
