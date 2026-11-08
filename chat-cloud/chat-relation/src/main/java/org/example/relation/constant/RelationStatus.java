package org.example.relation.constant;

/**
 * 「我和某个人是什么关系」—— 加好友页据此决定按钮长什么样。
 *
 * <p>前端拿到这个值之后：
 * <pre>
 *   NONE         → 显示「添加到通讯录」，可点
 *   PENDING_OUT  → 显示「已申请，等待验证」，不可点
 *   PENDING_IN   → 显示「对方申请了你，去处理」，点了去"新的朋友"页
 *   FRIEND       → 显示「发消息」
 *   SELF         → 什么按钮都不显示，只提示"这是你自己"
 * </pre>
 *
 * <p><b>为什么必须由后端算？</b> 因为"关系"这件事前端根本不知道：
 * 它只有一个人的资料，看不到关系表。前端自己猜会出现"已经是好友了还显示添加"这种错。
 */
public final class RelationStatus {

    /** 陌生人（没申请过，也不是好友） */
    public static final String NONE = "NONE";

    /** 我申请了对方，还在等对方处理 */
    public static final String PENDING_OUT = "PENDING_OUT";

    /** 对方申请了我，等我处理 */
    public static final String PENDING_IN = "PENDING_IN";

    /** 已经是好友 */
    public static final String FRIEND = "FRIEND";

    /** 点的是自己 */
    public static final String SELF = "SELF";

    private RelationStatus() {
    }
}
