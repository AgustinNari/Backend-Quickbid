package com.example.quickbid.quickbid.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.mail.MailException;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.stereotype.Service;

@Service
@ConditionalOnProperty(name = "app.mail.enabled", havingValue = "true")
@ConditionalOnProperty(name = "app.mail.provider", havingValue = "smtp", matchIfMissing = true)
public class SmtpMailService implements MailService {
	private static final Logger LOGGER = LoggerFactory.getLogger(SmtpMailService.class);

	private final JavaMailSender sender;
	private final MailTemplates templates;
	private final String from;

	public SmtpMailService(JavaMailSender sender, MailTemplates templates,
			@Value("${app.mail.from}") String from,
			@Value("${spring.mail.host:}") String host,
			@Value("${spring.mail.username:}") String username,
			@Value("${spring.mail.password:}") String password,
			@Value("${spring.mail.properties.mail.smtp.auth:true}") boolean smtpAuth) {
		this.sender = sender;
		this.templates = templates;
		this.from = requiredEmail(from, "APP_MAIL_FROM");
		required(host, "SPRING_MAIL_HOST");
		if (smtpAuth) {
			required(username, "SPRING_MAIL_USERNAME");
			required(password, "SPRING_MAIL_PASSWORD");
		}
	}

	@Override
	public void sendToken(String purpose, String recipient, String token) {
		send(recipient, templates.token(purpose, token), purpose);
	}

	@Override
	public void sendNotification(String recipient, String type) {
		send(recipient, templates.notification(type), type);
	}

	private void send(String recipient, MailTemplates.Message template, String type) {
		String to = requiredEmail(recipient, "destinatario");
		try {
			SimpleMailMessage message = new SimpleMailMessage();
			message.setFrom(from);
			message.setTo(to);
			message.setSubject(template.subject());
			message.setText(template.body());
			sender.send(message);
		} catch (MailException exception) {
			LOGGER.warn("SMTP delivery failed type={} error={}", type, rootCauseType(exception));
			throw new MailDeliveryException("No se pudo enviar el correo", exception);
		}
	}

	private String rootCauseType(Throwable exception) {
		Throwable cause = exception;
		while (cause.getCause() != null && cause.getCause() != cause) cause = cause.getCause();
		return cause.getClass().getSimpleName();
	}

	private String required(String value, String field) {
		if (value == null || value.isBlank()) throw new MailDeliveryException("Falta configurar " + field);
		return value.trim();
	}

	private String requiredEmail(String value, String field) {
		String email = required(value, field);
		if (!email.contains("@")) throw new MailDeliveryException("Correo invalido: " + field);
		return email;
	}
}
