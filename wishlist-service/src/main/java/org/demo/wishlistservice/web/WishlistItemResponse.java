package org.demo.wishlistservice.web;

import java.time.Instant;

public record WishlistItemResponse(Long productId, Instant createdAt) {}
