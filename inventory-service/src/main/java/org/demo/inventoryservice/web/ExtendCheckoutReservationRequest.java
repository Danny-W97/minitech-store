package org.demo.inventoryservice.web;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import java.time.Instant;

public record ExtendCheckoutReservationRequest(@NotNull @Positive Long orderId, @NotNull Instant expiresAt) {}
