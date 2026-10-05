package com.example.quickbid.quickbid.service;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

@Service
@ConditionalOnProperty(name = "app.mail.enabled", havingValue = "true")
@ConditionalOnProperty(name = "app.mail.provider", havingValue = "brevo")
public class BrevoMailService implements MailService {
	private static final Logger LOGGER = LoggerFactory.getLogger(BrevoMailService.class);
	private static final String USER_AGENT = "quickbid-backend/1.0";
	private static final String DEFAULT_SENDER_NAME = "QuickBid";

	private final HttpTransport transport;
	private final ObjectMapper json;
	private final MailTemplates templates;
	private final Sender sender;
	private final String apiKey;
	private final URI apiUri;
	private final Duration requestTimeout;

	@Autowired
	public BrevoMailService(MailTemplates templates,
			@Value("${app.mail.from}") String from,
			@Value("${app.brevo.api-key:}") String apiKey,
			@Value("${app.brevo.api-url:https://api.brevo.com/v3/smtp/email}") String apiUrl,
			@Value("${app.brevo.timeout-seconds:10}") int timeoutSeconds) {
		this(httpTransport(timeoutSeconds), new ObjectMapper(), templates, from, apiKey, apiUrl, timeoutSeconds);
	}

	BrevoMailService(HttpTransport transport, ObjectMapper json, MailTemplates templates,
			String from, String apiKey, String apiUrl, int timeoutSeconds) {
		this.transport = transport;
		this.json = json;
		this.templates = templates;
		this.sender = parseSender(from);
		this.apiKey = required(apiKey, "APP_BREVO_API_KEY");
		this.apiUri = requiredHttpsUri(apiUrl);
		if (timeoutSeconds <= 0) throw new MailDeliveryException("APP_BREVO_TIMEOUT_SECONDS debe ser positivo");
		this.requestTimeout = Duration.ofSeconds(timeoutSeconds);
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
			Map<String, Object> message = new LinkedHashMap<>();
			message.put("sender", Map.of("name", sender.name(), "email", sender.email()));
			message.put("to", List.of(Map.of("email", to)));
			message.put("subject", template.subject());
			message.put("textContent", template.textContent());
			if (template.htmlContent() != null) message.put("htmlContent", template.htmlContent());
			String payload = json.writeValueAsString(message);
			HttpRequest request = HttpRequest.newBuilder(apiUri)
					.timeout(requestTimeout)
					.header("api-key", apiKey)
					.header("Content-Type", "application/json")
					.header("Accept", "application/json")
					.header("User-Agent", USER_AGENT)
					.POST(HttpRequest.BodyPublishers.ofString(payload))
					.build();
			HttpResponse<Void> response = transport.send(request);
			if (response.statusCode() < 200 || response.statusCode() >= 300) {
				LOGGER.warn("HTTP mail delivery failed provider=brevo type={} status={}", type, response.statusCode());
				throw statusError(response.statusCode());
			}
		} catch (JsonProcessingException exception) {
			logTransportFailure(type, exception);
			throw new MailDeliveryException("No se pudo preparar el correo", exception);
		} catch (IOException exception) {
			logTransportFailure(type, exception);
			throw new MailDeliveryException("No se pudo conectar con el proveedor de correo", exception);
		} catch (InterruptedException exception) {
			Thread.currentThread().interrupt();
			logTransportFailure(type, exception);
			throw new MailDeliveryException("El envío de correo fue interrumpido", exception);
		}
	}

	private MailDeliveryException statusError(int status) {
		return switch (status) {
			case 400, 422 -> new MailDeliveryException("El proveedor rechazó los datos del correo");
			case 401, 403 -> new MailDeliveryException("El proveedor rechazó la autenticación de correo");
			case 429 -> new MailDeliveryException("El proveedor limitó temporalmente los envíos");
			default -> status >= 500
					? new MailDeliveryException("El proveedor de correo no está disponible")
					: new MailDeliveryException("Respuesta inesperada del proveedor de correo");
		};
	}

	private void logTransportFailure(String type, Exception exception) {
		LOGGER.warn("HTTP mail delivery failed provider=brevo type={} error={}",
				type, exception.getClass().getSimpleName());
	}

	private Sender parseSender(String value) {
		String configured = required(value, "APP_MAIL_FROM");
		boolean hasAngleBracket = configured.contains("<") || configured.contains(">");
		if (!hasAngleBracket) return new Sender(DEFAULT_SENDER_NAME, validSenderEmail(configured));

		int open = configured.indexOf('<');
		int close = configured.indexOf('>');
		if (open < 0 || close < 0 || close < open || close != configured.length() - 1
				|| configured.indexOf('<', open + 1) >= 0 || configured.indexOf('>', close + 1) >= 0) {
			throw new MailDeliveryException("APP_MAIL_FROM tiene un formato invalido");
		}
		String name = configured.substring(0, open).trim();
		String email = configured.substring(open + 1, close).trim();
		if (email.isEmpty()) throw new MailDeliveryException("APP_MAIL_FROM debe incluir un email");
		return new Sender(name.isEmpty() ? DEFAULT_SENDER_NAME : name, validSenderEmail(email));
	}

	private String validSenderEmail(String email) {
		if (!email.contains("@")) throw new MailDeliveryException("APP_MAIL_FROM debe incluir un email valido");
		return email;
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

	private URI requiredHttpsUri(String value) {
		String url = required(value, "APP_BREVO_API_URL");
		try {
			URI uri = URI.create(url);
			if (!"https".equalsIgnoreCase(uri.getScheme()) || uri.getHost() == null) {
				throw new MailDeliveryException("APP_BREVO_API_URL debe ser una URL HTTPS valida");
			}
			return uri;
		} catch (IllegalArgumentException exception) {
			throw new MailDeliveryException("APP_BREVO_API_URL debe ser una URL HTTPS valida", exception);
		}
	}

	private static HttpTransport httpTransport(int timeoutSeconds) {
		if (timeoutSeconds <= 0) throw new MailDeliveryException("APP_BREVO_TIMEOUT_SECONDS debe ser positivo");
		HttpClient client = HttpClient.newBuilder()
				.connectTimeout(Duration.ofSeconds(timeoutSeconds))
				.build();
		return request -> client.send(request, HttpResponse.BodyHandlers.discarding());
	}

	private record Sender(String name, String email) {
	}

	@FunctionalInterface
	interface HttpTransport {
		HttpResponse<Void> send(HttpRequest request) throws IOException, InterruptedException;
	}
}
