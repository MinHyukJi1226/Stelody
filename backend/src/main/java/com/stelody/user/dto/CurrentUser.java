package com.stelody.user.dto;

import java.util.UUID;

public record CurrentUser(UUID id, String email, String role, String status) {}
