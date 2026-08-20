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
import java.util.List;
import java.util.Locale;
import java.util.UUID;

@Service
public class PromotionService {
    private static final String DAILY_CODE = "DAILY_STORE_ROTATION";
    private final PromotionCampaignRepository campaignRepository;
    private final PromotionPriceHistoryRepository historyRepository;
    private final PromotionCodeRepository codeRepository;
    private final PromotionRedemptionRepository redemptionRepository;

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
        List<PromotionEvaluationResponse> offers = requests.stream().map(r -> offer(r, campaign, date, day, size)).toList();
        offers.stream()
                .filter(o -> !historyRepository.existsByProductIdAndCampaignCodeAndRotationDate(
                        o.productId(), o.campaignCode(), o.rotationDate()))
                .map(o -> new PromotionPriceHistory(o.productId(), o.campaignCode(), o.regularPrice(), o.price(),
                        o.discountPercent(), o.rotationDate()))
                .forEach(historyRepository::save);
        return offers;
    }

    @Transactional(readOnly = true)
    public List<PriceHistoryResponse> history(Long productId) {
        return historyRepository.findTop30ByProductIdOrderByRotationDateDesc(productId).stream()
                .map(h -> new PriceHistoryResponse(h.getProductId(), h.getCampaignCode(), h.getRegularPrice(),
                        h.getPromotionalPrice(), h.getDiscountPercent(), h.getRotationDate(), h.getRecordedAt())).toList();
    }

    @Transactional(readOnly = true)
    public LowestPriceResponse lowestPrice(Long productId, int requestedDays) {
        int days = Math.min(Math.max(requestedDays, 1), 30);
        LocalDate to = LocalDate.now(ZoneOffset.UTC);
        LocalDate from = to.minusDays(days - 1L);
        BigDecimal lowest = historyRepository.findLowestPriceSince(productId, from).orElse(null);
        return new LowestPriceResponse(productId, days, from, to, lowest);
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
        if (code.getMaxRedemptions() != null && code.getRedeemedCount() >= code.getMaxRedemptions()) {
            return invalidCoupon(normalizedCode, subtotal, "Limit użyć kodu rabatowego został wyczerpany.");
        }
        if (subtotal.compareTo(code.getMinimumOrderValue()) < 0) {
            return invalidCoupon(normalizedCode, subtotal, "Minimalna wartość koszyka dla tego kodu to " + code.getMinimumOrderValue() + " zł.");
        }
        BigDecimal discount = subtotal.multiply(BigDecimal.valueOf(code.getDiscountPercent()))
                .divide(BigDecimal.valueOf(100), 2, RoundingMode.HALF_UP);
        if (code.getMaximumDiscountAmount() != null && discount.compareTo(code.getMaximumDiscountAmount()) > 0) {
            discount = code.getMaximumDiscountAmount().setScale(2, RoundingMode.HALF_UP);
        }
        return new CouponValidationResponse(
                normalizedCode, true, "Kod rabatowy zaakceptowany.", code.getDiscountPercent(), discount,
                subtotal, subtotal.subtract(discount).setScale(2, RoundingMode.HALF_UP),
                code.getMinimumOrderValue(), code.getMaximumDiscountAmount());
    }

    @Transactional
    public PromotionRedemptionResponse redeemCoupon(PromotionRedemptionRequest request) {
        String normalizedCode = request.code().trim().toUpperCase(Locale.ROOT);
        PromotionCode code = codeRepository.findByCodeIgnoreCase(normalizedCode)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND,
                        "Kod rabatowy nie istnieje."));

        if (redemptionRepository.existsByCodeIgnoreCaseAndOrderId(normalizedCode, request.orderId())) {
            return redemptionResponse(code, request.orderId());
        }
        if (!code.isActive() || !validToday(code.getValidFrom(), code.getValidTo())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "Kod rabatowy jest nieaktywny lub wygasł.");
        }
        if (codeRepository.incrementRedeemedCountIfAvailable(normalizedCode) != 1) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "Limit użyć kodu rabatowego został wyczerpany.");
        }
        // The bulk update deliberately protects the limit against concurrent
        // requests. Keep the managed entity in sync for the response in this
        // transaction as well.
        code.incrementRedeemedCount();
        redemptionRepository.save(new PromotionRedemption(normalizedCode, request.orderId()));
        PromotionCode updated = codeRepository.findByCodeIgnoreCase(normalizedCode).orElse(code);
        return redemptionResponse(updated, request.orderId());
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
