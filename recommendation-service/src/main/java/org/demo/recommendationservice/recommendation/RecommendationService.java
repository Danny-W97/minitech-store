package org.demo.recommendationservice.recommendation;

import org.demo.recommendationservice.recommendation.ProductCatalogClient.ProductSnapshot;
import org.demo.recommendationservice.web.RecommendationException;
import org.demo.recommendationservice.web.RecommendationResponse;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.demo.recommendationservice.telemetry.RecommendationTelemetry;
import static org.demo.recommendationservice.telemetry.RecommendationTelemetry.Strategy.CUSTOMER_HISTORY;
import static org.demo.recommendationservice.telemetry.RecommendationTelemetry.Strategy.PRODUCT_SIMILARITY;

import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

@Service
public class RecommendationService {
    private final ProductCatalogClient productCatalogClient;
    private final OrderHistoryClient orderHistoryClient;
    private final RecommendationTelemetry telemetry;

    public RecommendationService(ProductCatalogClient productCatalogClient,
                                 OrderHistoryClient orderHistoryClient,
                                 RecommendationTelemetry telemetry) {
        this.productCatalogClient = productCatalogClient;
        this.orderHistoryClient = orderHistoryClient;
        this.telemetry = telemetry;
    }

    public List<RecommendationResponse> forProduct(Long productId, int limit) {
        List<ProductSnapshot> catalog = productCatalogClient.getActiveProducts();
        ProductSnapshot source = catalog.stream()
                .filter(product -> product.id().equals(productId))
                .findFirst()
                .orElseThrow(() -> new RecommendationException(HttpStatus.NOT_FOUND, "Nie znaleziono aktywnego produktu."));

        List<RecommendationResponse> result = rankForProduct(catalog, source, limit);
        telemetry.record(PRODUCT_SIMILARITY, null, productId, result.size());
        return result;
    }

    public List<RecommendationResponse> forCustomer(Long customerId, int limit) {
        List<ProductSnapshot> catalog = productCatalogClient.getActiveProducts();
        Set<Long> purchasedIds = orderHistoryClient.purchasedProducts(customerId, catalog.stream().map(ProductSnapshot::id).toList());

        List<RecommendationResponse> result = purchasedIds.isEmpty()
                ? rankFallback(catalog, limit)
                : rankForHistory(catalog, purchasedIds, limit);
        telemetry.record(CUSTOMER_HISTORY, customerId, null, result.size());
        return result;
    }

    private List<RecommendationResponse> rankForProduct(List<ProductSnapshot> catalog,
                                                        ProductSnapshot source, int limit) {
        return catalog.stream()
                .filter(product -> !product.id().equals(source.id()))
                .map(product -> scored(product, similarityScore(source, product), reasonFor(source, product)))
                .sorted(Comparator.comparingInt(ScoredProduct::score).reversed()
                        .thenComparing(item -> item.product().name(), String.CASE_INSENSITIVE_ORDER))
                .limit(normalizeLimit(limit))
                .map(item -> RecommendationResponse.from(item.product(), item.score(), item.reason()))
                .toList();
    }

    private List<RecommendationResponse> rankForHistory(List<ProductSnapshot> catalog,
                                                       Set<Long> purchasedIds, int limit) {
        List<ProductSnapshot> purchased = catalog.stream()
                .filter(product -> purchasedIds.contains(product.id()))
                .toList();
        return catalog.stream()
                .filter(product -> !purchasedIds.contains(product.id()))
                .map(product -> {
                    ScoredProduct best = purchased.stream()
                            .map(source -> scored(product, similarityScore(source, product), reasonFor(source, product)))
                            .max(Comparator.comparingInt(ScoredProduct::score))
                            .orElse(new ScoredProduct(product, fallbackScore(product), "Polecane na podstawie aktualnej oferty."));
                    return best;
                })
                .sorted(Comparator.comparingInt(ScoredProduct::score).reversed()
                        .thenComparing(item -> item.product().name(), String.CASE_INSENSITIVE_ORDER))
                .limit(normalizeLimit(limit))
                .map(item -> RecommendationResponse.from(item.product(), item.score(), "Na podstawie Twoich opłaconych zamówień: " + item.reason()))
                .toList();
    }

    private List<RecommendationResponse> rankFallback(List<ProductSnapshot> catalog, int limit) {
        return catalog.stream()
                .map(product -> scored(product, fallbackScore(product), "Polecane z aktualnej oferty."))
                .sorted(Comparator.comparingInt(ScoredProduct::score).reversed()
                        .thenComparing(item -> item.product().name(), String.CASE_INSENSITIVE_ORDER))
                .limit(normalizeLimit(limit))
                .map(item -> RecommendationResponse.from(item.product(), item.score(), item.reason()))
                .toList();
    }

    private ScoredProduct scored(ProductSnapshot product, int score, String reason) {
        return new ScoredProduct(product, score, reason);
    }

    private int similarityScore(ProductSnapshot source, ProductSnapshot candidate) {
        int score = 0;
        if (same(source.category(), candidate.category())) score += 70;
        if (same(source.manufacturer(), candidate.manufacturer())) score += 20;
        score += Math.min(sharedParameters(source.technicalParameters(), candidate.technicalParameters()) * 4, 16);
        if (candidate.promotional()) score += 5;
        if (candidate.newProduct()) score += 3;
        return score;
    }

    private int fallbackScore(ProductSnapshot product) {
        return (product.promotional() ? 20 : 0) + (product.newProduct() ? 12 : 0)
                + (product.discountPercent() > 0 ? Math.min(product.discountPercent(), 10) : 0);
    }

    private String reasonFor(ProductSnapshot source, ProductSnapshot candidate) {
        if (same(source.category(), candidate.category()) && same(source.manufacturer(), candidate.manufacturer())) {
            return "Ta sama kategoria i producent.";
        }
        if (same(source.category(), candidate.category())) return "Ta sama kategoria produktu.";
        if (same(source.manufacturer(), candidate.manufacturer())) return "Ten sam producent.";
        if (sharedParameters(source.technicalParameters(), candidate.technicalParameters()) > 0) {
            return "Podobne parametry techniczne.";
        }
        return "Polecane z aktualnej oferty.";
    }

    private int sharedParameters(Map<String, Object> left, Map<String, Object> right) {
        if (left == null || right == null) return 0;
        Set<String> keys = new HashSet<>(left.keySet());
        keys.retainAll(right.keySet());
        return (int) keys.stream()
                .filter(key -> left.get(key) != null && left.get(key).equals(right.get(key)))
                .count();
    }

    private boolean same(String left, String right) {
        return left != null && left.equalsIgnoreCase(right);
    }

    private int normalizeLimit(int limit) {
        return Math.min(Math.max(limit, 1), 12);
    }

    private record ScoredProduct(ProductSnapshot product, int score, String reason) {}
}
