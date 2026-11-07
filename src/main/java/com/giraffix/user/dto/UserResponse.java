package com.giraffix.user.dto;


import com.giraffix.user.entity.User;

import java.util.UUID;

public record UserResponse(
        UUID id,
        String email,
        String fullName,
        boolean onboardingCompleted
) {
    public static UserResponse from(User user) {
        return new UserResponse(
                user.getId(),
                user.getEmail(),
                user.getFullName(),
                user.isOnboardingCompleted()
        );
    }
}
