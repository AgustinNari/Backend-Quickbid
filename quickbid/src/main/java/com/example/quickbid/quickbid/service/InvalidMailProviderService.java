package com.example.quickbid.quickbid.service;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.stereotype.Service;

@Service
@ConditionalOnExpression("${app.mail.enabled:false} and '${app.mail.provider:smtp}' != 'smtp' and '${app.mail.provider:smtp}' != 'resend' and '${app.mail.provider:smtp}' != 'brevo'")
public class InvalidMailProviderService implements MailService {

	public InvalidMailProviderService(@Value("${app.mail.provider:smtp}") String ignoredProvider) {
		throw new MailDeliveryException("APP_MAIL_PROVIDER no soportado; usar smtp, resend o brevo");
	}

	@Override
	public void sendToken(String purpose, String recipient, String token) {
		throw new IllegalStateException("Provider de mail invalido");
	}

	@Override
	public void sendNotification(String recipient, String type) {
		throw new IllegalStateException("Provider de mail invalido");
	}
}
