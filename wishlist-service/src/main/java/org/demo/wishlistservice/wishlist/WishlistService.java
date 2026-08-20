package org.demo.wishlistservice.wishlist;

import org.demo.wishlistservice.web.WishlistItemResponse;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Service
public class WishlistService {
    private final WishlistItemRepository repository;

    public WishlistService(WishlistItemRepository repository) {
        this.repository = repository;
    }

    @Transactional(readOnly = true)
    public List<WishlistItemResponse> get(Long customerId) {
        return repository.findAllByCustomerIdOrderByCreatedAtDesc(customerId).stream()
                .map(item -> new WishlistItemResponse(item.getProductId(), item.getCreatedAt()))
                .toList();
    }

    @Transactional
    public WishlistItemResponse add(Long customerId, Long productId) {
        WishlistItem item = repository.findByCustomerIdAndProductId(customerId, productId)
                .orElseGet(() -> repository.save(new WishlistItem(customerId, productId)));
        return new WishlistItemResponse(item.getProductId(), item.getCreatedAt());
    }

    @Transactional
    public void remove(Long customerId, Long productId) {
        repository.findByCustomerIdAndProductId(customerId, productId).ifPresent(repository::delete);
    }
}
