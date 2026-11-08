package org.demo.notificationservice.notification;

import jakarta.mail.MessagingException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.MimeMessageHelper;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.stereotype.Component;

import java.util.UUID;

@Component
@ConditionalOnProperty(
        name = "notification.delivery.provider",
        havingValue = "smtp",
        matchIfMissing = true
)
public class SmtpNotificationDeliveryService implements NotificationDeliveryService {

    private final JavaMailSender mailSender;
    private final BillingPdfClient billingPdfClient;
    private final String fromAddress;
    private final java.util.concurrent.Semaphore sendSlots=new java.util.concurrent.Semaphore(4);
    private final java.util.Set<Thread> sending=java.util.concurrent.ConcurrentHashMap.newKeySet();
    @jakarta.annotation.PreDestroy void stopSending() { sending.forEach(Thread::interrupt); }

    public SmtpNotificationDeliveryService(
            JavaMailSender mailSender,
            BillingPdfClient billingPdfClient,
            @Value("${notification.delivery.from}") String fromAddress
    ) {
        this.mailSender = mailSender;
        this.billingPdfClient = billingPdfClient;
        this.fromAddress = fromAddress;
    }

    @Override
    public NotificationDeliveryResult deliver(Notification notification, boolean simulateFailure) {
        notification.requireDeliveryContent();
        if (simulateFailure) {
            return NotificationDeliveryResult.failed(
                    "Notification delivery declined by the simulation"
            );
        }

        if (notification.getChannel() != NotificationChannel.EMAIL) {
            return NotificationDeliveryResult.failed(
                    "Only EMAIL delivery is implemented for the SMTP provider"
            );
        }

        try {
            var message = mailSender.createMimeMessage();
            var helper = new MimeMessageHelper(message, true, "UTF-8");
            helper.setFrom(fromAddress);
            helper.setTo(notification.getRecipient());
            helper.setSubject(notification.getSubject());
            helper.setText(notification.getMessage(), false);
            if (notification.getInvoiceId() != null) {
                byte[] pdf = billingPdfClient.download(notification.getInvoiceId());
                helper.addAttachment("faktura-order-" + notification.getSourceId() + ".pdf", new ByteArrayResource(pdf));
            }
            org.demo.platformsecurity.RequestBudget.check();
            sendWithinBudget(message);
            return NotificationDeliveryResult.sent(
                    "SMTP-" + UUID.randomUUID().toString().substring(0, 8).toUpperCase()
            );
        } catch (UncertainDeliveryException uncertain) {
            return NotificationDeliveryResult.uncertain("SMTP outcome unknown; manual review required: "+uncertain.getMessage());
        } catch (MessagingException | RuntimeException exception) {
            return NotificationDeliveryResult.failed(
                    "SMTP delivery failed: " + exception.getMessage()
            );
        }
    }

    private static final class UncertainDeliveryException extends RuntimeException {
        UncertainDeliveryException(String message,Throwable cause) { super(message,cause); }
    }
    private void sendWithinBudget(jakarta.mail.internet.MimeMessage message) {
        var limit=org.demo.platformsecurity.RequestBudget.forCall(8000);
        if(!sendSlots.tryAcquire()) throw new IllegalStateException("SMTP delivery capacity exhausted");
        var task=new java.util.concurrent.FutureTask<Void>(() -> {
            try(var ignored=org.demo.platformsecurity.RequestBudget.open(8000,Long.toString(limit.epochMillis()))) {
                org.demo.platformsecurity.RequestBudget.check(); mailSender.send(message); return null;
            }
        });
        Thread thread;
        try {
            thread=Thread.ofVirtual().name("smtp-delivery").start(() -> {
                var current=Thread.currentThread(); sending.add(current);
                try { task.run(); } finally { sending.remove(current); sendSlots.release(); }
            });
        } catch(RuntimeException | Error failure) { sendSlots.release(); throw failure; }
        try { task.get(limit.remainingMillis(),java.util.concurrent.TimeUnit.MILLISECONDS); }
        catch(java.util.concurrent.ExecutionException failure) { throw new UncertainDeliveryException("SMTP send failed",failure.getCause()); }
        catch(java.util.concurrent.TimeoutException | org.demo.platformsecurity.RequestBudget.Exceeded expired) {
            task.cancel(true); thread.interrupt(); throw new UncertainDeliveryException("SMTP delivery time budget exhausted",expired);
        } catch(InterruptedException interrupted) {
            task.cancel(true); thread.interrupt(); Thread.currentThread().interrupt(); throw new UncertainDeliveryException("SMTP delivery interrupted",interrupted);
        }
    }
}
