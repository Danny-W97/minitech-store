package org.demo.returnsservice.web;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import org.demo.returnsservice.returns.ReturnStatus;

public record ChangeReturnStatusRequest(@NotNull ReturnStatus status, @Size(max = 1000) String note) {}
