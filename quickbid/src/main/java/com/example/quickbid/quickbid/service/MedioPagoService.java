package com.example.quickbid.quickbid.service;

import java.io.ByteArrayInputStream;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.YearMonth;
import java.util.List;
import java.util.Set;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import com.example.quickbid.quickbid.audit.AuditEvent;
import com.example.quickbid.quickbid.audit.AuditService;
import com.example.quickbid.quickbid.dto.request.MedioPagoRequest;
import com.example.quickbid.quickbid.dto.response.MedioPagoResponse;
import com.example.quickbid.quickbid.entity.app.ArchivoApp;
import com.example.quickbid.quickbid.entity.app.ChequeCertificado;
import com.example.quickbid.quickbid.entity.app.CuentaApp;
import com.example.quickbid.quickbid.entity.app.CuentaBancaria;
import com.example.quickbid.quickbid.entity.app.MedioPago;
import com.example.quickbid.quickbid.entity.app.NotificacionApp;
import com.example.quickbid.quickbid.entity.app.Tarjeta;
import com.example.quickbid.quickbid.exception.BusinessException;
import com.example.quickbid.quickbid.repository.app.ArchivoAppRepository;
import com.example.quickbid.quickbid.repository.app.ChequeCertificadoRepository;
import com.example.quickbid.quickbid.repository.app.CuentaAppRepository;
import com.example.quickbid.quickbid.repository.app.CuentaBancariaRepository;
import com.example.quickbid.quickbid.repository.app.MedioPagoRepository;
import com.example.quickbid.quickbid.repository.app.NotificacionAppRepository;
import com.example.quickbid.quickbid.repository.app.PaymentMethodQueryRepository;
import com.example.quickbid.quickbid.repository.app.TarjetaRepository;
import com.example.quickbid.quickbid.security.TokenService;
import com.example.quickbid.quickbid.storage.StorageService;

@Service
public class MedioPagoService {

	private final MedioPagoRepository medios;
	private final TarjetaRepository tarjetas;
	private final CuentaBancariaRepository cuentasBancarias;
	private final ChequeCertificadoRepository cheques;
	private final CuentaAppRepository cuentas;
	private final ArchivoAppRepository archivos;
	private final NotificacionAppRepository notificaciones;
	private final CategoriaService categorias;
	private final AuditService audit;
	private final TokenService tokens;
	private final StorageService storage;
	private final ImageValidationService images;
	private final PaymentMethodQueryRepository queries;
	private final MailNotificationService mail;

	public MedioPagoService(
			MedioPagoRepository m,
			TarjetaRepository t,
			CuentaBancariaRepository cb,
			ChequeCertificadoRepository ch,
			CuentaAppRepository c,
			ArchivoAppRepository a,
			NotificacionAppRepository n,
			CategoriaService cat,
			AuditService au,
			TokenService tok,
			StorageService st,
			ImageValidationService i,
			PaymentMethodQueryRepository q,
			MailNotificationService mail) {
		medios = m;
		tarjetas = t;
		cuentasBancarias = cb;
		cheques = ch;
		cuentas = c;
		archivos = a;
		notificaciones = n;
		categorias = cat;
		audit = au;
		tokens = tok;
		storage = st;
		images = i;
		queries = q;
		this.mail = mail;
	}

	@Transactional(readOnly = true)
	public List<MedioPagoResponse> list(Long cuenta) {
		account(cuenta);
		return medios.findAllByCuentaIdAndDeletedAtIsNullOrderByCreatedAtDesc(cuenta)
				.stream()
				.map(this::response)
				.toList();
	}

	@Transactional
	public MedioPagoResponse create(Long cuenta, MedioPagoRequest r) {
		account(cuenta);
		String tipo = value(r.tipo());
		String moneda = currency(r.moneda());
		if (tipo.equals("tarjeta")) {
			return card(cuenta, moneda, r);
		}
		if (tipo.equals("cuenta_bancaria")) {
			return bank(cuenta, moneda, r);
		}
		if (tipo.equals("cheque_certificado")) {
			throw bad("Use multipart para crear cheques certificados", "INVALID_PAYMENT_METHOD");
		}
		throw bad("Tipo de medio de pago no soportado", "INVALID_PAYMENT_METHOD");
	}

