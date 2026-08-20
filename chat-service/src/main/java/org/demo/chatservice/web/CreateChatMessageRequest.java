package org.demo.chatservice.web;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record CreateChatMessageRequest(
        @NotBlank @Size(max = 100) String customerName,
        @NotBlank @Email @Size(max = 255) String customerEmail,
        @Size(max = 160) String subject,
        @NotBlank @Size(max = 4000) String message
) {
}
