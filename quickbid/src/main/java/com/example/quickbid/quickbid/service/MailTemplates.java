package com.example.quickbid.quickbid.service;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.Map;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
public class MailTemplates {
	private static final Map<String, Template> NOTIFICATIONS = Map.ofEntries(
			Map.entry("medio_pago_verificado", new Template("Medio de pago verificado",
					"Tu medio de pago fue verificado y ya puede utilizarse en QuickBid.")),
			Map.entry("medio_pago_rechazado", new Template("Medio de pago rechazado",
					"Revisa los datos de tu medio de pago desde la app QuickBid.")),
			Map.entry("multa_generada", new Template("Multa pendiente",
					"Se generó una multa asociada a una compra. Revisá el detalle y el plazo desde la app.")),
			Map.entry("multa_pagada", new Template("Multa pagada",
					"Registramos la regularización de tu multa.")),
			Map.entry("multa_vencida", new Template("Multa vencida",
					"Tu multa venció y la cuenta quedó bloqueada. Revisá el detalle desde la app.")),
			Map.entry("lote_ganado", new Template("Ganaste un lote",
					"Ganaste un lote subastado. Revisá tu compra y los pasos pendientes desde la app.")),
			Map.entry("pago_adjudicacion_exitoso", new Template("Pago de adjudicación aprobado",
					"El pago de adjudicación fue aprobado. Revisá los pagos extra pendientes desde la app.")),
			Map.entry("entrega_pendiente", new Template("Entrega pendiente",
					"El pago de extras fue aprobado. Revisa el estado de entrega desde la app.")),
			Map.entry("retiro_pendiente", new Template("Retiro pendiente",
					"El pago de extras fue aprobado. Revisá el retiro pendiente desde la app.")),
			Map.entry("acuerdo_disponible", new Template("Acuerdo de consignación disponible",
					"Ya podés revisar el acuerdo propuesto para tu consignación.")),
			Map.entry("consignacion_documentacion_requerida", new Template("Documentación adicional requerida",
					"Debés adjuntar documentación adicional para continuar con tu consignación.")),
			Map.entry("consignacion_publicada", new Template("Artículo publicado",
					"Tu artículo consignado fue asignado a una subasta.")),
			Map.entry("liquidacion_disponible", new Template("Liquidación disponible",
					"La liquidación de tu artículo consignado ya fue emitida.")),
			Map.entry("subasta_inscripta_proxima_inicio", new Template("Tu subasta inscripta está por iniciar",
					"Una subasta en la que manifestaste interés ya está disponible en vivo.")));

	private final String frontendBaseUrl;
	private final String publicBaseUrl;

	@Autowired
	public MailTemplates(@Value("${app.frontend.base-url:http://localhost:3000}") String frontendBaseUrl,
			@Value("${app.public-base-url:}") String publicBaseUrl) {
		this.frontendBaseUrl = frontendBaseUrl.replaceAll("/+$", "");
		this.publicBaseUrl = publicBaseUrl == null ? "" : publicBaseUrl.trim().replaceAll("/+$", "");
	}

	public MailTemplates(String frontendBaseUrl) {
		this(frontendBaseUrl, "");
	}

	public Message token(String purpose, String token) {
		String encoded = URLEncoder.encode(token, StandardCharsets.UTF_8);
		return switch (purpose) {
			case "registro" -> tokenMessage("Completa tu registro en QuickBid",
					"Completa tu registro y configura tu clave.", "completar-registro", token, encoded);
			case "recuperacion" -> tokenMessage("Recupera tu clave de QuickBid",
					"Configura una nueva clave para tu cuenta.", "recuperar-clave", token, encoded);
			default -> throw new IllegalArgumentException("Tipo de token de mail desconocido");
		};
	}

	private Message tokenMessage(String subject, String introduction, String path, String token, String encoded) {
		String deepLink = frontendBaseUrl + "/" + path + "?token=" + encoded;
		String primaryLink = publicBaseUrl.isBlank()
				? deepLink
				: publicBaseUrl + "/auth-links/" + path + "?token=" + encoded;
		String text = introduction + "\n\nAbrí este enlace:\n" + primaryLink
				+ "\n\nSi el enlace no funciona, copiá este código en la app:\n" + token
				+ "\n\nSi no solicitaste esto, podés ignorar este mensaje.";
		String html = """
				<!doctype html>
				<html><body style="margin:0;padding:24px;background:#f4f6f8;font-family:Arial,sans-serif;color:#18202a">
				<div style="max-width:560px;margin:0 auto;background:#ffffff;border-radius:12px;padding:32px">
				<h1 style="margin:0 0 16px;font-size:24px">QuickBid</h1>
				<p style="line-height:1.5">%s</p>
				<p style="margin:28px 0"><a href="%s" style="background:#1463ff;color:#ffffff;text-decoration:none;padding:14px 22px;border-radius:8px;display:inline-block;font-weight:bold">Continuar en QuickBid</a></p>
				<p style="line-height:1.5">Si el botón no funciona, abrí este enlace:</p>
				<p style="word-break:break-all"><a href="%s">%s</a></p>
				<p style="line-height:1.5">También podés copiar este código en la app:</p>
				<div style="word-break:break-all;background:#eef1f5;border-radius:8px;padding:12px;font-family:monospace">%s</div>
				<p style="margin-top:24px;color:#596574;font-size:13px">Si no solicitaste esto, podés ignorar este mensaje.</p>
				</div></body></html>
				""".formatted(escapeHtml(introduction), escapeHtml(primaryLink), escapeHtml(primaryLink),
					escapeHtml(primaryLink), escapeHtml(token));
		return new Message(subject, text, html);
	}

	public Message notification(String type) {
		Template value = NOTIFICATIONS.get(type);
		if (value == null) throw new IllegalArgumentException("Tipo de notificacion de mail desconocido");
		return new Message(value.subject(), value.body(), null);
	}

	private String escapeHtml(String value) {
		return value.replace("&", "&amp;")
				.replace("<", "&lt;")
				.replace(">", "&gt;")
				.replace("\"", "&quot;")
				.replace("'", "&#39;");
	}

	public record Message(String subject, String textContent, String htmlContent) {
		public String body() {
			return textContent;
		}
	}

	private record Template(String subject, String body) {
	}
}
