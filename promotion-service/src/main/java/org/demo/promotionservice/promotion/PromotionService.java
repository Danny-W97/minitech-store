package org.demo.promotionservice.promotion;

import org.demo.promotionservice.web.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.http.HttpStatus;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import org.springframework.web.client.RestClientException;

@Service
public class PromotionService {
    private static final String DAILY_CODE = "DAILY_STORE_ROTATION";
    private final PromotionCampaignRepository campaignRepository;
    private final PromotionPriceHistoryRepository historyRepository;
    private final PromotionCodeRepository codeRepository;
    private final PromotionRedemptionRepository redemptionRepository;
    @org.springframework.beans.factory.annotation.Autowired private CouponReservationRepository couponReservations;
    @org.springframework.beans.factory.annotation.Autowired private CouponReservationStateStore couponStates;
    @org.springframework.beans.factory.annotation.Autowired private CouponRedemptionVerifier couponVerifier;
    @org.springframework.beans.factory.annotation.Autowired private ProductPriceHistoryClient productPriceHistory;

    public PromotionService(PromotionCampaignRepository campaignRepository,
                             PromotionPriceHistoryRepository historyRepository,
                             PromotionCodeRepository codeRepository,
                             PromotionRedemptionRepository redemptionRepository) {
        this.campaignRepository = campaignRepository;
        this.historyRepository = historyRepository;
        this.codeRepository = codeRepository;
        this.redemptionRepository = redemptionRepository;
    }

    @Transactional(readOnly = true)
    public List<PromotionCampaignResponse> activeCampaigns() {
        return campaignRepository.findAllByActiveTrueOrderByValidFromDesc().stream()
                .filter(this::validToday).map(c -> new PromotionCampaignResponse(c.getCode(), c.getName(),
                        c.getMinDiscountPercent(), c.getMaxDiscountPercent(), c.getPromotionCount(),
                        c.getNewProductCount(), c.getValidFrom(), c.getValidTo())).toList();
    }

    @Transactional
    public List<PromotionEvaluationResponse> evaluate(List<PromotionEvaluationRequest> requests) {
        PromotionCampaign campaign = campaignRepository.findFirstByCodeAndActiveTrue(DAILY_CODE)
                .filter(this::validToday).orElse(null);
        if (campaign == null || requests == null || requests.isEmpty()) return List.of();
        LocalDate date = LocalDate.now(ZoneOffset.UTC);
        int size = Math.max(requests.stream().mapToInt(r -> r.catalogSize()).max().orElse(1), 1);
        long day = date.toEpochDay();
        return requests.stream().map(r -> offer(r, campaign, date, day, size)).toList();
    }

    @Transactional(propagation = org.springframework.transaction.annotation.Propagation.NOT_SUPPORTED)
    public List<PriceHistoryResponse> history(Long productId) {
        List<PriceHistoryResponse> result = new ArrayList<>();
        try { result.addAll(productPriceHistory.history(productId)); }
        catch (RestClientException unavailable) { /* Keep legacy observations readable while Product is unavailable. */ }
        result.addAll(historyRepository.findTop100ByProductIdOrderByRotationDateDescRecordedAtDesc(productId).stream()
                .map(h -> new PriceHistoryResponse(h.getProductId(), h.getCampaignCode(), h.getRegularPrice(),
                        h.getPromotionalPrice(), h.getDiscountPercent(), h.getRotationDate(), h.getRecordedAt(),
                        null, null, "LEGACY_OBSERVATION")).toList());
        return result.stream().sorted(Comparator.comparing(PriceHistoryResponse::rotationDate).reversed()
                        .thenComparing(PriceHistoryResponse::recordedAt, Comparator.nullsLast(Comparator.reverseOrder())))
                .limit(100).toList();
    }

