package org.demo.notificationservice.privacy;

import org.demo.notificationservice.notification.*;
import org.demo.notificationservice.web.CreateNotificationRequest;
import org.demo.platformevents.EventIdentity;
import org.demo.platformsecurity.privacy.PrivacySql;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.http.HttpStatus;
import javax.sql.DataSource;
import java.util.*;

@Component
public class NotificationPrivacyGuard {
    private final PrivacySql privacy;
    public NotificationPrivacyGuard(DataSource source){privacy=new PrivacySql(source);}
    public boolean sealed(NotificationEventType type,long source,Long customer){
        if(type==NotificationEventType.CUSTOMER_REGISTERED)return privacy.accountFenced(customer==null?source:customer);
        if(type==NotificationEventType.PASSWORD_RESET_REQUESTED)return customer!=null&&privacy.accountFenced(customer);
        return !type.chatEvent()&&privacy.orderSealed(source);
    }
    public Long existing(CreateNotificationRequest request){
        if(!sealed(request.eventType(),request.sourceId(),request.customerId()))return null;
        var rows=privacy.jdbc().queryForList("SELECT id FROM notifications WHERE event_type=? AND source_id=? AND coalesce(recipient_hash,privacy_identity_hash(recipient))=?",Long.class,request.eventType().name(),request.sourceId(),EventIdentity.hash(request.recipient()));
        if(rows.isEmpty())throw new ResponseStatusException(HttpStatus.CONFLICT,"PRIVACY_NOTIFICATION_FENCED");return rows.getFirst();
    }
}
