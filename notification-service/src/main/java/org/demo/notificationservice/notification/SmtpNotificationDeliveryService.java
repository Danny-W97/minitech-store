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
            mailSender.send(message);
            return NotificationDeliveryResult.sent(
                    "SMTP-" + UUID.randomUUID().toString().substring(0, 8).toUpperCase()
            );
        } catch (MessagingException | RuntimeException exception) {
            return NotificationDeliveryResult.failed(
                    "SMTP delivery failed: " + exception.getMessage()
            );
        }
    }
}
