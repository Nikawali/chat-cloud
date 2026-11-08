package org.example.relation.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

public record CreateGroupRequest(
        @NotBlank @Size(max = 100) String groupName,
        @NotBlank @Pattern(regexp = "[A-Za-z0-9-]{16,64}") String clientRequestId) {}
