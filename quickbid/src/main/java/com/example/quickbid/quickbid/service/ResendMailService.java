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
@ConditionalOnProperty(name = "app.mail.provider", havingValue = "resend")
public class ResendMailService implements MailService {
	private static final Logger LOGGER = LoggerFactory.getLogger(ResendMailService.class);
	private static final String USER_AGENT = "quickbid-backend/1.0";

	private final HttpTransport transport;
	private final ObjectMapper json;
	private final MailTemplates templates;
	private final String from;
	private final String apiKey;
	private final URI apiUri;
	private final Duration requestTimeout;

	@Autowired
	public ResendMailService(ObjectMapper json, MailTemplates templates,
			@Value("${app.mail.from}") String from,
			@Value("${app.resend.api-key:}") String apiKey,
			@Value("${app.resend.api-url:}") String apiUrl,
			@Value("${app.resend.timeout-seconds:10}") int timeoutSeconds) {
		this(httpTransport(timeoutSeconds), json, templates, from, apiKey, apiUrl, timeoutSeconds);
	}

	ResendMailService(HttpTransport transport, ObjectMapper json, MailTemplates templates,
			String from, String apiKey, String apiUrl, int timeoutSeconds) {
		this.transport = transport;
		this.json = json;
		this.templates = templates;
		this.from = requiredEmail(from, "APP_MAIL_FROM");
		this.apiKey = required(apiKey, "APP_RESEND_API_KEY");
		this.apiUri = requiredHttpsUri(apiUrl);
		if (timeoutSeconds <= 0) throw new MailDeliveryException("APP_RESEND_TIMEOUT_SECONDS debe ser positivo");
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
			message.put("from", from);
			message.put("to", List.of(to));
			message.put("subject", template.subject());
			message.put("text", template.textContent());
			if (template.htmlContent() != null) message.put("html", template.htmlContent());
			String payload = json.writeValueAsString(message);
			HttpRequest request = HttpRequest.newBuilder(apiUri)
					.timeout(requestTimeout)
					.header("Authorization", "Bearer " + apiKey)
					.header("Content-Type", "application/json")
					.header("Accept", "application/json")
					.header("User-Agent", USER_AGENT)
					.POST(HttpRequest.BodyPublishers.ofString(payload))
					.build();
			HttpResponse<Void> response = transport.send(request);
			if (response.statusCode() < 200 || response.statusCode() >= 300) {
				LOGGER.warn("HTTP mail delivery failed provider=resend type={} status={}", type, response.statusCode());
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
		LOGGER.warn("HTTP mail delivery failed provider=resend type={} error={}",
				type, exception.getClass().getSimpleName());
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
		String url = required(value, "APP_RESEND_API_URL");
		try {
			URI uri = URI.create(url);
			if (!"https".equalsIgnoreCase(uri.getScheme()) || uri.getHost() == null) {
				throw new MailDeliveryException("APP_RESEND_API_URL debe ser una URL HTTPS valida");
			}
			return uri;
		} catch (IllegalArgumentException exception) {
			throw new MailDeliveryException("APP_RESEND_API_URL debe ser una URL HTTPS valida", exception);
		}
	}

	private static HttpTransport httpTransport(int timeoutSeconds) {
		if (timeoutSeconds <= 0) throw new MailDeliveryException("APP_RESEND_TIMEOUT_SECONDS debe ser positivo");
		HttpClient client = HttpClient.newBuilder()
				.connectTimeout(Duration.ofSeconds(timeoutSeconds))
				.build();
		return request -> client.send(request, HttpResponse.BodyHandlers.discarding());
	}

	@FunctionalInterface
	interface HttpTransport {
		HttpResponse<Void> send(HttpRequest request) throws IOException, InterruptedException;
	}
}