	@Transactional
	public MedioPagoResponse createCheque(
			Long cuenta,
			String moneda,
			Boolean nacional,
			String titular,
			String numero,
			BigDecimal monto,
			LocalDate vencimiento,
			String banco,
			MultipartFile anverso,
			MultipartFile reverso) {
		account(cuenta);
		moneda = currency(moneda);
		required(titular, "titular");
		required(numero, "numeroCheque");
		required(banco, "bancoEmisor");

		if (
				nacional == null ||
				monto == null ||
				monto.signum() <= 0 ||
				vencimiento == null ||
				!vencimiento.isAfter(LocalDate.now())) {
			throw bad("Datos de cheque invalidos", "INVALID_CHEQUE");
		}

		String hash = tokens.hash("cheque:" + normalize(numero));
		duplicate(cuenta, hash);
		Long frente = saveChequeImage(cuenta, "cheque_anverso", anverso);
		Long dorso = saveChequeImage(cuenta, "cheque_reverso", reverso);
		var medio = medios.save(new MedioPago(
				cuenta,
				"cheque_certificado",
				moneda,
				nacional,
				"Cheque certificado " + banco,
				null,
				titular,
				hash,
				monto));
		cheques.save(new ChequeCertificado(medio.getId(), hash, monto, vencimiento, banco, frente, dorso));
		created(cuenta, medio);
		return response(medio);
	}

	@Transactional
	public void delete(Long cuenta, Long id) {
		account(cuenta);
		var medio = owned(cuenta, id);
		if (queries.existsPendingOperationForPaymentMethod(id)) {
			throw conflict("El medio esta asociado a una operacion pendiente", "PAYMENT_METHOD_IN_USE");
		}
		medio.delete();
		audit(cuenta, "medio_pago.eliminado", id);
	}

	@Transactional
	public MedioPagoResponse principal(Long cuenta, Long id) {
		account(cuenta);
		var medio = owned(cuenta, id);
		if (!medio.getEstado().equals("verificado")) {
			throw conflict("Solo un medio verificado puede ser principal", "PAYMENT_METHOD_NOT_VERIFIED");
		}

		medios.clearOtherActivePrincipals(cuenta, medio.getMoneda(), id);
		medio.markPrincipal(true);
		medios.saveAndFlush(medio);
		audit(cuenta, "medio_pago.principal_actualizado", id);
		return response(medio);
	}

	@Transactional
	public MedioPagoResponse verify(Long id, Integer employee, BigDecimal limiteAprobado) {
		if (limiteAprobado == null || limiteAprobado.signum() <= 0) {
			throw bad("limiteAprobado es obligatorio", "INVALID_LIMIT");
		}

		var medio = medios.findById(id).orElseThrow(() -> notFound());
		if (!Set.of("pendiente_verificacion", "verificado", "vencido").contains(medio.getEstado())) {
			throw conflict("Estado incompatible", "INVALID_STATE_TRANSITION");
		}

		boolean firstVerification = medio.getEstado().equals("pendiente_verificacion");
		medio.verify(employee, limiteAprobado);
		if (firstVerification) {
			categorias.addPoints(account(medio.getCuentaId()), 30, "medio_pago_verificado", "medio_pago", id);
		}
		notificaciones.save(new NotificacionApp(
				medio.getCuentaId(),
				"medio_pago_verificado",
				"Medio de pago verificado",
				"Tu medio de pago ya puede utilizarse.",
				"medio_pago",
				id));
		mail.critical(medio.getCuentaId(), "medio_pago_verificado");
		audit.record(new AuditEvent(
				"admin",
				employee.longValue(),
				firstVerification ? "medio_pago.verificado" : "medio_pago.revalidado",
				"medio_pago",
				id,
				"{}"));
		return response(medio);
	}

