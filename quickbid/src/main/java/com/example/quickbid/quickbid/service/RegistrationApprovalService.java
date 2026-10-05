package com.example.quickbid.quickbid.service;

import java.util.Set;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.example.quickbid.quickbid.audit.AuditEvent;
import com.example.quickbid.quickbid.audit.AuditService;
import com.example.quickbid.quickbid.entity.legacy.Cliente;
import com.example.quickbid.quickbid.entity.legacy.Persona;
import com.example.quickbid.quickbid.exception.BusinessException;
import com.example.quickbid.quickbid.repository.app.SolicitudRegistroRepository;
import com.example.quickbid.quickbid.repository.legacy.ClienteRepository;
import com.example.quickbid.quickbid.repository.legacy.PersonaRepository;
import com.example.quickbid.quickbid.security.TokenService;

@Service
public class RegistrationApprovalService {

	private static final Set<String> CATEGORIES = Set.of("comun", "especial", "plata", "oro", "platino");

	private final SolicitudRegistroRepository solicitudes;
	private final PersonaRepository personas;
	private final ClienteRepository clientes;
	private final TokenService tokens;
	private final AuditService audit;
	private final MailService mail;

	public RegistrationApprovalService(
			SolicitudRegistroRepository s,
			PersonaRepository p,
			ClienteRepository c,
			TokenService t,
			AuditService a,
			MailService m) {
		solicitudes = s;
		personas = p;
		clientes = c;
		tokens = t;
		audit = a;
		mail = m;
	}

	@Transactional
	public void approve(long solicitudId, String documento, int verificadorId) {
		approve(solicitudId, documento, verificadorId, "comun");
	}

	@Transactional
	public void approve(long solicitudId, String documento, int verificadorId, String categoria) {
		String value = categoria == null || categoria.isBlank() ? "comun" : categoria.toLowerCase();
		if (!CATEGORIES.contains(value)) {
			throw new BusinessException(HttpStatus.BAD_REQUEST, "Categoría inválida", "INVALID_CATEGORY");
		}

		var s = solicitudes.findById(solicitudId)
				.filter(x -> x.getEstado().equals("pendiente_revision"))
				.orElseThrow(() -> new BusinessException(
						HttpStatus.BAD_REQUEST,
						"Solicitud fuera de etapa",
						"INVALID_REGISTRATION"));
		var p = personas.save(new Persona(documento, s.getNombre() + " " + s.getApellido(), s.getDomicilioLegal()));
		var cliente = new Cliente(p.getIdentificador(), s.getIdPaisOrigen(), verificadorId);
		cliente.updateCategory(value);
		clientes.save(cliente);

		String raw = tokens.opaque();
		s.approve(p.getIdentificador(), p.getIdentificador(), tokens.hash(raw));
		mail.sendToken("registro", s.getEmail(), raw);
		audit.record(new AuditEvent(
				"admin",
				(long) verificadorId,
				"auth.registro_aprobado",
				"solicitud_registro",
				s.getId(),
				"{}"));
	}

	@Transactional
	public void reject(long solicitudId, String reason, int verificadorId) {
		if (reason == null || reason.isBlank()) {
			throw new BusinessException(HttpStatus.BAD_REQUEST, "Motivo requerido", "INVALID_FIELD");
		}

		var s = solicitudes.findById(solicitudId)
				.filter(x -> x.getEstado().equals("pendiente_revision"))
				.orElseThrow(() -> new BusinessException(
						HttpStatus.BAD_REQUEST,
						"Solicitud fuera de etapa",
						"INVALID_REGISTRATION"));
		s.reject(reason.trim());
		audit.record(new AuditEvent(
				"admin",
				(long) verificadorId,
				"auth.registro_rechazado",
				"solicitud_registro",
				s.getId(),
				"{}"));
	}
}
