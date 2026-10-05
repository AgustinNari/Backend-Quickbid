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
import java.util.List;
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

class ResendMailServiceTests {
	private final ObjectMapper json = new ObjectMapper();
	private final MailTemplates templates = new MailTemplates(
			"quickbid://auth", "https://quickbid-backend.demo");

	@Test
	void sendsExpectedResendPayloadAndHeaders() throws Exception {
		CapturingTransport transport = new CapturingTransport(200);
		ResendMailService mail = service(transport);

		mail.sendToken("recuperacion", "usuario@quickbid.demo", "token con /+");

		HttpRequest request = transport.request;
		assertEquals("POST", request.method());
		assertEquals(URI.create("https://api.resend.test/emails"), request.uri());
		assertEquals("Bearer test-only-resend-key", request.headers().firstValue("Authorization").orElseThrow());
		assertEquals("application/json", request.headers().firstValue("Content-Type").orElseThrow());
		assertEquals("quickbid-backend/1.0", request.headers().firstValue("User-Agent").orElseThrow());

		Map<String, Object> payload = json.readValue(body(request), new TypeReference<>() {});
		assertEquals("no-reply@quickbid.demo", payload.get("from"));
		assertEquals(List.of("usuario@quickbid.demo"), payload.get("to"));
		assertEquals("Recupera tu clave de QuickBid", payload.get("subject"));
		assertTrue(payload.get("text").toString()
				.contains("https://quickbid-backend.demo/auth-links/recuperar-clave?token=token+con+%2F%2B"));
		assertTrue(payload.get("html").toString().contains("Continuar en QuickBid"));
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
					() -> service(new CapturingTransport(status))
							.sendNotification("usuario@quickbid.demo", "multa_generada"));
			assertEquals(message, error.getMessage());
		});
	}

	@Test
	void mapsConnectivityFailuresToControlledMailError() {
		ResendMailService.HttpTransport transport = request -> {
			throw new IOException("test-only connection failure");
		};

		MailDeliveryException error = assertThrows(MailDeliveryException.class,
				() -> service(transport).sendNotification("usuario@quickbid.demo", "multa_generada"));

		assertEquals("No se pudo conectar con el proveedor de correo", error.getMessage());
		assertInstanceOf(IOException.class, error.getCause());
	}

	@Test
	void validatesRequiredConfigurationAndHttpsUrl() {
		CapturingTransport transport = new CapturingTransport(200);

		assertConfigError("Falta configurar APP_MAIL_FROM",
				() -> new ResendMailService(transport, json, templates,
						"", "key", "https://api.resend.test/emails", 10));
		assertConfigError("Falta configurar APP_RESEND_API_KEY",
				() -> new ResendMailService(transport, json, templates,
						"no-reply@quickbid.demo", "", "https://api.resend.test/emails", 10));
		assertConfigError("Falta configurar APP_RESEND_API_URL",
				() -> new ResendMailService(transport, json, templates,
						"no-reply@quickbid.demo", "key", "", 10));
		assertConfigError("APP_RESEND_API_URL debe ser una URL HTTPS valida",
				() -> new ResendMailService(transport, json, templates,
						"no-reply@quickbid.demo", "key", "http://api.resend.test/emails", 10));
	}

	private ResendMailService service(ResendMailService.HttpTransport transport) {
		return new ResendMailService(
				transport,
				json,
				templates,
				"no-reply@quickbid.demo",
				"test-only-resend-key",
				"https://api.resend.test/emails",
				10);
	}

	private void assertConfigError(String message, Runnable constructor) {
		MailDeliveryException error = assertThrows(MailDeliveryException.class, constructor::run);
		assertEquals(message, error.getMessage());
	}

	private String body(HttpRequest request) {
		ByteArrayOutputStream output = new ByteArrayOutputStream();
		CompletableFuture<String> result = new CompletableFuture<>();
		request.bodyPublisher().orElseThrow().subscribe(new Flow.Subscriber<>() {
			@Override
			public void onSubscribe(Flow.Subscription subscription) {
				subscription.request(Long.MAX_VALUE);
			}

			@Override
			public void onNext(ByteBuffer item) {
				byte[] bytes = new byte[item.remaining()];
				item.get(bytes);
				output.writeBytes(bytes);
			}

			@Override
			public void onError(Throwable throwable) {
				result.completeExceptionally(throwable);
			}

			@Override
			public void onComplete() {
				result.complete(output.toString(StandardCharsets.UTF_8));
			}
		});
		return result.join();
	}

	private static final class CapturingTransport implements ResendMailService.HttpTransport {
		private final int status;
		private HttpRequest request;

		private CapturingTransport(int status) {
			this.status = status;
		}

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
