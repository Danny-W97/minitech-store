package org.demo.customerservice.export.jobs;

/** The caller cannot choose hosts, paths, customer ids or free-form cursors. */
public enum ExportKind {
    PROFILE("customer","customer-profile-and-security-summary",null),
    METHODS("customer","security-method-metadata",null),
    EVENTS("customer","security-audit-page","items"),
    PASSKEYS("customer","security-passkey-metadata-page","items"),
    RECOVERY("customer","security-recovery-metadata-page","items"),
    WISHLIST("wishlist","wishlist-page","items"), REVIEWS("review","reviews-page","items"),
    RECOMMENDATIONS("recommendation","recommendation-log-page","items"),
    ORDERS("order","orders-page","orders"), RETURNS("returns","returns-page","returns"),
    ORDER_ITEMS("order","order-items-page","items"),
    INVENTORY("inventory","inventory-reservation",null), INVENTORY_ITEMS("inventory","inventory-reservation-items-page","items"),
    COUPON("promotion","coupon-reservation",null), REDEMPTIONS("promotion","coupon-redemptions-page","items"),
    PAYMENT("payment","payment-for-order",null), SHIPMENT("delivery","shipment-for-order",null),
    INVOICE("billing","invoice-for-order",null), INVOICE_ITEMS("billing","invoice-items-page","items"),
    CORRECTIONS("billing","invoice-corrections-page","corrections"),
    INVOICE_PDF("billing","invoice-pdf-representation",null), CORRECTION_PDF("billing","invoice-correction-pdf-representation",null),
    NOTIFICATIONS("notification","order-notifications","items"),
    RETURN_ITEMS("returns","return-items-page","items"), RETURN_EVENTS("returns","return-events-page","events");
    public final String service,scope,collection;
    ExportKind(String service,String scope,String collection){this.service=service;this.scope=scope;this.collection=collection;}
    public boolean local(){return service.equals("customer");}
    public boolean pdf(){return this==INVOICE_PDF||this==CORRECTION_PDF;}
    public boolean optional(){return this==PAYMENT||this==SHIPMENT||this==INVOICE;}
    public String path(long parent,long detail,long cursor){
        String order="/me/data-export/orders/"+parent;
        String path=switch(this){
            case PROFILE -> "/customers/me/data-export"; case METHODS -> "/customers/me/data-export/security/methods";
            case EVENTS -> "/customers/me/data-export/security/events";case PASSKEYS -> "/customers/me/data-export/security/passkeys";
            case RECOVERY -> "/customers/me/data-export/security/recovery-codes";
            case WISHLIST -> "/wishlists/me/data-export";case REVIEWS -> "/reviews/me/data-export";
            case RECOMMENDATIONS -> "/recommendations/me/data-export";case ORDERS -> "/orders/me/data-export";case RETURNS -> "/returns/me/data-export";
            case ORDER_ITEMS -> "/orders/me/data-export/"+parent+"/items";
            case INVENTORY -> "/inventory"+order;case INVENTORY_ITEMS -> "/inventory"+order+"/items";
            case COUPON -> "/promotions"+order;case REDEMPTIONS -> "/promotions"+order+"/redemptions";
            case PAYMENT -> "/payments"+order;case SHIPMENT -> "/deliveries"+order;case INVOICE -> "/invoices"+order;
            case INVOICE_ITEMS -> "/invoices"+order+"/items";case CORRECTIONS -> "/invoices"+order+"/corrections";
            case INVOICE_PDF -> "/invoices"+order+"/pdf";case CORRECTION_PDF -> "/invoices"+order+"/corrections/"+detail+"/pdf";
            case NOTIFICATIONS -> "/notifications"+order;
            case RETURN_ITEMS -> "/returns/me/data-export/"+parent+"/items";case RETURN_EVENTS -> "/returns/me/data-export/"+parent+"/events";
        };
        return collection==null?path:path+"?size=50&cursor="+cursor;
    }
}
