package org.demo.returnsservice.web;
import jakarta.validation.constraints.*;
import java.util.UUID;
public record ReconcileReturnRequest(@NotNull @PositiveOrZero Long expectedVersion,@NotNull UUID operationId,@NotBlank @Size(max=1000) String note) {}
