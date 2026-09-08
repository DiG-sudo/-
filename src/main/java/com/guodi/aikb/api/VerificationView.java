package com.guodi.aikb.api;

public record VerificationView(
        String status,
        String reason,
        int evidenceCount) {
}
