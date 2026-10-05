package com.example.quickbid.quickbid.service;

import jakarta.mail.Session;
import jakarta.mail.internet.MimeMessage;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.mail.MailSendException;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.example.quickbid.quickbid.repository.app.CuentaAppRepository;

import java.io.InputStream;
import java.util.Properties;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;

class MailServiceTests {

    @Test
    void usesSimulatedMailServiceWhenDisabled() {
        new ApplicationContextRunner()
                .withUserConfiguration(MailTemplates.class, SimulatedMailService.class,
                        SmtpMailService.class, ResendMailService.class, BrevoMailService.class,
                        InvalidMailProviderService.class)
                .withBean(JavaMailSender.class, StubJavaMailSender::new)
                .withBean(ObjectMapper.class, ObjectMapper::new)
                .withPropertyValues("app.mail.enabled=false")
                .run(context -> assertInstanceOf(SimulatedMailService.class, context.getBean(MailService.class)));
    }

    @Test
    void usesSmtpMailServiceWhenEnabled() {
        new ApplicationContextRunner()
                .withUserConfiguration(MailTemplates.class, SimulatedMailService.class,
                        SmtpMailService.class, ResendMailService.class, BrevoMailService.class,
                        InvalidMailProviderService.class)
                .withBean(JavaMailSender.class, StubJavaMailSender::new)
                .withBean(ObjectMapper.class, ObjectMapper::new)
                .withPropertyValues(
                        "app.mail.enabled=true",
                        "app.mail.from=no-reply@quickbid.demo",
                        "spring.mail.host=smtp.quickbid.demo",
                        "spring.mail.username=usuario-smtp",
                        "spring.mail.password=password-smtp"
                )
                .run(context -> assertInstanceOf(SmtpMailService.class, context.getBean(MailService.class)));
    }

    @Test
    void usesResendMailServiceWhenEnabledAndSelected() {
        new ApplicationContextRunner()
                .withUserConfiguration(MailTemplates.class, SimulatedMailService.class,
                        SmtpMailService.class, ResendMailService.class, BrevoMailService.class,
                        InvalidMailProviderService.class)
                .withBean(JavaMailSender.class, StubJavaMailSender::new)
                .withBean(ObjectMapper.class, ObjectMapper::new)
                .withPropertyValues(
                        "app.mail.enabled=true",
                        "app.mail.provider=resend",
                        "app.mail.from=no-reply@quickbid.demo",
                        "app.resend.api-key=test-only-resend-key",
                        "app.resend.api-url=https://api.resend.test/emails"
                )
                .run(context -> assertInstanceOf(ResendMailService.class, context.getBean(MailService.class)));
    }

    @Test
    void startsWithBrevoWithoutExternalObjectMapperBean() {
        new ApplicationContextRunner()
                .withUserConfiguration(MailTemplates.class, SimulatedMailService.class,
                        SmtpMailService.class, ResendMailService.class, BrevoMailService.class,
                        InvalidMailProviderService.class)
                .withBean(JavaMailSender.class, StubJavaMailSender::new)
                .withPropertyValues(
                        "app.mail.enabled=true",
                        "app.mail.provider=brevo",
                        "app.mail.from=QuickBid App <ayuda.breakbuddy@gmail.com>",
                        "app.brevo.api-key=test-key",
                        "app.brevo.api-url=https://api.brevo.test/v3/smtp/email",
                        "app.brevo.timeout-seconds=10"
                )
                .run(context -> {
                    assertInstanceOf(BrevoMailService.class, context.getBean(MailService.class));
                    assertEquals(1, context.getBeansOfType(MailService.class).size());
                });
    }

    @Test
    void invalidEnabledProviderFailsClearly() {
        new ApplicationContextRunner()
                .withUserConfiguration(MailTemplates.class, SimulatedMailService.class,
                        SmtpMailService.class, ResendMailService.class, BrevoMailService.class,
                        InvalidMailProviderService.class)
                .withBean(JavaMailSender.class, StubJavaMailSender::new)
                .withBean(ObjectMapper.class, ObjectMapper::new)
                .withPropertyValues(
                        "app.mail.enabled=true",
                        "app.mail.provider=desconocido"
                )
                .run(context -> {
                    assertNotNull(context.getStartupFailure());
                    assertTrue(allMessages(context.getStartupFailure())
                            .contains("APP_MAIL_PROVIDER no soportado; usar smtp, resend o brevo"));
                });
    }

