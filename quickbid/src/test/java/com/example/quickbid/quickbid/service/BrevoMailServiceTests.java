package com.example.quickbid.quickbid.service;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpHeaders;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Flow;

import javax.net.ssl.SSLSession;

import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BrevoMailServiceTests {
	private final ObjectMapper json = new ObjectMapper();
	private final MailTemplates templates = new MailTemplates(
			"quickbid://auth", "https://quickbid-backend.demo");

	@Test
	void sendsExpectedBrevoPayloadAndHeaders() throws Exception {
		CapturingTransport transport = new CapturingTransport(201);
		BrevoMailService mail = service(transport, "QuickBid <no-reply@quickbid.demo>");

		mail.sendToken("recuperacion", "usuario@quickbid.demo", "token con /+");

		HttpRequest request = transport.request;
		assertEquals("POST", request.method());
		assertEquals(URI.create("https://api.brevo.test/v3/smtp/email"), request.uri());
		assertEquals("test-only-brevo-key", request.headers().firstValue("api-key").orElseThrow());
		assertEquals("application/json", request.headers().firstValue("Content-Type").orElseThrow());
		assertEquals("application/json", request.headers().firstValue("Accept").orElseThrow());
		assertEquals("quickbid-backend/1.0", request.headers().firstValue("User-Agent").orElseThrow());

		Map<String, Object> payload = json.readValue(body(request), new TypeReference<>() {});
		Map<?, ?> sender = (Map<?, ?>) payload.get("sender");
		Map<?, ?> recipient = (Map<?, ?>) ((java.util.List<?>) payload.get("to")).get(0);
		assertEquals("QuickBid", sender.get("name"));
		assertEquals("no-reply@quickbid.demo", sender.get("email"));
		assertEquals("usuario@quickbid.demo", recipient.get("email"));
		assertEquals("Recupera tu clave de QuickBid", payload.get("subject"));
		assertTrue(payload.get("textContent").toString()
				.contains("https://quickbid-backend.demo/auth-links/recuperar-clave?token=token+con+%2F%2B"));
		assertTrue(payload.get("textContent").toString().contains("token con /+"));
		assertTrue(payload.get("htmlContent").toString().contains("Continuar en QuickBid"));
		assertTrue(payload.get("htmlContent").toString()
				.contains("https://quickbid-backend.demo/auth-links/recuperar-clave?token=token+con+%2F%2B"));
	}

	@Test
	void parsesMailFromWithDisplayName() throws Exception {
		CapturingTransport transport = new CapturingTransport(200);
		service(transport, "QuickBid App <ayuda.breakbuddy@gmail.com>")
				.sendNotification("usuario@quickbid.demo", "multa_generada");

		Map<String, Object> payload = json.readValue(body(transport.request), new TypeReference<>() {});
		Map<?, ?> sender = (Map<?, ?>) payload.get("sender");
		assertEquals("QuickBid App", sender.get("name"));
		assertEquals("ayuda.breakbuddy@gmail.com", sender.get("email"));
	}

	@Test
	void parsesMailFromSimpleEmail() throws Exception {
		CapturingTransport transport = new CapturingTransport(200);
		service(transport, "ayuda.breakbuddy@gmail.com")
				.sendNotification("usuario@quickbid.demo", "multa_generada");

		Map<String, Object> payload = json.readValue(body(transport.request), new TypeReference<>() {});
		Map<?, ?> sender = (Map<?, ?>) payload.get("sender");
		assertEquals("QuickBid", sender.get("name"));
		assertEquals("ayuda.breakbuddy@gmail.com", sender.get("email"));
	}

	@Test
	void mapsProviderHttpErrorsWithoutReadingResponseBodies() {
		Map<Integer, String> expected = Map.of(
				400, "El proveedor rechazó los datos del correo",
				401, "El proveedor rechazó la autenticación de correo",
				403, "El proveedor rechazó la autenticación de correo",
				422, "El proveedor rechazó los datos del correo",
				429, "El proveedor limitó temporalmente los envíos",
				503, "El proveedor de correo no está disponible");

		expected.forEach((status, message) -> {
			MailDeliveryException error = assertThrows(MailDeliveryException.class,
					() -> service(new CapturingTransport(status), "no-reply@quickbid.demo")
							.sendNotification("usuario@quickbid.demo", "multa_generada"));
			assertEquals(message, error.getMessage());
		});
	}

	@Test
	void mapsConnectivityFailuresToControlledMailError() {
		BrevoMailService.HttpTransport transport = request -> {
			throw new IOException("test-only connection failure");
		};

		MailDeliveryException error = assertThrows(MailDeliveryException.class,
				() -> service(transport, "no-reply@quickbid.demo")
						.sendNotification("usuario@quickbid.demo", "multa_generada"));

		assertEquals("No se pudo conectar con el proveedor de correo", error.getMessage());
		assertInstanceOf(IOException.class, error.getCause());
	}

	@Test
	void validatesRequiredConfiguration() {
		CapturingTransport transport = new CapturingTransport(200);

		assertConfigError("Falta configurar APP_MAIL_FROM",
				() -> configured(transport, "", "key", "https://api.brevo.test/v3/smtp/email", 10));
		assertConfigError("APP_MAIL_FROM debe incluir un email valido",
				() -> configured(transport, "invalid-sender", "key", "https://api.brevo.test/v3/smtp/email", 10));
		assertConfigError("APP_MAIL_FROM debe incluir un email",
				() -> configured(transport, "QuickBid <>", "key", "https://api.brevo.test/v3/smtp/email", 10));
		assertConfigError("Falta configurar APP_BREVO_API_KEY",
				() -> configured(transport, "no-reply@quickbid.demo", "", "https://api.brevo.test/v3/smtp/email", 10));
		assertConfigError("Falta configurar APP_BREVO_API_URL",
				() -> configured(transport, "no-reply@quickbid.demo", "key", "", 10));
		assertConfigError("APP_BREVO_API_URL debe ser una URL HTTPS valida",
				() -> configured(transport, "no-reply@quickbid.demo", "key", "http://api.brevo.test/v3/smtp/email", 10));
		assertConfigError("APP_BREVO_TIMEOUT_SECONDS debe ser positivo",
				() -> configured(transport, "no-reply@quickbid.demo", "key", "https://api.brevo.test/v3/smtp/email", 0));
	}

	private BrevoMailService service(BrevoMailService.HttpTransport transport, String from) {
		return configured(transport, from, "test-only-brevo-key",
				"https://api.brevo.test/v3/smtp/email", 10);
	}

	private BrevoMailService configured(BrevoMailService.HttpTransport transport, String from,
			String apiKey, String apiUrl, int timeoutSeconds) {
		return new BrevoMailService(transport, json, templates, from, apiKey, apiUrl, timeoutSeconds);
	}

	private void assertConfigError(String message, Runnable constructor) {
		MailDeliveryException error = assertThrows(MailDeliveryException.class, constructor::run);
		assertEquals(message, error.getMessage());
	}

	private String body(HttpRequest request) {
		ByteArrayOutputStream output = new ByteArrayOutputStream();
		CompletableFuture<String> result = new CompletableFuture<>();
		request.bodyPublisher().orElseThrow().subscribe(new Flow.Subscriber<>() {
			@Override public void onSubscribe(Flow.Subscription subscription) { subscription.request(Long.MAX_VALUE); }
			@Override public void onNext(ByteBuffer item) {
				byte[] bytes = new byte[item.remaining()];
				item.get(bytes);
				output.writeBytes(bytes);
			}
			@Override public void onError(Throwable throwable) { result.completeExceptionally(throwable); }
			@Override public void onComplete() { result.complete(output.toString(StandardCharsets.UTF_8)); }
		});
		return result.join();
	}

	private static final class CapturingTransport implements BrevoMailService.HttpTransport {
		private final int status;
		private HttpRequest request;

		private CapturingTransport(int status) { this.status = status; }

		@Override
		public HttpResponse<Void> send(HttpRequest request) {
			this.request = request;
			return response(request, status);
		}
	}

	private static HttpResponse<Void> response(HttpRequest request, int status) {
		return new HttpResponse<>() {
			@Override public int statusCode() { return status; }
			@Override public HttpRequest request() { return request; }
			@Override public Optional<HttpResponse<Void>> previousResponse() { return Optional.empty(); }
			@Override public HttpHeaders headers() { return HttpHeaders.of(Map.of(), (a, b) -> true); }
			@Override public Void body() { return null; }
			@Override public Optional<SSLSession> sslSession() { return Optional.empty(); }
			@Override public URI uri() { return request.uri(); }
			@Override public HttpClient.Version version() { return HttpClient.Version.HTTP_1_1; }
		};
	}
}
