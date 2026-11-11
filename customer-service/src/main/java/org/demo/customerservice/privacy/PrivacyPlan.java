package org.demo.customerservice.privacy;

import org.demo.platformsecurity.Access;
import java.util.*;

public final class PrivacyPlan {
    private PrivacyPlan(){}
    public record Scope(String service,String action,String stage){}
    public static final List<Scope> SCOPES=List.of(
        new Scope("customer-service","REMOVE_ACCOUNT_CORE","A43.34"),new Scope("wishlist-service","REMOVE_PRIVATE_ROWS","A43.34"),
        new Scope("recommendation-service","REMOVE_PRIVATE_ROWS","A43.34"),new Scope("review-service","REMOVE_CONTENT_KEEP_KEYS","A43.34"),
        new Scope("chat-service","SEPARATE_PROOF_REQUIRED","A43.34/A43.39"),new Scope("order-service","WAIT_FOR_DOMAIN_PROCESS","A43.35"),
        new Scope("inventory-service","WAIT_FOR_DOMAIN_PROCESS","A43.35"),new Scope("promotion-service","WAIT_FOR_DOMAIN_PROCESS","A43.35"),
        new Scope("delivery-service","WAIT_FOR_DOMAIN_PROCESS","A43.35"),new Scope("returns-service","WAIT_FOR_DOMAIN_PROCESS","A43.35"),
        new Scope("payment-service","RETENTION_POLICY_PENDING","A43.36/A43.40"),new Scope("billing-service","RETENTION_POLICY_PENDING","A43.36/A43.40"),
        new Scope("notification-service","MANUAL_POLICY_PENDING","A43.36"),new Scope("product-service","NOT_ACCOUNT_SCOPED","A43.37"));
    public static final Set<String> ACCOUNT_SERVICES=Set.of("customer-service","wishlist-service","recommendation-service","review-service","chat-service");
    public static final Set<String> COMMERCE_SERVICES=Set.of("order-service","inventory-service","promotion-service","delivery-service","returns-service");
    public static String commercePolicyHash(){return org.demo.platformsecurity.privacy.ErasurePolicies.commerceHash();}
    public static String cleanupPolicyHash(){return org.demo.platformsecurity.privacy.ErasurePolicies.coreHash();}
    public static String binding(UUID id,long customer,java.time.Instant created,UUID key){return Access.hash("A43.37/v1:external-journal-required:id-only-marker:no-ttl-until-A43.40:status-7-days:"+cleanupPolicyHash()+":"+commercePolicyHash()+":"+org.demo.platformsecurity.privacy.FinancePolicy.hash()+":"+SCOPES+":"+id+":"+customer+":"+created+":"+key);}
}