    @Test
    void simulatedMailDoesNotRetainRawToken() {
        SimulatedMailService mail = new SimulatedMailService(new MailTemplates("https://frontend.quickbid.demo"));

        mail.sendToken("recuperacion", "usuario@quickbid.demo", "token-super-secreto");

        assertEquals(1, mail.deliveries().size());
        assertEquals("Recupera tu clave de QuickBid", mail.deliveries().get(0).subject());
        assertTrue(mail.deliveries().get(0).preview().contains("token-redactado"));
        assertFalse(mail.deliveries().toString().contains("token-super-secreto"));
    }

    @Test
    void simulatedMailValidatesKnownTemplateTypes() {
        SimulatedMailService mail = new SimulatedMailService(new MailTemplates("https://frontend.quickbid.demo"));

        assertThrows(IllegalArgumentException.class,
                () -> mail.sendToken("desconocido", "usuario@quickbid.demo", "token"));
        assertThrows(IllegalArgumentException.class,
                () -> mail.sendNotification("usuario@quickbid.demo", "desconocida"));
    }

    @Test
    void smtpMailBuildsRecoveryLinkAndSendsMessage() {
        StubJavaMailSender sender = new StubJavaMailSender();
        MailTemplates templates = new MailTemplates("https://frontend.quickbid.demo");
        SmtpMailService mail = smtp(sender, templates);

        mail.sendToken("recuperacion", "usuario@quickbid.demo", "token con /+");

        assertNotNull(sender.lastMessage);
        assertEquals("no-reply@quickbid.demo", sender.lastMessage.getFrom());
        assertEquals("usuario@quickbid.demo", sender.lastMessage.getTo()[0]);
        assertTrue(sender.lastMessage.getText().contains(
                "https://frontend.quickbid.demo/recuperar-clave?token=token+con+%2F%2B"
        ));
        assertTrue(sender.lastMessage.getText().contains("Si no solicitaste esto, podés ignorar este mensaje."));
    }

    @Test
    void tokenTemplatesBuildAndroidDeepLinksWithoutDuplicatedSlashes() {
        MailTemplates templates = new MailTemplates("quickbid://auth/");

        MailTemplates.Message setup = templates.token("registro", "token con /+");
        MailTemplates.Message reset = templates.token("recuperacion", "token con /+");

        assertTrue(setup.body().contains("quickbid://auth/completar-registro?token=token+con+%2F%2B"));
        assertTrue(reset.body().contains("quickbid://auth/recuperar-clave?token=token+con+%2F%2B"));
    }

    @Test
    void tokenTemplatesBuildAndroidDeepLinksWhenBaseUrlHasNoTrailingSlash() {
        MailTemplates templates = new MailTemplates("quickbid://auth");

        MailTemplates.Message setup = templates.token("registro", "abc");
        MailTemplates.Message reset = templates.token("recuperacion", "abc");

        assertTrue(setup.body().contains("quickbid://auth/completar-registro?token=abc"));
        assertTrue(reset.body().contains("quickbid://auth/recuperar-clave?token=abc"));
    }

    @Test
    void tokenTemplatesUsePublicHttpsLinksWithTextAndHtmlFallbacks() {
        MailTemplates templates = new MailTemplates(
                "quickbid://auth", "https://quickbid-backend.demo/");

        MailTemplates.Message setup = templates.token("registro", "token con /+");
        MailTemplates.Message reset = templates.token("recuperacion", "token con /+");

        assertTrue(setup.textContent().contains(
                "https://quickbid-backend.demo/auth-links/completar-registro?token=token+con+%2F%2B"));
        assertTrue(reset.textContent().contains(
                "https://quickbid-backend.demo/auth-links/recuperar-clave?token=token+con+%2F%2B"));
        assertTrue(setup.textContent().contains("token con /+"));
        assertTrue(reset.textContent().contains("token con /+"));
        assertTrue(setup.htmlContent().contains("Continuar en QuickBid"));
        assertTrue(reset.htmlContent().contains("Continuar en QuickBid"));

        MailTemplates.Message unusual = templates.token("registro", "<script>alert('x')</script>");
        assertFalse(unusual.htmlContent().contains("<script>"));
        assertTrue(unusual.htmlContent().contains("&lt;script&gt;"));
        assertTrue(unusual.htmlContent().contains("%3Cscript%3E"));
    }

