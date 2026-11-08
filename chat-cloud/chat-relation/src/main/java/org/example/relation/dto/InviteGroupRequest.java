package org.example.relation.dto;

import java.util.List;
import jakarta.validation.constraints.*;

public record InviteGroupRequest(@NotEmpty @Size(max = 50) List<@NotNull @Positive Long> userIds) {}
