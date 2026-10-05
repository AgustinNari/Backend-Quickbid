package com.example.quickbid.quickbid.service;

import java.io.ByteArrayInputStream;
import java.time.Duration;
import java.util.Map;
import java.util.Set;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import com.example.quickbid.quickbid.audit.AuditEvent;
import com.example.quickbid.quickbid.audit.AuditService;
import com.example.quickbid.quickbid.entity.app.ArchivoApp;
import com.example.quickbid.quickbid.entity.app.CuentaApp;
import com.example.quickbid.quickbid.entity.app.PasswordResetToken;
import com.example.quickbid.quickbid.entity.app.RefreshToken;
import com.example.quickbid.quickbid.entity.app.SolicitudRegistro;
import com.example.quickbid.quickbid.exception.BusinessException;
import com.example.quickbid.quickbid.repository.app.ArchivoAppRepository;
import com.example.quickbid.quickbid.repository.app.CuentaAppRepository;
import com.example.quickbid.quickbid.repository.app.PasswordResetTokenRepository;
import com.example.quickbid.quickbid.repository.app.RefreshTokenRepository;
import com.example.quickbid.quickbid.repository.app.SolicitudRegistroRepository;
import com.example.quickbid.quickbid.repository.legacy.PaisRepository;
import com.example.quickbid.quickbid.security.AuthRateLimitService;
import com.example.quickbid.quickbid.security.TokenService;
import com.example.quickbid.quickbid.storage.StorageService;

@Service
public class AuthService {

	private final CuentaAppRepository cuentas;
	private final SolicitudRegistroRepository solicitudes;
	private final PaisRepository paises;
	private final RefreshTokenRepository refresh;
	private final PasswordResetTokenRepository resets;
	private final PasswordEncoder passwords;
	private final TokenService tokens;
	private final int refreshDays;
	private final ArchivoAppRepository archivos;
	private final StorageService storage;
	private final AuditService audit;
	private final AuthRateLimitService limits;
	private final AuthFailureService failures;
	private final MailService mail;
	private final ImageValidationService images;

	public AuthService(
			CuentaAppRepository c,
			SolicitudRegistroRepository s,
			PaisRepository p,
			RefreshTokenRepository r,
			PasswordResetTokenRepository pr,
			PasswordEncoder pe,
			TokenService t,
			@Value("${app.jwt.refresh-token-days:30}") int d,
			ArchivoAppRepository a,
			StorageService st,
			AuditService au,
			AuthRateLimitService l,
			AuthFailureService f,
			MailService m,
			ImageValidationService i) {
		cuentas = c;
		solicitudes = s;
		paises = p;
		refresh = r;
		resets = pr;
		passwords = pe;
		tokens = t;
		refreshDays = d;
		archivos = a;
		storage = st;
		audit = au;
		limits = l;
		failures = f;
		mail = m;
		images = i;
	}

	@Transactional
	public Map<String, Object> etapa1(
			String email,
			String nombre,
			String apellido,
			String domicilio,
			Integer pais) {
		if (!paises.existsById(pais)) {
			throw bad("País inexistente", "INVALID_COUNTRY");
		}
		if (cuentas.findByEmailIgnoreCase(email).isPresent()) {
			throw bad("El email ya está registrado", "EMAIL_ALREADY_EXISTS");
		}

		solicitudes.findFirstByEmailIgnoreCaseOrderByCreatedAtDesc(email)
				.filter(x -> !Set.of("rechazado", "completada", "expirada").contains(x.getEstado()))
				.ifPresent(x -> {
					throw bad("Ya existe una solicitud activa", "ACTIVE_REGISTRATION");
				});

		var s = solicitudes.save(new SolicitudRegistro(email, nombre, apellido, domicilio, pais));
		audit("sistema", null, "auth.registro_etapa1", "solicitud_registro", s.getId());
		return Map.of("idRegistro", s.getId(), "siguientePaso", "etapa2");
	}

	@Transactional
	public void etapa2(String email, MultipartFile frente, MultipartFile dorso) {
		var s = solicitudes.findFirstByEmailIgnoreCaseOrderByCreatedAtDesc(email)
				.filter(x -> x.getEstado().equals("pendiente_etapa2"))
				.orElseThrow(() -> bad("Solicitud inexistente o fuera de etapa", "INVALID_REGISTRATION"));

		s.attachDni(saveImage("dni_frente", frente), saveImage("dni_dorso", dorso));
		audit("sistema", null, "auth.registro_etapa2", "solicitud_registro", s.getId());
	}

	@Transactional(readOnly = true)
	public void verify(String raw) {
		setup(raw);
	}

