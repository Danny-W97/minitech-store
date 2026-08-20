package org.demo.chatservice.web;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record ReplyChatMessageRequest(
        @NotBlank @Size(max = 4000) String message
) {
}
