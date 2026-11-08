package org.demo.notificationservice.web;

import jakarta.validation.Valid;
import org.demo.notificationservice.notification.NotificationService;
import org.demo.notificationservice.notification.NotificationStatus;
import org.demo.notificationservice.notification.NotificationDeadLetterService;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/notifications")
public class NotificationController {

    private final NotificationService notificationService;
    private final NotificationDeadLetterService deadLetterService;

    public NotificationController(
            NotificationService notificationService,
            NotificationDeadLetterService deadLetterService
    ) {
        this.notificationService = notificationService;
        this.deadLetterService = deadLetterService;
    }

    @PostMapping
    public ResponseEntity<NotificationResponse> create(
            @Valid @RequestBody CreateNotificationRequest request
    ) {
        if(java.util.Set.of(org.demo.notificationservice.notification.NotificationEventType.CUSTOMER_REGISTERED,org.demo.notificationservice.notification.NotificationEventType.PASSWORD_RESET_REQUESTED).contains(request.eventType())){
            org.demo.platformsecurity.Access.require(org.demo.platformsecurity.Access.actor().internal()&&"customer-service".equals(org.demo.platformsecurity.Access.actor().service()));
            Long customer=request.customerId();
            if(customer==null&&request.eventType()==org.demo.notificationservice.notification.NotificationEventType.CUSTOMER_REGISTERED)customer=request.sourceId();
            if(customer==null||request.eventType()==org.demo.notificationservice.notification.NotificationEventType.CUSTOMER_REGISTERED&&!customer.equals(request.sourceId()))throw new org.springframework.web.server.ResponseStatusException(HttpStatus.BAD_REQUEST,"Verified account subject is required");
            request=new CreateNotificationRequest(request.eventType(),request.sourceId(),request.recipient(),request.channel(),request.subject(),request.message(),request.simulateFailure(),request.invoiceId(),customer);
        }else if(request.customerId()!=null)throw new org.springframework.web.server.ResponseStatusException(HttpStatus.BAD_REQUEST,"Unexpected account subject");
        NotificationResponse response = notificationService.create(request);
        HttpStatus status = response.status() == NotificationStatus.FAILED
                ? HttpStatus.SERVICE_UNAVAILABLE
                : HttpStatus.CREATED;
        return ResponseEntity.status(status).body(response);
    }

    @GetMapping
    public ResponseEntity<List<NotificationResponse>> getAll(@org.springframework.web.bind.annotation.RequestParam(defaultValue = "0") long cursor,
            @org.springframework.web.bind.annotation.RequestParam(defaultValue = "50") int size,
            @org.springframework.web.bind.annotation.RequestParam(required = false) org.demo.notificationservice.notification.NotificationStatus status) {
        return notificationService.list(null, status, new org.demo.platformsecurity.ListWindow(cursor, size)).response();
    }

    @GetMapping("/{id}")
    public NotificationResponse get(@PathVariable Long id) {
        return notificationService.get(id);
    }

    @GetMapping("/source/{sourceId}")
    public ResponseEntity<List<NotificationResponse>> getBySourceId(@PathVariable Long sourceId,
            @org.springframework.web.bind.annotation.RequestParam(defaultValue = "0") long cursor,
            @org.springframework.web.bind.annotation.RequestParam(defaultValue = "50") int size) {
        return notificationService.list(sourceId, null, new org.demo.platformsecurity.ListWindow(cursor, size)).response();
    }

    @GetMapping("/dead-letter")
    public ResponseEntity<List<NotificationDeadLetterResponse>> getOpenDeadLetters(
            @org.springframework.web.bind.annotation.RequestParam(defaultValue = "0") long cursor,
            @org.springframework.web.bind.annotation.RequestParam(defaultValue = "50") int size) {
        return deadLetterService.list(new org.demo.platformsecurity.ListWindow(cursor, size)).response();
    }

    @PostMapping("/dead-letter/{id}/replay")
    public ResponseEntity<NotificationResponse> replayDeadLetter(@PathVariable Long id) {
        NotificationResponse response = notificationService.replayDeadLetter(id);
        HttpStatus status = response.status() == NotificationStatus.FAILED
                ? HttpStatus.SERVICE_UNAVAILABLE
                : HttpStatus.OK;
        return ResponseEntity.status(status).body(response);
    }
}