	@Transactional
	public MedioPagoResponse reject(Long id, Integer employee, String reason) {
		if (blank(reason)) {
			throw bad("Motivo requerido", "INVALID_FIELD");
		}

		var medio = medios.findById(id).orElseThrow(() -> notFound());
		if (!medio.getEstado().equals("pendiente_verificacion")) {
			throw conflict("Estado incompatible", "INVALID_STATE_TRANSITION");
		}

		medio.reject(employee, reason.trim());
		notificaciones.save(new NotificacionApp(
				medio.getCuentaId(),
				"medio_pago_rechazado",
				"Medio de pago rechazado",
				"Revisa los datos de tu medio de pago.",
				"medio_pago",
				id));
		mail.critical(medio.getCuentaId(), "medio_pago_rechazado");
		audit.record(new AuditEvent(
				"admin",
				employee.longValue(),
				"medio_pago.rechazado",
				"medio_pago",
				id,
				"{}"));
		return response(medio);
	}

	@Transactional
	public int expireVerified() {
		var expired = medios.findAllByEstadoAndVerificadoHastaBeforeAndDeletedAtIsNull(
				"verificado",
				OffsetDateTime.now());
		expired.forEach(MedioPago::expire);
		return expired.size();
	}

	private MedioPagoResponse card(Long cuenta, String moneda, MedioPagoRequest r) {
		String pan = normalize(r.numeroTarjeta());
		if (
				!pan.matches("\\d{12,19}") ||
				r.cvv() == null ||
				!r.cvv().matches("\\d{3,4}") ||
				!validExpiry(r.vencimientoAnio(), r.vencimientoMes())) {
			throw bad("Datos de tarjeta invalidos", "INVALID_CARD");
		}

		String hash = tokens.hash("tarjeta:" + pan);
		String last = pan.substring(pan.length() - 4);
		duplicate(cuenta, hash);
		var medio = medios.save(new MedioPago(
				cuenta,
				"tarjeta",
				moneda,
				r.nacional(),
				"Tarjeta " + last,
				last,
				r.titular(),
				hash,
				null));
		tarjetas.save(new Tarjeta(medio.getId(), r.marca(), r.vencimientoMes(), r.vencimientoAnio()));
		created(cuenta, medio);
		return response(medio);
	}

	private MedioPagoResponse bank(Long cuenta, String moneda, MedioPagoRequest r) {
		required(r.nombreBanco(), "nombreBanco");
		if (blank(r.numeroCuenta()) && blank(r.cbuCvu())) {
			throw bad("Debe informar cuenta o CBU/CVU", "INVALID_BANK_ACCOUNT");
		}

		String raw = !blank(r.cbuCvu()) ? r.cbuCvu() : r.numeroCuenta();
		String hash = tokens.hash("cuenta:" + normalize(raw));
		duplicate(cuenta, hash);
		var medio = medios.save(new MedioPago(
				cuenta,
				"cuenta_bancaria",
				moneda,
				r.nacional(),
				"Cuenta " + r.nombreBanco(),
				null,
				r.titular(),
				hash,
				null));
		cuentasBancarias.save(new CuentaBancaria(
				medio.getId(),
				blank(r.numeroCuenta()) ? null : tokens.hash(normalize(r.numeroCuenta())),
				blank(r.cbuCvu()) ? null : tokens.hash(normalize(r.cbuCvu())),
				r.nombreBanco(),
				r.alias()));
		created(cuenta, medio);
		return response(medio);
	}

	private Long saveChequeImage(Long cuenta, String tipo, MultipartFile file) {
		byte[] bytes = images.validate(file);
		String name = file.getOriginalFilename() == null ? "cheque" : file.getOriginalFilename();
		String contentType = file.getContentType();
		var stored = storage.store(name, contentType, new ByteArrayInputStream(bytes));
		return archivos.save(new ArchivoApp(
				cuenta,
				tipo,
				name,
				contentType,
				stored.sizeBytes(),
				stored.storagePath(),
				stored.checksum()))
				.getId();
	}

