package org.example.common.constant;

/**
 * 服务名常量。Feign 调用、网关路由都引用这里，避免各模块写错字符串。
 */
public final class ServiceNames {

    public static final String CHAT_AUTH = "chat-auth";
    public static final String CHAT_USER = "chat-user";
    /** 关系服务：好友 / 黑名单 / 分组 */
    public static final String CHAT_RELATION = "chat-relation";
    public static final String CHAT_MESSAGE = "chat-message";
    public static final String CHAT_CONNECTION = "chat-connection";
    public static final String CHAT_GATEWAY = "chat-gateway";

    private ServiceNames() {
    }
}
