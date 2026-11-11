package org.demo.chatservice.web;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record ReplyChatMessageRequest(
        @NotBlank @Size(max = 4000) String message,
        @jakarta.validation.constraints.Pattern(regexp="[A-Za-z0-9._:-]{1,100}") String requestId
) {
    public ReplyChatMessageRequest(String message) { this(message,null); }
}
