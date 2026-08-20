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
        NotificationResponse response = notificationService.create(request);
        HttpStatus status = response.status() == NotificationStatus.FAILED
                ? HttpStatus.SERVICE_UNAVAILABLE
                : HttpStatus.CREATED;
        return ResponseEntity.status(status).body(response);
    }

    @GetMapping
    public List<NotificationResponse> getAll() {
        return notificationService.getAll();
    }

    @GetMapping("/{id}")
    public NotificationResponse get(@PathVariable Long id) {
        return notificationService.get(id);
    }

    @GetMapping("/source/{sourceId}")
    public List<NotificationResponse> getBySourceId(@PathVariable Long sourceId) {
        return notificationService.getBySourceId(sourceId);
    }

    @GetMapping("/dead-letter")
    public List<NotificationDeadLetterResponse> getOpenDeadLetters() {
        return deadLetterService.getOpenEntries().stream()
                .map(NotificationDeadLetterResponse::from)
                .toList();
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
