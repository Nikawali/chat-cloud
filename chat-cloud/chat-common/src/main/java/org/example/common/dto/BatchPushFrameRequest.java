package org.example.common.dto;
import java.util.List;
import java.util.Map;
public record BatchPushFrameRequest(List<Target> targets, Map<String,Object> frame) {
    public record Target(Long userId, String sessionId) {}
}