	private MedioPagoResponse response(MedioPago m) {
		String banco = cuentasBancarias.findById(m.getId())
				.map(CuentaBancaria::getNombreBanco)
				.orElseGet(() -> cheques.findById(m.getId())
						.map(ChequeCertificado::getBancoEmisor)
						.orElse(null));
		BigDecimal limiteMonto = m.getLimiteMonto();
		BigDecimal limiteUsado = limiteMonto == null
				? null
				: m.getConsumoActual().max(BigDecimal.ZERO)
						.add(queries.activeReservationsForPaymentMethod(m.getId()));
		BigDecimal limiteDisponible = limiteMonto == null
				? null
				: limiteMonto.subtract(limiteUsado).max(BigDecimal.ZERO);
		return new MedioPagoResponse(
				m.getId(),
				m.getTipo(),
				m.getMoneda(),
				m.getEstado(),
				m.getPrincipal(),
				m.getAliasVisible(),
				m.getUltimos4(),
				banco,
				m.getSaldoGarantia(),
				limiteMonto,
				limiteUsado,
				limiteDisponible,
				m.getVerificadoHasta(),
				m.getCreatedAt());
	}

	private CuentaApp account(Long id) {
		return cuentas.findById(id)
				.orElseThrow(() -> new BusinessException(
						HttpStatus.NOT_FOUND,
						"Cuenta inexistente",
						"RESOURCE_NOT_FOUND"));
	}

	private MedioPago owned(Long cuenta, Long id) {
		return medios.findByIdAndCuentaId(id, cuenta)
				.orElseThrow(() ->
						medios.existsById(id)
								? new BusinessException(
										HttpStatus.FORBIDDEN,
										"El recurso no pertenece al usuario",
										"RESOURCE_NOT_OWNED")
								: notFound());
	}

	private void duplicate(Long c, String h) {
		if (medios.findByCuentaIdAndHashIdentificadorAndDeletedAtIsNull(c, h).isPresent()) {
			throw conflict("El medio ya existe", "PAYMENT_METHOD_DUPLICATE");
		}
	}

	private void created(Long c, MedioPago m) {
		audit(c, "medio_pago.creado", m.getId());
		notificaciones.save(new NotificacionApp(
				c,
				"medio_pago_pendiente",
				"Medio de pago en revisión",
				"Tu medio de pago quedó pendiente de verificación manual.",
				"medio_pago",
				m.getId()));
	}

	private void audit(Long c, String action, Long id) {
		audit.record(new AuditEvent("usuario", c, action, "medio_pago", id, "{}"));
	}

	private boolean validExpiry(Integer year, Integer month) {
		try {
			return year != null && month != null && !YearMonth.of(year, month).isBefore(YearMonth.now());
		} catch (java.time.DateTimeException e) {
			return false;
		}
	}

	private String currency(String v) {
		v = value(v);
		if (!Set.of("ARS", "USD").contains(v)) {
			throw bad("Moneda inválida", "INVALID_CURRENCY");
		}
		return v;
	}

	private String value(String v) {
		if (blank(v)) {
			throw bad("Campo obligatorio", "INVALID_FIELD");
		}
		return v.trim();
	}

	private void required(String v, String f) {
		if (blank(v)) {
			throw bad("Falta " + f, "INVALID_FIELD");
		}
	}

	private String normalize(String v) {
		return v == null ? "" : v.replaceAll("[^A-Za-z0-9]", "").toLowerCase();
	}

	private boolean blank(String v) {
		return v == null || v.isBlank();
	}

	private BusinessException bad(String m, String c) {
		return new BusinessException(HttpStatus.BAD_REQUEST, m, c);
	}

	private BusinessException conflict(String m, String c) {
		return new BusinessException(HttpStatus.CONFLICT, m, c);
	}

	private BusinessException notFound() {
		return new BusinessException(HttpStatus.NOT_FOUND, "Medio inexistente", "RESOURCE_NOT_FOUND");
	}
}