	@Transactional
	public Map<String, Object> etapa3(String raw, String clave, String confirmacion) {
		validatePassword(clave);
		if (!clave.equals(confirmacion)) {
			throw bad("Las claves no coinciden", "PASSWORD_CONFIRMATION_MISMATCH");
		}

		var s = setup(raw);
		if (s.getPersonaId() == null || s.getClienteId() == null) {
			throw bad("La solicitud aun no fue aprobada internamente", "REGISTRATION_NOT_APPROVED");
		}

		var c = cuentas.save(new CuentaApp(
				s.getPersonaId(),
				s.getClienteId(),
				s.getEmail(),
				passwords.encode(clave)));
		s.complete();
		audit("usuario", c.getId(), "auth.registro_completado", "cuenta", c.getId());
		return issue(c);
	}

	@Transactional
	public void resend(String email) {
		limits.check("setup-link", email, 3, Duration.ofMinutes(15));
		solicitudes.findFirstByEmailIgnoreCaseOrderByCreatedAtDesc(email)
				.filter(x -> x.getEstado().equals("aprobada_pendiente_finalizacion"))
				.ifPresent(s -> {
					String raw = tokens.opaque();
					String previousHash = s.getSetupTokenHash();
					var previousExpires = s.getSetupTokenExpiresAt();
					s.approve(s.getPersonaId(), s.getClienteId(), tokens.hash(raw));
					if (!deliverConcealed("registro", email, raw)) {
						s.restoreSetupToken(previousHash, previousExpires);
						return;
					}
					audit("sistema", null, "auth.registro_link_reenviado", "solicitud_registro", s.getId());
				});
	}

	@Transactional
	public Map<String, Object> login(String email, String clave) {
		limits.check("login", email, 10, Duration.ofMinutes(15));
		var c = cuentas.findByEmailIgnoreCase(email)
				.orElseThrow(() -> unauth("Credenciales inválidas"));

		if (!passwords.matches(clave, c.getPasswordHash())) {
			failures.failedLogin(c.getId());
			throw unauth("Credenciales inválidas");
		}

		if (c.getEstado().equals("deshabilitada_admin")) {
			audit("usuario", c.getId(), "auth.login_bloqueado", "cuenta", c.getId());
			throw new BusinessException(HttpStatus.FORBIDDEN, "Cuenta bloqueada", "ACCOUNT_BLOCKED");
		}

		c.loginSucceeded();
		audit(
				"usuario",
				c.getId(),
				c.getEstado().equals("bloqueada_permanente") ? "auth.login_limitado" : "auth.login_exitoso",
				"cuenta",
				c.getId());
		return issue(c);
	}

	@Transactional
	public Map<String, Object> refresh(String raw) {
		var rt = refresh.findByTokenHash(tokens.hash(raw))
				.filter(RefreshToken::valid)
				.orElseThrow(() -> unauth("Refresh token invalido"));
		rt.revoke();

		var c = cuentas.findById(rt.getCuentaId()).orElseThrow();
		ensureLoginAllowed(c);
		audit("usuario", c.getId(), "auth.refresh_rotado", "cuenta", c.getId());
		return issue(c);
	}

	@Transactional
	public void logout(String raw) {
		refresh.findByTokenHash(tokens.hash(raw)).ifPresent(rt -> {
			rt.revoke();
			audit("usuario", rt.getCuentaId(), "auth.logout", "cuenta", rt.getCuentaId());
		});
	}

	@Transactional
	public void recover(String email) {
		limits.check("password-reset", email, 3, Duration.ofMinutes(15));
		cuentas.findByEmailIgnoreCase(email).ifPresent(c -> {
			String raw = tokens.opaque();
			var reset = resets.save(new PasswordResetToken(c.getId(), tokens.hash(raw)));
			if (!deliverConcealed("recuperacion", email, raw)) {
				resets.delete(reset);
				return;
			}
			audit("usuario", c.getId(), "auth.recuperacion_solicitada", "cuenta", c.getId());
		});
	}

	@Transactional
	public void changeWithToken(String raw, String clave, String confirmacion) {
		if (raw == null || raw.isBlank()) {
			throw bad("Token invalido o expirado", "INVALID_TOKEN");
		}
		validatePassword(clave);
		if (!clave.equals(confirmacion)) {
			throw bad("Las claves no coinciden", "PASSWORD_CONFIRMATION_MISMATCH");
		}

		var r = resets.findByTokenHash(tokens.hash(raw))
				.filter(PasswordResetToken::valid)
				.orElseThrow(() -> bad("Token invalido o expirado", "INVALID_TOKEN"));
		var c = cuentas.findById(r.getCuentaId()).orElseThrow();
		changePassword(c, clave);
		r.use();
	}

