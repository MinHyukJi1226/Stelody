package com.stelody.user.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import java.util.UUID;

@Schema(requiredProperties = {"id", "email", "role", "status"})
public record CurrentUser(UUID id, String email, String role, String status) {}