    @Test
    void smtpFailureIsExposedAsControlledMailError() {
        JavaMailSender sender = new StubJavaMailSender() {
            @Override
            public void send(SimpleMailMessage simpleMessage) throws MailSendException {
                throw new MailSendException("provider unavailable");
            }
        };
        SmtpMailService mail = smtp(sender, new MailTemplates("https://frontend.quickbid.demo"));

        assertThrows(
                MailDeliveryException.class,
                () -> mail.sendNotification("usuario@quickbid.demo", "multa_generada")
        );
    }

    @Test
    void smtpRequiresConfiguredSenderAddress() {
        assertThrows(MailDeliveryException.class,
                () -> new SmtpMailService(new StubJavaMailSender(),
                        new MailTemplates("https://frontend.quickbid.demo"), "",
                        "smtp.quickbid.demo", "usuario", "password", true));
        assertThrows(MailDeliveryException.class,
                () -> new SmtpMailService(new StubJavaMailSender(),
                        new MailTemplates("https://frontend.quickbid.demo"), "no-es-email",
                        "smtp.quickbid.demo", "usuario", "password", true));
    }

    @Test
    void smtpRequiresHostAndCredentialsWhenAuthenticationIsEnabled() {
        MailTemplates templates = new MailTemplates("https://frontend.quickbid.demo");

        assertThrows(MailDeliveryException.class,
                () -> new SmtpMailService(new StubJavaMailSender(), templates,
                        "no-reply@quickbid.demo", "", "usuario", "password", true));
        assertThrows(MailDeliveryException.class,
                () -> new SmtpMailService(new StubJavaMailSender(), templates,
                        "no-reply@quickbid.demo", "smtp.quickbid.demo", "", "password", true));
        assertThrows(MailDeliveryException.class,
                () -> new SmtpMailService(new StubJavaMailSender(), templates,
                        "no-reply@quickbid.demo", "smtp.quickbid.demo", "usuario", "", true));
    }

    @Test
    void smtpAllowsBlankCredentialsWhenAuthenticationIsDisabled() {
        SmtpMailService mail = new SmtpMailService(
                new StubJavaMailSender(),
                new MailTemplates("https://frontend.quickbid.demo"),
                "no-reply@quickbid.demo",
                "localhost",
                "",
                "",
                false
        );

        assertNotNull(mail);
    }

    @Test
    void smtpRejectsInvalidRecipientAsControlledMailError() {
        SmtpMailService mail = smtp(
                new StubJavaMailSender(),
                new MailTemplates("https://frontend.quickbid.demo")
        );

        assertThrows(MailDeliveryException.class,
                () -> mail.sendNotification("destinatario-invalido", "multa_generada"));
    }

    @Test
    void disabledBusinessNotificationsDoNotReachAnyMailProvider() {
        CuentaAppRepository accounts = mock(CuentaAppRepository.class);
        AtomicBoolean delivered = new AtomicBoolean();
        MailService provider = new MailService() {
            @Override public void sendToken(String purpose, String recipient, String token) { delivered.set(true); }
            @Override public void sendNotification(String recipient, String type) { delivered.set(true); }
        };

        new MailNotificationService(accounts, provider, false).critical(3001L, "multa_generada");

        assertFalse(delivered.get());
        verifyNoInteractions(accounts);
    }

    private SmtpMailService smtp(JavaMailSender sender, MailTemplates templates) {
        return new SmtpMailService(
                sender,
                templates,
                "no-reply@quickbid.demo",
                "smtp.quickbid.demo",
                "usuario-smtp",
                "password-smtp",
                true
        );
    }

    private String allMessages(Throwable error) {
        StringBuilder messages = new StringBuilder();
        Throwable current = error;
        while (current != null && current.getCause() != current) {
            if (current.getMessage() != null) messages.append(current.getMessage()).append('\n');
            current = current.getCause();
        }
        return messages.toString();
    }

    private static class StubJavaMailSender implements JavaMailSender {
        private SimpleMailMessage lastMessage;

        @Override
        public MimeMessage createMimeMessage() {
            return new MimeMessage(Session.getInstance(new Properties()));
        }

        @Override
        public MimeMessage createMimeMessage(InputStream contentStream) {
            return createMimeMessage();
        }

        @Override
        public void send(MimeMessage mimeMessage) {
        }

        @Override
        public void send(MimeMessage... mimeMessages) {
        }

        @Override
        public void send(SimpleMailMessage simpleMessage) {
            lastMessage = simpleMessage;
        }

        @Override
        public void send(SimpleMailMessage... simpleMessages) {
            if (simpleMessages.length > 0) {
                lastMessage = simpleMessages[0];
            }
        }
    }
}
