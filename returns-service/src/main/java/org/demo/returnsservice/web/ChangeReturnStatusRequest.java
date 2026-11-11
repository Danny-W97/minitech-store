package org.demo.returnsservice.web;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.PositiveOrZero;
import java.util.UUID;
import org.demo.returnsservice.returns.ReturnStatus;

public record ChangeReturnStatusRequest(@NotNull ReturnStatus status,@NotBlank @Size(max = 1000) String note,
        @NotNull @PositiveOrZero Long expectedVersion,@NotNull UUID operationId) {}
