package org.demo.notificationservice.notification;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.mail.MailSendException;
import org.springframework.mail.javamail.JavaMailSenderImpl;
import org.springframework.test.context.ActiveProfiles;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.CopyOnWriteArrayList;
import static org.assertj.core.api.Assertions.*;

@SpringBootTest(properties = {"DB_URL=jdbc:h2:mem:prodmail;MODE=PostgreSQL;DB_CLOSE_DELAY=-1", "DB_USERNAME=sa", "DB_PASSWORD=",
        "spring.flyway.enabled=false", "spring.jpa.hibernate.ddl-auto=create-drop", "management.health.redis.enabled=false",
        "MAIL_HOST=127.0.0.1", "MAIL_PORT=2525", "MAIL_USERNAME=fixture", "MAIL_PASSWORD=fixture", "MAIL_FROM=sender@audit.invalid",
        "REDIS_URL=redis://127.0.0.1:16379",
        "notification.retry.enabled=false", "messaging.consumer.enabled=false"})
@ActiveProfiles("prod")
class ProductionMailConfigurationTest {
    @Autowired JavaMailSenderImpl sender;
    @Autowired NotificationDeliveryService delivery;

    @Test void productionSelectsImplementedSmtpAndRequiresVerifiedStartTls() {
        assertThat(delivery).isInstanceOf(SmtpNotificationDeliveryService.class);
        for (String key : new String[]{"mail.smtp.auth", "mail.smtp.starttls.enable", "mail.smtp.starttls.required", "mail.smtp.ssl.checkserveridentity"})
            assertThat(sender.getJavaMailProperties().getProperty(key)).as(key).isEqualTo("true");
    }

    @Test void productionCannotSendCredentialsOrMailToAServerWithoutStartTls() throws Exception {
        var commands = new CopyOnWriteArrayList<String>();
        int originalPort = sender.getPort();
        try (var server = new ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"))) {
            server.setSoTimeout(3000);
            var worker = Thread.ofPlatform().start(() -> {
                try (var socket = server.accept()) {
                    socket.setSoTimeout(1500);
                    var input = new BufferedReader(new InputStreamReader(socket.getInputStream(), StandardCharsets.US_ASCII));
                    var output = new PrintWriter(socket.getOutputStream(), true, StandardCharsets.US_ASCII);
                    output.print("220 fixture SMTP\r\n"); output.flush();
                    String line;
                    while ((line = input.readLine()) != null) {
                        String command = line.split(" ", 2)[0].toUpperCase(); commands.add(command);
                        if (command.equals("EHLO")) output.print("250-fixture\r\n250 AUTH PLAIN LOGIN\r\n");
                        else if (command.equals("QUIT")) { output.print("221 bye\r\n"); output.flush(); break; }
                        else output.print("535 fixture rejects insecure authentication\r\n");
                        output.flush();
                    }
                } catch (IOException expectedAfterClientCloses) { }
            });
            sender.setPort(server.getLocalPort());
            var message = sender.createMimeMessage();
            message.setFrom("sender@audit.invalid"); message.setRecipients(jakarta.mail.Message.RecipientType.TO, "recipient@audit.invalid");
            message.setSubject("TLS fixture"); message.setText("must not leave the client without TLS");
            assertThatThrownBy(() -> sender.send(message)).isInstanceOf(MailSendException.class);
            worker.join(3000);
            assertThat(worker.isAlive()).isFalse();
            assertThat(commands).contains("EHLO").doesNotContain("AUTH", "MAIL", "RCPT", "DATA");
        } finally { sender.setPort(originalPort); }
    }
}
