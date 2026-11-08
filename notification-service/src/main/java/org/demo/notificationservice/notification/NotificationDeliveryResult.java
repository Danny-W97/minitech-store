package org.demo.notificationservice.notification;

public record NotificationDeliveryResult(boolean sent,String providerReference,String failureReason,boolean uncertain) {
    public NotificationDeliveryResult(boolean sent,String reference,String failure) { this(sent,reference,failure,false); }
    public static NotificationDeliveryResult sent(String reference) { return new NotificationDeliveryResult(true,reference,null,false); }
    public static NotificationDeliveryResult failed(String reason) { return failure(reason,false); }
    public static NotificationDeliveryResult uncertain(String reason) { return failure(reason,true); }
    private static NotificationDeliveryResult failure(String reason,boolean uncertain) {
        if(reason!=null && reason.length()>500) reason=reason.substring(0,500);
        return new NotificationDeliveryResult(false,null,reason,uncertain);
    }
}
