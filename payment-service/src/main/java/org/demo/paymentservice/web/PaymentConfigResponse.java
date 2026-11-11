package org.demo.paymentservice.web;

import java.util.List;

public record PaymentConfigResponse(String provider,List<String> supportedMethods) {}
