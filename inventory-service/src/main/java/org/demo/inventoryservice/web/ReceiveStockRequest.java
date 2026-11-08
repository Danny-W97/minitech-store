package org.demo.inventoryservice.web;

import jakarta.validation.constraints.*;

public record ReceiveStockRequest(@NotNull @Positive Integer quantity,
        @NotBlank @Pattern(regexp="[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}") String operationId) {}
