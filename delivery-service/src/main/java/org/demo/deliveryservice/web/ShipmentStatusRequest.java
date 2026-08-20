package org.demo.deliveryservice.web;

import jakarta.validation.constraints.NotBlank;

public record ShipmentStatusRequest(@NotBlank String status) {
}
