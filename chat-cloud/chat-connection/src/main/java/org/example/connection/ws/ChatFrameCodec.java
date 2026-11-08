package org.example.connection.ws;

import java.util.Map;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

/** WS JSON 编解码属于接入层；公共协议不依赖 Spring 或 Jackson。 */
public final class ChatFrameCodec {
    private static final ObjectMapper MAPPER = JsonMapper.builder().build();

    private ChatFrameCodec() {
    }

    public static String toJson(Map<String, Object> frame) {
        return MAPPER.writeValueAsString(frame);
    }

    @SuppressWarnings("unchecked")
    public static Map<String, Object> parse(String payload) {
        try {
            Object parsed = MAPPER.readValue(payload, Map.class);
            if (parsed instanceof Map<?, ?> map) {
                return (Map<String, Object>) map;
            }
            throw new IllegalArgumentException("帧必须是一个 JSON 对象");
        } catch (JacksonException e) {
            throw new IllegalArgumentException("帧不是合法 JSON: " + e.getMessage());
        }
    }
}
