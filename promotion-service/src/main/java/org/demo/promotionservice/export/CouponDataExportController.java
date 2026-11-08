package org.demo.promotionservice.export;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import org.demo.platformsecurity.Access;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/promotions/me/data-export/orders/{orderId}")
@SecurityRequirement(name="bearerAuth")
public class CouponDataExportController {
    private final CouponDataExportService service;
    public CouponDataExportController(CouponDataExportService service){this.service=service;}
    @GetMapping(produces=MediaType.APPLICATION_JSON_VALUE)
    @Operation(summary="Download the stored coupon reservation for your own order",description="Current Order owner required. Does not expire, release, redeem or reconcile coupons; excludes operational version and global promotion definitions.")
    public ResponseEntity<CouponDataExport> export(@PathVariable long orderId){allowed();return response("coupon-reservation-data.json",service.export(orderId));}
    @GetMapping(value="/redemptions",produces=MediaType.APPLICATION_JSON_VALUE)
    @Operation(summary="Download a bounded page of coupon redemptions for your own order",description="Only stored transaction codes, order id and redemption dates; no global usage or limits. Partial export.")
    public ResponseEntity<RedemptionDataExportPage> redemptions(@PathVariable long orderId,@RequestParam(defaultValue="0")long cursor,@RequestParam(defaultValue="50")int size){allowed();return response("coupon-redemptions-page.json",service.redemptions(orderId,cursor,size));}
    private static void allowed(){var actor=Access.actor();Access.require("CUSTOMER".equals(actor.role())&&actor.customerId()!=null&&actor.customerId()>0);}
    private static <T> ResponseEntity<T> response(String filename,T body){return ResponseEntity.ok().cacheControl(CacheControl.noStore().cachePrivate())
            .header(HttpHeaders.CONTENT_DISPOSITION,"attachment; filename=\""+filename+"\"").header("X-Content-Type-Options","nosniff").body(body);}
}
