package org.demo.wishlistservice.web;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.constraints.Positive;
import org.demo.wishlistservice.wishlist.WishlistService;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@Validated
@RestController
@RequestMapping("/wishlists")
@Tag(name = "Wishlist", description = "Customer saved products")
public class WishlistController {
    private final WishlistService wishlistService;

    public WishlistController(WishlistService wishlistService) {
        this.wishlistService = wishlistService;
    }

    @GetMapping("/customer/{customerId}")
    @Operation(summary = "List saved products")
    public List<WishlistItemResponse> get(@PathVariable @Positive Long customerId) {
        return wishlistService.get(customerId);
    }

    @PutMapping("/customer/{customerId}/products/{productId}")
    @Operation(summary = "Save a product")
    public ResponseEntity<WishlistItemResponse> add(
            @PathVariable @Positive Long customerId,
            @PathVariable @Positive Long productId
    ) {
        return ResponseEntity.status(HttpStatus.CREATED).body(wishlistService.add(customerId, productId));
    }

    @DeleteMapping("/customer/{customerId}/products/{productId}")
    @Operation(summary = "Remove a saved product")
    public ResponseEntity<Void> remove(
            @PathVariable @Positive Long customerId,
            @PathVariable @Positive Long productId
    ) {
        wishlistService.remove(customerId, productId);
        return ResponseEntity.noContent().build();
    }
}