    @Transactional(propagation = org.springframework.transaction.annotation.Propagation.NOT_SUPPORTED)
    public LowestPriceResponse lowestPrice(Long productId, int requestedDays) {
        int days = Math.min(Math.max(requestedDays, 1), 30);
        LocalDate to = LocalDate.now(ZoneOffset.UTC);
        LocalDate from = to.minusDays(days - 1L);
        BigDecimal lowest = null;
        boolean sourceAvailable = false;
        int observedDays = 0;
        boolean complete = false;
        try {
            ProductPriceHistoryClient.Summary source = productPriceHistory.lowest(productId, days);
            if (source != null) {
                sourceAvailable = true;
                if (source.days() == days && source.from() != null && source.to() != null) {
                    from = source.from();
                    to = source.to();
                }
                observedDays = Math.min(Math.max(source.observedDays(), 0), days);
                lowest = source.lowestObservedPrice();
                complete = source.days() == days && observedDays == days && lowest != null
                        && source.from() != null && source.to() != null;
            }
        } catch (RestClientException unavailable) { /* Fail closed when authoritative coverage cannot be checked. */ }
        complete = sourceAvailable && complete;
        return new LowestPriceResponse(productId, days, from, to, complete ? lowest : null, observedDays, complete);
    }

    @Transactional(readOnly = true)
    public List<PromotionCodeResponse> codes() {
        return codeRepository.findAllByOrderByCodeAsc().stream()
                .map(this::toCodeResponse)
                .toList();
    }