	@Transactional
	public void changeAuthenticated(Long cuentaId, String actual, String nueva, String confirmacion) {
		validatePassword(nueva);
		if (!nueva.equals(confirmacion)) {
			throw bad("Las claves no coinciden", "PASSWORD_CONFIRMATION_MISMATCH");
		}

		var c = cuentas.findById(cuentaId).orElseThrow();
		if (actual == null || !passwords.matches(actual, c.getPasswordHash())) {
			throw new BusinessException(
					HttpStatus.UNAUTHORIZED,
					"Clave actual incorrecta",
					"INVALID_CURRENT_PASSWORD");
		}
		changePassword(c, nueva);
	}

	@Transactional(readOnly = true)
	public Map<String, Object> session(Long id) {
		var c = cuentas.findById(id).orElseThrow();
		return user(c);
	}

	private Map<String, Object> issue(CuentaApp c) {
		String opaque = tokens.opaque();
		refresh.save(new RefreshToken(c.getId(), tokens.hash(opaque), refreshDays));
		return Map.of(
				"accessToken", tokens.access(c.getId(), c.getEmail(), c.getEstado()),
				"refreshToken", opaque,
				"tokenType", "Bearer",
				"expiresIn", tokens.accessSeconds(),
				"estadoCuenta", c.getEstado(),
				"usuario", user(c));
	}

	private Map<String, Object> user(CuentaApp c) {
		return Map.of(
				"id", c.getId(),
				"email", c.getEmail(),
				"estado", c.getEstado(),
				"puntos", c.getPuntos(),
				"categoria", c.getCategoriaCalculada());
	}

	public void validatePassword(String p) {
		if (p == null || !p.matches("^(?=.*[a-z])(?=.*[A-Z])(?=.*\\d)(?=.*[^A-Za-z0-9]).{8,}$")) {
			throw bad("La clave no cumple la politica requerida", "WEAK_PASSWORD");
		}
	}

	private SolicitudRegistro setup(String raw) {
		if (raw == null || raw.isBlank()) {
			throw bad("Token invalido o expirado", "INVALID_TOKEN");
		}

		return solicitudes.findBySetupTokenHash(tokens.hash(raw))
				.filter(s ->
						s.getSetupTokenUsedAt() == null &&
						s.getSetupTokenExpiresAt() != null &&
						s.getSetupTokenExpiresAt().isAfter(java.time.OffsetDateTime.now()))
				.orElseThrow(() -> bad("Token invalido o expirado", "INVALID_TOKEN"));
	}

	private boolean deliverConcealed(String purpose, String email, String raw) {
		try {
			mail.sendToken(purpose, email, raw);
			return true;
		} catch (MailDeliveryException e) {
			return false;
		}
	}

	private Long saveImage(String tipo, MultipartFile f) {
		try {
			byte[] bytes = images.validate(f);
			String type = f.getContentType();
			String filename = f.getOriginalFilename() == null ? "dni" : f.getOriginalFilename();
			var stored = storage.store(filename, type, new ByteArrayInputStream(bytes));
			return archivos.save(new ArchivoApp(
					tipo,
					filename,
					type,
					stored.sizeBytes(),
					stored.storagePath(),
					stored.checksum()))
					.getId();
		} catch (BusinessException e) {
			throw bad("Formato DNI invalido", "INVALID_FILE");
		}
	}

	private void ensureLoginAllowed(CuentaApp c) {
		if (
				c.getEstado().equals("bloqueada_permanente") ||
				c.getEstado().equals("deshabilitada_admin")) {
			audit("usuario", c.getId(), "auth.login_bloqueado", "cuenta", c.getId());
			throw new BusinessException(HttpStatus.FORBIDDEN, "Cuenta bloqueada", "ACCOUNT_BLOCKED");
		}
	}

	private void changePassword(CuentaApp c, String clave) {
		c.changePassword(passwords.encode(clave));
		refresh.findAllByCuentaIdAndRevokedAtIsNull(c.getId()).forEach(RefreshToken::revoke);
		audit("usuario", c.getId(), "auth.clave_actualizada", "cuenta", c.getId());
	}

	private void audit(String actor, Long actorId, String action, String entity, Long entityId) {
		audit.record(new AuditEvent(actor, actorId, action, entity, entityId, "{}"));
	}

	private BusinessException bad(String m, String c) {
		return new BusinessException(HttpStatus.BAD_REQUEST, m, c);
	}

	private BusinessException unauth(String m) {
		return new BusinessException(HttpStatus.UNAUTHORIZED, m, "INVALID_CREDENTIALS");
	}
}
