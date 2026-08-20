package org.demo.returnsservice.web;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import org.demo.returnsservice.returns.ReturnType;

public record CreateReturnRequest(
        @NotNull @Positive Long orderId,
        @NotNull @Positive Long customerId,
        @NotBlank @Email @Size(max = 255) String customerEmail,
        @NotNull ReturnType requestType,
        @NotBlank @Size(max = 120) String reason,
        @NotBlank @Size(max = 2000) String description
) {}