    @Transactional
    public PromotionCodeResponse createCode(CreatePromotionCodeRequest request) {
        if (request.validTo().isBefore(request.validFrom())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Data końcowa kodu nie może być wcześniejsza niż data początkowa.");
        }

        String code = normalizeOrGenerateCode(request.code());
        if (codeRepository.findByCodeIgnoreCase(code).isPresent()) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "Kod rabatowy " + code + " już istnieje.");
        }

        PromotionCode saved = codeRepository.save(new PromotionCode(
                code,
                request.description().trim(),
                request.discountPercent(),
                request.maxRedemptions(),
                request.validFrom(),
                request.validTo(),
                request.active(),
                request.minimumOrderValue(),
                request.maximumDiscountAmount()
        ));
        return toCodeResponse(saved);
    }

    @Transactional(readOnly = true)
    public CouponValidationResponse validateCoupon(CouponValidationRequest request) {
        String normalizedCode = request.code().trim().toUpperCase(Locale.ROOT);
        BigDecimal subtotal = request.subtotal() == null
                ? BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP)
                : request.subtotal().setScale(2, RoundingMode.HALF_UP);
        PromotionCode code = codeRepository.findByCodeIgnoreCaseAndActiveTrue(normalizedCode).orElse(null);
        if (code == null || !validToday(code.getValidFrom(), code.getValidTo())) {
            return invalidCoupon(normalizedCode, subtotal, "Kod rabatowy jest nieprawidłowy lub wygasł.");
        }
        if (code.getMaxRedemptions() != null && (long)code.getRedeemedCount()+couponReservations.countByCodeAndState(code.getCode(),"RESERVED") >= code.getMaxRedemptions()) {
            return invalidCoupon(normalizedCode, subtotal, "Limit użyć kodu rabatowego został wyczerpany.");
        }
        if (subtotal.compareTo(code.getMinimumOrderValue()) < 0) {
            return invalidCoupon(normalizedCode, subtotal, "Minimalna wartość koszyka dla tego kodu to " + code.getMinimumOrderValue() + " zł.");
        }
        BigDecimal discount = CouponPricing.discount(code,subtotal);
        return new CouponValidationResponse(
                normalizedCode, true, "Kod poprawny. Dostępność użycia potwierdzimy przy zamówieniu.", code.getDiscountPercent(), discount,
                subtotal, subtotal.subtract(discount).setScale(2, RoundingMode.HALF_UP),
                code.getMinimumOrderValue(), code.getMaximumDiscountAmount());
    }

    @Transactional(propagation=org.springframework.transaction.annotation.Propagation.NOT_SUPPORTED)
    public PromotionRedemptionResponse redeemCoupon(PromotionRedemptionRequest request) {
        String normalizedCode = request.code().trim().toUpperCase(Locale.ROOT);
        if(!couponStates.alreadyRedeemed(normalizedCode,request.orderId())) {
            var hold=couponStates.get(request.orderId());
            if(!java.util.Set.of("RESERVED","CONSUMED").contains(hold.state()) || !normalizedCode.equals(hold.code())) throw new ResponseStatusException(HttpStatus.CONFLICT,"Coupon reservation is not active for this order");
            if("RESERVED".equals(hold.state())) couponVerifier.verify(normalizedCode,request.orderId(),hold);
        }
        return couponStates.redeem(normalizedCode,request.orderId());
    }

    private PromotionRedemptionResponse redemptionResponse(PromotionCode code, Long orderId) {
        return new PromotionRedemptionResponse(code.getCode(), orderId, code.getRedeemedCount(),
                code.getMaxRedemptions(), true);
    }

    private CouponValidationResponse invalidCoupon(String code, BigDecimal subtotal, String message) {
        return new CouponValidationResponse(code, false, message, 0, BigDecimal.ZERO.setScale(2),
                subtotal, subtotal, BigDecimal.ZERO.setScale(2), null);
    }

    private PromotionCodeResponse toCodeResponse(PromotionCode code) {
        return new PromotionCodeResponse(
                code.getCode(), code.getDescription(), code.getDiscountPercent(),
                code.getMaxRedemptions(), code.getRedeemedCount(), code.isActive(),
                code.getValidFrom(), code.getValidTo(), code.getMinimumOrderValue(), code.getMaximumDiscountAmount());
    }

    private String normalizeOrGenerateCode(String requestedCode) {
        if (requestedCode != null && !requestedCode.isBlank()) {
            return requestedCode.trim().toUpperCase(Locale.ROOT);
        }
        for (int attempt = 0; attempt < 10; attempt++) {
            String generated = "MINI-" + UUID.randomUUID().toString()
                    .replace("-", "").substring(0, 10).toUpperCase(Locale.ROOT);
            if (codeRepository.findByCodeIgnoreCase(generated).isEmpty()) return generated;
        }
        throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,
                "Nie udało się wygenerować unikalnego kodu rabatowego.");
    }

    private PromotionEvaluationResponse offer(PromotionEvaluationRequest r, PromotionCampaign c,
                                               LocalDate date, long day, int size) {
        int index = Math.max(r.catalogIndex(), 0);
        int newOffset = Math.floorMod(day, size);
        boolean isNew = Math.floorMod(index - newOffset, size) < Math.min(c.getNewProductCount(), size);
        int promotionOffset = Math.floorMod(day * 3, size);
        int distance = Math.floorMod(index - promotionOffset, size);
        boolean promotional = distance < Math.min(c.getPromotionCount(), size);
        int discount = 0;
        if (promotional) {
            discount = switch (distance) {
                case 0 -> c.getMinDiscountPercent();
                case 1 -> c.getMaxDiscountPercent();
                default -> c.getMinDiscountPercent() + Math.floorMod(day + r.productId() * 17L,
                        c.getMaxDiscountPercent() - c.getMinDiscountPercent() + 1);
            };
        }
        BigDecimal regular = r.basePrice().setScale(2, RoundingMode.HALF_UP);
        BigDecimal price = promotional ? regular.multiply(BigDecimal.valueOf(100L - discount))
                .divide(BigDecimal.valueOf(100L), 2, RoundingMode.HALF_UP) : regular;
        return new PromotionEvaluationResponse(r.productId(), price, regular, discount, promotional, isNew,
                date, c.getCode());
    }

    private boolean validToday(PromotionCampaign campaign) {
        return validToday(campaign.getValidFrom(), campaign.getValidTo());
    }

    private boolean validToday(LocalDate validFrom, LocalDate validTo) {
        LocalDate today = LocalDate.now(ZoneOffset.UTC);
        return !today.isBefore(validFrom) && !today.isAfter(validTo);
    }
}
