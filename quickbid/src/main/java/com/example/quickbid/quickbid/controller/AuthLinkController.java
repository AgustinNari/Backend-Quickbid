package com.example.quickbid.quickbid.controller;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping(value = "/auth-links", produces = MediaType.TEXT_HTML_VALUE)
public class AuthLinkController {
	private final String frontendBaseUrl;

	public AuthLinkController(@Value("${app.frontend.base-url:http://localhost:3000}") String frontendBaseUrl) {
		this.frontendBaseUrl = frontendBaseUrl.replaceAll("/+$", "");
	}

	@GetMapping("/completar-registro")
	public ResponseEntity<String> completeRegistration(@RequestParam(required = false) String token) {
		return page("completar-registro", "Completá tu registro", token);
	}

	@GetMapping("/recuperar-clave")
	public ResponseEntity<String> recoverPassword(@RequestParam(required = false) String token) {
		return page("recuperar-clave", "Recuperá tu clave", token);
	}

	private ResponseEntity<String> page(String path, String title, String token) {
		if (token == null || token.isBlank()) {
			return response(HttpStatus.BAD_REQUEST, layout("Enlace incompleto",
					"Este enlace no incluye el código necesario. Volvé al correo e intentá nuevamente."));
		}
		String encoded = URLEncoder.encode(token, StandardCharsets.UTF_8);
		String deepLink = frontendBaseUrl + "/" + path + "?token=" + encoded;
		String content = """
				<h1>%s</h1>
				<p>Usá el botón para continuar de forma segura en la app.</p>
				<p><a class="button" href="%s">Abrir QuickBid</a></p>
				<p>Si el botón no funciona, copiá este código en la app:</p>
				<div class="code">%s</div>
				<p class="help">Deep link: <span class="link">%s</span></p>
				""".formatted(escapeHtml(title), escapeHtml(deepLink), escapeHtml(token), escapeHtml(deepLink));
		return response(HttpStatus.OK, layout(title, content));
	}

	private ResponseEntity<String> response(HttpStatus status, String body) {
		return ResponseEntity.status(status)
				.cacheControl(CacheControl.noStore())
				.header("Referrer-Policy", "no-referrer")
				.header("X-Content-Type-Options", "nosniff")
				.body(body);
	}

	private String layout(String title, String content) {
		return """
				<!doctype html>
				<html lang="es"><head><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1">
				<title>%s | QuickBid</title>
				<style>body{margin:0;padding:24px;background:#f4f6f8;font-family:Arial,sans-serif;color:#18202a}.card{max-width:560px;margin:40px auto;background:#fff;border-radius:14px;padding:32px;box-shadow:0 8px 28px #0001}h1{font-size:26px}.button{display:inline-block;margin:12px 0;padding:14px 22px;border-radius:8px;background:#1463ff;color:#fff;text-decoration:none;font-weight:bold}.code,.link{word-break:break-all}.code{padding:14px;border-radius:8px;background:#eef1f5;font-family:monospace}.help{color:#596574;font-size:13px}</style>
				</head><body><main class="card"><div class="help">QuickBid</div>%s</main></body></html>
				""".formatted(escapeHtml(title), content);
	}

	private String escapeHtml(String value) {
		return value.replace("&", "&amp;")
				.replace("<", "&lt;")
				.replace(">", "&gt;")
				.replace("\"", "&quot;")
				.replace("'", "&#39;");
	}
}
