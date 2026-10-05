package com.example.quickbid.quickbid.service;

import java.io.ByteArrayInputStream;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.sql.PreparedStatement;
import java.security.MessageDigest;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import com.example.quickbid.quickbid.audit.AuditEvent;
import com.example.quickbid.quickbid.audit.AuditService;
import com.example.quickbid.quickbid.dto.request.ConsignmentAgreementAcceptanceRequest;
import com.example.quickbid.quickbid.dto.request.ConsignmentReturnPaymentRequest;
import com.example.quickbid.quickbid.dto.request.ConsignmentReturnRequest;
import com.example.quickbid.quickbid.dto.response.ConsignmentDtos;
import com.example.quickbid.quickbid.dto.response.ConsignmentDtos.Detail;
import com.example.quickbid.quickbid.dto.response.ConsignmentDtos.File;
import com.example.quickbid.quickbid.dto.response.ConsignmentDtos.Liquidation;
import com.example.quickbid.quickbid.dto.response.ConsignmentDtos.Page;
import com.example.quickbid.quickbid.dto.response.ConsignmentDtos.Policy;
import com.example.quickbid.quickbid.dto.response.ConsignmentDtos.Requirement;
import com.example.quickbid.quickbid.dto.response.ConsignmentDtos.Requirements;
import com.example.quickbid.quickbid.dto.response.ConsignmentDtos.ReturnPayment;
import com.example.quickbid.quickbid.dto.response.ConsignmentDtos.Summary;
import com.example.quickbid.quickbid.entity.app.ArchivoApp;
import com.example.quickbid.quickbid.entity.app.CuentaApp;
import com.example.quickbid.quickbid.entity.app.DireccionEnvio;
import com.example.quickbid.quickbid.exception.BusinessException;
import com.example.quickbid.quickbid.repository.app.ArchivoAppRepository;
import com.example.quickbid.quickbid.repository.app.CuentaAppRepository;
import com.example.quickbid.quickbid.repository.app.DireccionEnvioRepository;
import com.example.quickbid.quickbid.repository.app.PaymentMethodQueryRepository;
import com.example.quickbid.quickbid.storage.StorageService;
import com.example.quickbid.quickbid.storage.FileDownload;

@Service
public class ConsignmentService {
	private static final Set<String> CATEGORIES = Set.of("comun", "especial", "plata", "oro", "platino");
	private static final BigDecimal DEFAULT_COMMISSION = new BigDecimal("10.00");
	private final JdbcTemplate jdbc;
	private final CuentaAppRepository accounts;
	private final ArchivoAppRepository files;
	private final StorageService storage;
	private final ImageValidationService images;
	private final DocumentValidationService documents;
	private final CategoriaService categories;
	private final AuditService audit;
	private final MailNotificationService mail;
	private final PaymentMethodQueryRepository paymentMethodQueries;
	private final DireccionEnvioRepository addresses;
	private final BigDecimal returnShippingCost;
	private final int publishedPoints;
	private final SimplePdfService pdfs;

	public ConsignmentService(JdbcTemplate jdbc, CuentaAppRepository accounts, ArchivoAppRepository files,
			StorageService storage, ImageValidationService images, DocumentValidationService documents,
			CategoriaService categories, AuditService audit, MailNotificationService mail,
			PaymentMethodQueryRepository paymentMethodQueries, DireccionEnvioRepository addresses,
			@Value("${app.consignment.return-shipping-flat-cost:12000}") BigDecimal returnShippingCost,
			@Value("${app.consignment.published-points:70}") int publishedPoints, SimplePdfService pdfs) {
		this.jdbc = jdbc;
		this.accounts = accounts;
		this.files = files;
		this.storage = storage;
		this.images = images;
		this.documents = documents;
		this.categories = categories;
		this.audit = audit;
		this.mail = mail;
		this.paymentMethodQueries = paymentMethodQueries;
		this.addresses = addresses;
		this.returnShippingCost = returnShippingCost;
		this.publishedPoints = publishedPoints;
		this.pdfs = pdfs;
	}

	@Transactional(readOnly = true)
	public Requirements requirements(Long accountId) {
		accountAllowed(accountId);
		var values = paymentMethodQueries.findConsignmentRequirements(accountId);
		boolean payment = values.registeredPayment();
		boolean collectionAccount = values.collectionAccount();
		boolean usableReturnPayment = values.usableReturnPayment();
		return new Requirements(payment && collectionAccount, List.of(
				new Requirement("MEDIO_PAGO_REGISTRADO",
						"Medio de pago registrado para afrontar una eventual devolución", payment),
				new Requirement("CUENTA_COBRO_REGISTRADA",
						"Cuenta bancaria registrada como destino de una eventual liquidación", collectionAccount),
				new Requirement("MEDIO_PAGO_APTO_PARA_ENVIO_DEVOLUCION",
						"Advertencia: para pagar un eventual envío de devolución necesitarás un medio verificado con validación manual vigente",
						usableReturnPayment)),
				6);
	}

	@Transactional
	public Detail create(Long accountId, String segment, String ignoredAuctionCategory, Boolean acceptsTerms,
			Boolean ownershipDeclaration, String title, String description, String history, String approximateDate,
			Boolean artwork, String author, String extendedHistory, String idempotencyKey, List<MultipartFile> photos) {
		CuentaApp account = accountAllowed(accountId);
		String normalizedIdempotencyKey = normalizeIdempotencyKey(idempotencyKey);
		Long replayId = findConsignmentByIdempotencyKey(accountId, normalizedIdempotencyKey);
		if (replayId != null) return detail(accountId, replayId);
		if (!Boolean.TRUE.equals(acceptsTerms) || !Boolean.TRUE.equals(ownershipDeclaration)) {
			throw bad("Debés aceptar términos y declarar propiedad y origen lícito", "CONSIGNMENT_DECLARATION_REQUIRED");
		}
		if (!requirements(accountId).puedeContinuar()) {
			throw forbidden("Faltan requisitos para consignar", "CONSIGNMENT_REQUIREMENTS_NOT_MET");
		}
		if (photos == null || photos.size() < 6) throw bad("Se requieren al menos seis fotos", "MINIMUM_PHOTOS_REQUIRED");
		if (photos.size() > 15) throw bad("Se permite un máximo de quince fotos", "MAXIMUM_PHOTOS_EXCEEDED");
		required(title, "titulo");
		required(description, "descripcion");
		SegmentAndCategory classification = classifyPublicCreation(segment);
		if (normalizedIdempotencyKey != null) {
			jdbc.queryForObject("SELECT id FROM app_cuentas WHERE id=? FOR UPDATE", Long.class, accountId);
			replayId = findConsignmentByIdempotencyKey(accountId, normalizedIdempotencyKey);
			if (replayId != null) return detail(accountId, replayId);
		}
		long id = insert("""
				INSERT INTO app_solicitudes_consignacion(cuenta_id,cliente_id,titulo,descripcion,segmento,categoria_sugerida,
					historia,historia_extendida,artista_disenador,fecha_objeto,idempotency_key,
					declaracion_propiedad,acepta_devolucion_con_cargo,estado)
				VALUES (?,?,?,?,?,?,?,?,?,?,?,true,true,'pendiente_revision')
				""", statement -> {
			statement.setLong(1, accountId);
			statement.setInt(2, account.getClienteId());
			statement.setString(3, title.trim());
			statement.setString(4, description.trim());
			statement.setString(5, classification.segment());
			statement.setString(6, classification.category());
			statement.setString(7, blankToNull(history));
			statement.setString(8, blankToNull(extendedHistory));
			statement.setString(9, Boolean.TRUE.equals(artwork) ? blankToNull(author) : null);
			statement.setString(10, blankToNull(approximateDate));
			statement.setString(11, normalizedIdempotencyKey);
		});
		int order = 0;
		for (MultipartFile photo : photos) {
			Long fileId = saveImage(accountId, "consignacion", photo);
			jdbc.update("INSERT INTO app_consignacion_fotos(solicitud_id,archivo_id,orden) VALUES (?,?,?)", id, fileId,
					++order);
		}
		notify(accountId, "consignacion_creada", "Consignación recibida",
				"Recibimos la solicitud y sus fotos para revisión.", id);
		audit(accountId, "consignacion.creada", id);
		return detail(accountId, id);
	}

	@Transactional
	public Detail uploadOriginDocuments(Long accountId, Long id, List<MultipartFile> uploaded, String observations) {
		Consignment value = owned(accountId, id, true);
		if (!Set.of("pendiente_revision", "documentacion_adicional").contains(value.state())) {
			throw conflict("La consignación no acepta documentación en su estado actual", "INVALID_STATE_TRANSITION");
		}
		List<MultipartFile> present = uploaded.stream().filter(file -> file != null && !file.isEmpty()).toList();
		if (present.isEmpty()) throw bad("Debés adjuntar al menos un documento", "DOCUMENT_REQUIRED");
		for (MultipartFile file : present) {
			Long fileId = saveDocument(accountId, "documento_consignacion", file);
			jdbc.update("""
					INSERT INTO app_consignacion_documentos_origen(solicitud_id,archivo_id,estado)
					VALUES (?,?,'pendiente')
					""", id, fileId);
		}
		jdbc.update("""
				UPDATE app_solicitudes_consignacion SET estado='pendiente_revision',
					requiere_documentacion_origen=true,updated_at=CURRENT_TIMESTAMP WHERE id=?
				""", id);
		audit.record(new AuditEvent("usuario", accountId, "consignacion.documentacion_origen_recibida", "consignacion", id,
				"{\"observaciones\":\"" + json(observations) + "\"}"));
		return detail(accountId, id);
	}

	@Transactional(readOnly = true)
	public Page<Summary> list(Long accountId, String filter, int page, int size) {
		accountAllowed(accountId);
		checkPage(page, size);
		String predicate = filterPredicate(filter);
		long total = jdbc.queryForObject("SELECT COUNT(*) FROM app_solicitudes_consignacion WHERE cuenta_id=? " + predicate,
				Long.class, accountId);
		List<Summary> values = jdbc.query("""
				SELECT s.id,s.titulo,s.estado,s.valor_base_propuesto,s.moneda_propuesta,s.updated_at,
				       (SELECT archivo_id FROM app_consignacion_fotos f WHERE f.solicitud_id=s.id ORDER BY orden LIMIT 1) foto
				FROM app_solicitudes_consignacion s WHERE s.cuenta_id=? %s
				ORDER BY s.updated_at DESC,s.id DESC LIMIT ? OFFSET ?
				""".formatted(predicate), (rs, row) -> new Summary(rs.getLong("id"), rs.getString("titulo"),
						rs.getString("estado"), rs.getBigDecimal("valor_base_propuesto"), rs.getString("moneda_propuesta"),
						action(rs.getString("estado")), (Long) rs.getObject("foto"),
						rs.getObject("updated_at", OffsetDateTime.class)), accountId, size, page * size);
		return new Page<>(values, page, size, total, (int) Math.ceil((double) total / size));
	}

	@Transactional(readOnly = true)
	public Detail detail(Long accountId, Long id) {
		return detail(owned(accountId, id, false));
	}

	@Transactional
	public Detail acceptAgreement(Long accountId, Long id, ConsignmentAgreementAcceptanceRequest request) {
		Consignment value = owned(accountId, id, true);
		if (!value.state().equals("acuerdo_pendiente")) throw conflict("El acuerdo ya fue procesado", "INVALID_STATE_TRANSITION");
		if (value.reviewerId() == null || value.ownerId() == null) {
			throw conflict("Falta validar consignador o asignar revisor", "CONSIGNOR_NOT_VERIFIED");
		}
		long productId = insert("""
				INSERT INTO productos(fecha,disponible,"descripcionCatalogo","descripcionCompleta",revisor,duenio)
				VALUES (CURRENT_DATE,'si',?,?,?,?)
				""", "identificador", statement -> {
			statement.setString(1, value.title());
			statement.setString(2, truncate(value.description(), 300));
			statement.setInt(3, value.reviewerId());
			statement.setInt(4, value.ownerId());
		});
		copyLegacyPhotos(id, Math.toIntExact(productId));
		jdbc.update("""
				UPDATE app_solicitudes_consignacion SET producto_id=?,estado='acuerdo_aceptado',
					acuerdo_aceptado_at=CURRENT_TIMESTAMP,updated_at=CURRENT_TIMESTAMP WHERE id=?
				""", productId, id);
		createGeneratedDocument(accountId, id, "acuerdo_consignacion", "Acuerdo de consignación aceptado #" + id);
		notify(accountId, "acuerdo_consignacion_aceptado", "Acuerdo aceptado",
				"El artículo quedó listo para ser asignado a una subasta.", id);
		audit(accountId, "consignacion.acuerdo_aceptado", id);
		return detail(accountId, id);
	}

	@Transactional
	public Detail rejectAgreement(Long accountId, Long id) {
		Consignment value = owned(accountId, id, true);
		if (!value.state().equals("acuerdo_pendiente")) throw conflict("El acuerdo ya fue procesado", "INVALID_STATE_TRANSITION");
		jdbc.update("""
				UPDATE app_solicitudes_consignacion SET estado='devolucion_pendiente',
					acuerdo_rechazado_at=CURRENT_TIMESTAMP,motivo_rechazo='Acuerdo rechazado por el consignador.',
					updated_at=CURRENT_TIMESTAMP WHERE id=?
				""", id);
		createReturn(id, "Acuerdo rechazado por el consignador.");
		notify(accountId, "consignacion_devolucion", "Devolución pendiente",
				"Elegí envío o retiro para recuperar el artículo.", id);
		audit(accountId, "consignacion.acuerdo_rechazado", id);
		return detail(accountId, id);
	}

	@Transactional
	public ConsignmentDtos.Return selectReturn(Long accountId, Long id, ConsignmentReturnRequest request) {
		Consignment value = owned(accountId, id, true);
		if (!value.state().equals("devolucion_pendiente") || value.productId() != null) {
			throw conflict("La devolución no puede gestionarse en este estado", "INVALID_STATE_TRANSITION");
		}
		ReturnRow returnValue = returnRow(id, true);
		if (returnValue == null || !returnValue.state().equals("pendiente_decision")) {
			throw conflict("La devolución ya fue gestionada", "INVALID_STATE_TRANSITION");
		}
		String modality = request.modalidad().trim().toLowerCase();
		if (!Set.of("envio", "retiro").contains(modality)) throw bad("Modalidad invalida", "INVALID_RETURN_METHOD");
		Long addressId = null;
		String address = blankToNull(request.direccion());
		String floor = blankToNull(request.piso());
		String postalCode = blankToNull(request.codigoPostal());
		String locality = blankToNull(request.localidad());
		String province = blankToNull(request.provincia());
		String phone = blankToNull(request.telefonoContacto());
		if (modality.equals("envio")) {
			if (request.direccionEnvioId() != null) {
				DireccionEnvio selected = addresses.findById(request.direccionEnvioId())
						.orElseThrow(() -> notFound("Dirección de envío inexistente"));
				if (!selected.getCuentaId().equals(accountId) || selected.getDeletedAt() != null) {
					throw forbidden("La direccion no pertenece al usuario", "RESOURCE_NOT_OWNED");
				}
				addressId = selected.getId();
				address = selected.getCalle() + " " + selected.getNumero();
				floor = selected.getPiso();
				postalCode = selected.getCodigoPostal();
				locality = selected.getLocalidad();
				province = selected.getProvincia();
				phone = selected.getTelefono();
			} else {
				required(address, "direccion");
				required(postalCode, "codigoPostal");
				required(locality, "localidad");
				required(province, "provincia");
			}
		}
		BigDecimal cost = modality.equals("envio") ? returnShippingCost : BigDecimal.ZERO;
		String state = modality.equals("envio") ? "pendiente_pago" : "pendiente_retiro";
		jdbc.update("""
				UPDATE app_consignacion_devoluciones SET modalidad=?,direccion_envio_id=?,direccion=?,piso=?,
					codigo_postal=?,localidad=?,provincia=?,telefono_contacto=?,costo=?,estado=? WHERE id=?
				""", modality, addressId, address, floor, postalCode, locality, province, phone, cost, state,
				returnValue.id());
		audit(accountId, "consignacion.devolucion_seleccionada", id);
		return returnDto(returnRow(id, false));
	}

	@Transactional(readOnly = true)
	public ConsignmentDtos.ReturnPreview previewReturn(Long accountId, Long id, ConsignmentReturnRequest request) {
		Consignment value = owned(accountId, id, false);
		if (!value.state().equals("devolucion_pendiente") || value.productId() != null) {
			throw conflict("La devolucion no puede gestionarse en este estado", "INVALID_STATE_TRANSITION");
		}
		ReturnRow returnValue = returnRow(id, false);
		if (returnValue == null || !returnValue.state().equals("pendiente_decision")) {
			throw conflict("La devolucion ya fue gestionada", "INVALID_STATE_TRANSITION");
		}
		String modality = request.modalidad().trim().toLowerCase();
		if (!Set.of("envio", "retiro").contains(modality)) throw bad("Modalidad invalida", "INVALID_RETURN_METHOD");
		Long addressId = null;
		String summary = null;
		if (modality.equals("envio")) {
			if (request.direccionEnvioId() != null) {
				DireccionEnvio selected = addresses.findById(request.direccionEnvioId())
						.orElseThrow(() -> notFound("Dirección de envío inexistente"));
				if (!selected.getCuentaId().equals(accountId) || selected.getDeletedAt() != null) {
					throw forbidden("La direccion no pertenece al usuario", "RESOURCE_NOT_OWNED");
				}
				addressId = selected.getId();
				summary = selected.getCalle() + " " + selected.getNumero()
						+ (selected.getPiso() == null ? "" : ", " + selected.getPiso())
						+ ", " + selected.getLocalidad() + ", " + selected.getProvincia()
						+ " (" + selected.getCodigoPostal() + ")";
			} else {
				String address = blankToNull(request.direccion());
				String floor = blankToNull(request.piso());
				String postalCode = blankToNull(request.codigoPostal());
				String locality = blankToNull(request.localidad());
				String province = blankToNull(request.provincia());
				required(address, "direccion");
				required(postalCode, "codigoPostal");
				required(locality, "localidad");
				required(province, "provincia");
				summary = address + (floor == null ? "" : ", " + floor) + ", " + locality + ", " + province
						+ " (" + postalCode + ")";
			}
		}
		BigDecimal cost = modality.equals("envio") ? returnShippingCost : BigDecimal.ZERO;
		return new ConsignmentDtos.ReturnPreview(modality, addressId, cost, returnValue.currency(), cost, summary);
	}

	@Transactional
	public ReturnPayment payReturnShipping(Long accountId, Long id, ConsignmentReturnPaymentRequest request) {
		owned(accountId, id, true);
		ReturnRow value = returnRow(id, true);
		if (value == null) throw conflict("El envio no admite pago", "INVALID_STATE_TRANSITION");
		String key = normalizedKey(request.idempotencyKey());
		ReturnPayment replay = replayReturnPayment(accountId, value.id(), request.medioPagoId(), key);
		if (replay != null) return replay;
		if (!value.state().equals("pendiente_pago")) {
			throw conflict("El envio no admite pago", "INVALID_STATE_TRANSITION");
		}
		PaymentMethod method = paymentMethod(accountId, request.medioPagoId());
		validatePayment(method, value.currency(), value.cost());
		long paymentId = insert("""
				INSERT INTO app_pagos(consignacion_devolucion_id,medio_pago_id,monto,moneda,estado,referencia_externa,
					idempotency_key) VALUES (?,?,?,?, 'aprobado',?,?)
				""", statement -> {
			statement.setLong(1, value.id());
			statement.setLong(2, request.medioPagoId());
			statement.setBigDecimal(3, value.cost());
			statement.setString(4, value.currency());
			statement.setString(5, "SIM-RETURN-" + UUID.randomUUID());
			statement.setString(6, key);
		});
		jdbc.update("UPDATE app_medios_pago SET consumo_actual=consumo_actual+?,updated_at=CURRENT_TIMESTAMP WHERE id=?",
				value.cost(), request.medioPagoId());
		jdbc.update("UPDATE app_consignacion_devoluciones SET pago_id=?,estado='pendiente_entrega' WHERE id=?",
				paymentId, value.id());
		createGeneratedDocument(accountId, id, "comprobante_envio_devolucion",
				"Comprobante de envío de devolución #" + id);
		audit(accountId, "consignacion.devolucion_envio_pagado", id);
		return new ReturnPayment(paymentId, value.id(), request.medioPagoId(), value.cost(), value.currency(),
				"aprobado", false);
	}

	@Transactional
	public void requestOriginDocuments(Long id, Integer employeeId) {
		Consignment value = lock(id);
		if (!value.state().equals("pendiente_revision")) throw invalidState();
		updateState(id, "documentacion_adicional");
		jdbc.update("UPDATE app_solicitudes_consignacion SET requiere_documentacion_origen=true,revisor_empleado_id=? WHERE id=?",
				employeeId, id);
		notify(value.accountId(), "consignacion_documentacion_requerida", "Documentación adicional requerida",
				"Adjuntá documentación para acreditar el origen lícito del artículo.", id);
		auditAdmin(employeeId, "consignacion.documentacion_solicitada", id);
	}

	@Transactional
	public void reviewOriginDocuments(Long id, Integer employeeId, boolean approved, String reason) {
		Consignment value = lock(id);
		if (!Set.of("pendiente_revision", "documentacion_recibida").contains(value.state())) throw invalidState();
		if (count("SELECT COUNT(*) FROM app_consignacion_documentos_origen WHERE solicitud_id=? AND estado='pendiente'",
				id) == 0) throw invalidState();
		jdbc.update("UPDATE app_consignacion_documentos_origen SET estado=? WHERE solicitud_id=? AND estado='pendiente'",
				approved ? "aprobado" : "rechazado", id);
		if (approved) {
			updateState(id, "recepcion_pendiente");
			notify(value.accountId(), "consignacion_aprobada", "Consignación aprobada",
					"La revisión documental fue aprobada y el bien puede avanzar a recepción.", id);
		} else {
			rejectInitial(id, employeeId, reason);
		}
		auditAdmin(employeeId, approved ? "consignacion.documentacion_aprobada" : "consignacion.documentacion_rechazada", id);
	}

	@Transactional
	public void approveDigitalReview(Long id, Integer employeeId) {
		Consignment value = lock(id);
		if (!value.state().equals("pendiente_revision")) throw invalidState();
		jdbc.update("UPDATE app_solicitudes_consignacion SET estado='recepcion_pendiente',revisor_empleado_id=?,updated_at=CURRENT_TIMESTAMP WHERE id=?",
				employeeId, id);
		notify(value.accountId(), "consignacion_aprobada", "Consignación aprobada",
				"La revisión inicial fue aprobada y el bien puede avanzar a recepción.", id);
		auditAdmin(employeeId, "consignacion.revision_digital_aprobada", id);
	}

	@Transactional
	public void assignAuctionCategory(Long id, Integer employeeId, String category) {
		Consignment value = lock(id);
		if (!Set.of("pendiente_revision", "documentacion_recibida", "recepcion_pendiente", "revision_fisica")
				.contains(value.state())) throw invalidState();
		String normalized = blankToNull(category);
		if (normalized == null || !CATEGORIES.contains(normalized.toLowerCase())) {
			throw bad("Categoria de subasta invalida", "INVALID_CATEGORY");
		}
		jdbc.update("UPDATE app_solicitudes_consignacion SET categoria_sugerida=?,revisor_empleado_id=?,updated_at=CURRENT_TIMESTAMP WHERE id=?",
				normalized.toLowerCase(), employeeId, id);
		auditAdmin(employeeId, "consignacion.categoria_asignada", id);
	}

	@Transactional
	public void rejectDigitalReview(Long id, Integer employeeId, String reason) {
		Consignment value = lock(id);
		if (!Set.of("pendiente_revision", "documentacion_recibida").contains(value.state())) throw invalidState();
		rejectInitial(id, employeeId, reason);
		auditAdmin(employeeId, "consignacion.revision_digital_rechazada", id);
	}

	@Transactional
	public void markPhysicalReception(Long id, Integer employeeId) {
		Consignment value = lock(id);
		if (!value.state().equals("recepcion_pendiente")) throw invalidState();
		jdbc.update("UPDATE app_solicitudes_consignacion SET estado='revision_fisica',revisor_empleado_id=?,updated_at=CURRENT_TIMESTAMP WHERE id=?",
				employeeId, id);
		auditAdmin(employeeId, "consignacion.recepcion_fisica", id);
	}

	@Transactional
	public void approvePhysicalReview(Long id, Integer employeeId) {
		Consignment value = lock(id);
		if (!value.state().equals("revision_fisica")) throw invalidState();
		jdbc.update("UPDATE app_solicitudes_consignacion SET estado='revision_fisica_aprobada',revisor_empleado_id=?,updated_at=CURRENT_TIMESTAMP WHERE id=?",
				employeeId, id);
		auditAdmin(employeeId, "consignacion.revision_fisica_aprobada", id);
	}

	@Transactional
	public void rejectPhysicalReview(Long id, Integer employeeId, String reason) {
		Consignment value = lock(id);
		if (!value.state().equals("revision_fisica")) throw invalidState();
		required(reason, "motivo");
		jdbc.update("""
				UPDATE app_solicitudes_consignacion SET estado='devolucion_pendiente',motivo_rechazo=?,
					revisor_empleado_id=?,updated_at=CURRENT_TIMESTAMP WHERE id=?
				""", reason.trim(), employeeId, id);
		createReturn(id, reason.trim());
		notify(value.accountId(), "consignacion_rechazada", "Consignación rechazada",
				"La revisión física fue rechazada. Debés gestionar la devolución del bien.", id);
		auditAdmin(employeeId, "consignacion.revision_fisica_rechazada", id);
	}

	@Transactional
	public Integer verifyConsignor(Long accountId, Integer employeeId, boolean financial, boolean judicial, int risk) {
		CuentaApp account = accountAllowed(accountId);
		if (risk < 1 || risk > 6) throw bad("Calificacion de riesgo invalida", "INVALID_RISK_SCORE");
		List<Integer> existing = jdbc.query("SELECT identificador FROM duenios WHERE identificador=?",
				(rs, row) -> rs.getInt(1), account.getPersonaId());
		if (existing.isEmpty()) {
			jdbc.update("""
					INSERT INTO duenios(identificador,"numeroPais","verificacionFinanciera","verificacionJudicial",
						"calificacionRiesgo",verificador)
					SELECT ?,c."numeroPais",?,?,?,? FROM clientes c WHERE c.identificador=?
					""", account.getPersonaId(), financial ? "si" : "no", judicial ? "si" : "no", risk, employeeId,
					account.getClienteId());
		} else {
			jdbc.update("""
					UPDATE duenios SET "verificacionFinanciera"=?,"verificacionJudicial"=?,
						"calificacionRiesgo"=?,verificador=? WHERE identificador=?
					""", financial ? "si" : "no", judicial ? "si" : "no", risk, employeeId, account.getPersonaId());
		}
		auditAdmin(employeeId, "consignador.verificado", accountId);
		return account.getPersonaId();
	}

	@Transactional
	public void proposeAgreement(Long id, Integer employeeId, BigDecimal baseValue, String currency,
			BigDecimal buyerCommissionPct, BigDecimal sellerCommissionPct, String text) {
		Consignment value = lock(id);
		if (!value.state().equals("revision_fisica_aprobada")) throw invalidState();
		if (value.ownerId() == null) throw conflict("El consignador todavia no fue verificado", "CONSIGNOR_NOT_VERIFIED");
		if (baseValue == null || baseValue.signum() <= 0) throw bad("Valor base invalido", "INVALID_BASE_PRICE");
		String normalizedCurrency = currency(currency);
		BigDecimal buyerPct = commissionPct(buyerCommissionPct);
		BigDecimal sellerPct = commissionPct(sellerCommissionPct);
		required(text, "acuerdoTexto");
		jdbc.update("""
				UPDATE app_solicitudes_consignacion SET estado='acuerdo_pendiente',revisor_empleado_id=?,
					valor_base_propuesto=?,moneda_propuesta=?,comision_comprador_pct=?,comision_vendedor_pct=?,
					acuerdo_texto=?,acuerdo_enviado_at=CURRENT_TIMESTAMP,updated_at=CURRENT_TIMESTAMP WHERE id=?
				""", employeeId, baseValue, normalizedCurrency, buyerPct, sellerPct, text.trim(), id);
		notify(value.accountId(), "acuerdo_disponible", "Acuerdo disponible",
				"Revisá el acuerdo de consignación para aceptar o rechazar la propuesta.", id);
		auditAdmin(employeeId, "consignacion.acuerdo_propuesto", id);
	}

	@Transactional
	public Integer assignAuctionAndInsurance(Long id, Integer employeeId, Integer auctionId, Integer catalogId,
			String combinedPolicyNumber) {
		Consignment value = lock(id);
		if (!value.state().equals("acuerdo_aceptado") || value.productId() == null) throw invalidState();
		Auction auction = auction(auctionId);
		if (!auction.currency().equals(value.currency())) throw unprocessable("Moneda incompatible", "CURRENCY_MISMATCH");
		if (count("SELECT COUNT(*) FROM catalogos WHERE identificador=? AND subasta=?", catalogId, auctionId) == 0) {
			throw notFound("Catalogo inexistente para la subasta");
		}
		String policy = insurance(value, auctionId, combinedPolicyNumber);
		BigDecimal buyerCommission = value.baseValue().multiply(value.buyerCommissionPct())
				.divide(new BigDecimal("100"), 2, RoundingMode.HALF_UP);
		long itemId = insert("""
				INSERT INTO "itemsCatalogo"(catalogo,producto,"precioBase",comision,subastado)
				VALUES (?,?,?,?,'no')
				""", "identificador", statement -> {
			statement.setInt(1, catalogId);
			statement.setInt(2, value.productId());
			statement.setBigDecimal(3, value.baseValue());
			statement.setBigDecimal(4, buyerCommission);
		});
		jdbc.update("UPDATE productos SET seguro=? WHERE identificador=?", policy, value.productId());
		jdbc.update("""
				UPDATE app_solicitudes_consignacion SET item_catalogo_id=?,subasta_id=?,estado='publicada',
					ubicacion_fisica=?,updated_at=CURRENT_TIMESTAMP WHERE id=?
				""", itemId, auctionId, custodyLocation(auction.location()), id);
		createGeneratedDocument(value.accountId(), id, "poliza_consignacion",
				"Póliza de seguro registrada para el bien consignado.");
		categories.addPoints(account(value.accountId()), publishedPoints, "consignacion_publicada", "consignacion", id);
		notify(value.accountId(), "consignacion_publicada", "Artículo publicado",
				"El artículo fue asignado a subasta y ya posee póliza de seguro.", id);
		auditAdmin(employeeId, "consignacion.publicada", id);
		return Math.toIntExact(itemId);
	}

	@Transactional
	public Liquidation liquidate(Long id, Integer employeeId, Long bankPaymentMethodId) {
		Consignment value = lock(id);
		if (count("SELECT COUNT(*) FROM app_liquidaciones_consignacion WHERE solicitud_id=?", id) > 0) {
			throw conflict("La liquidación ya fue generada", "LIQUIDATION_ALREADY_EXISTS");
		}
		if (!Set.of("vendida", "comprada_por_empresa").contains(value.state())) throw invalidState();
		PaymentMethod bank = paymentMethod(value.accountId(), bankPaymentMethodId);
		if (!bank.type().equals("cuenta_bancaria")) throw unprocessable("Se requiere cuenta bancaria", "BANK_ACCOUNT_REQUIRED");
		validateLiquidationDestination(bank);
		Purchase purchase = purchase(value.itemId());
		BigDecimal commission = purchase.amount().multiply(value.sellerCommissionPct())
				.divide(new BigDecimal("100"), 2, RoundingMode.HALF_UP);
		BigDecimal net = purchase.amount().subtract(commission);
		String destination = "Cuenta bancaria registrada #" + bankPaymentMethodId;
		long liquidationId = insert("""
				INSERT INTO app_liquidaciones_consignacion(solicitud_id,compra_id,monto_bruto,comision,monto_neto,
					cuenta_destino,estado,paid_at) VALUES (?,?,?,?,?,?,'pagada',CURRENT_TIMESTAMP)
				""", statement -> {
			statement.setLong(1, id);
			statement.setLong(2, purchase.id());
			statement.setBigDecimal(3, purchase.amount());
			statement.setBigDecimal(4, commission);
			statement.setBigDecimal(5, net);
			statement.setString(6, destination);
		});
		updateState(id, "liquidada");
		createGeneratedDocument(value.accountId(), id, "liquidacion_venta", "Liquidación de venta #" + id);
		notify(value.accountId(), "liquidacion_disponible", "Liquidación disponible",
				"La liquidación de tu artículo fue emitida.", id);
		auditAdmin(employeeId, "consignacion.liquidada", id);
		return liquidation(id);
	}

	@Transactional
	public void markReturnIncomplete(Long id, Integer employeeId) {
		Consignment value = lock(id);
		if (!value.state().equals("devolucion_pendiente")) throw invalidState();
		updateState(id, "devolucion_incompleta");
		jdbc.update("UPDATE app_consignacion_devoluciones SET estado='incumplida' WHERE solicitud_id=?", id);
		notify(value.accountId(), "consignacion_devolucion_incumplida", "Devolución vencida",
				"La devolución quedó marcada como incompleta por vencimiento del plazo.", id);
		if (employeeId == null) {
			audit.record(new AuditEvent("sistema", null, "consignacion.devolucion_incumplida", "consignacion", id, "{}"));
		} else {
			auditAdmin(employeeId, "consignacion.devolucion_incumplida", id);
		}
	}

	@Transactional
	public int expireDueReturns(Integer employeeId) {
		List<Long> due = jdbc.query("""
				SELECT s.id FROM app_solicitudes_consignacion s
				JOIN app_consignacion_devoluciones d ON d.solicitud_id=s.id
				WHERE s.estado='devolucion_pendiente'
				  AND d.estado IN ('pendiente_decision','pendiente_retiro','pendiente_pago')
				  AND d.created_at<=?
				ORDER BY d.created_at,s.id FOR UPDATE SKIP LOCKED
				""", (rs, row) -> rs.getLong(1), OffsetDateTime.now().minusHours(72));
		due.forEach(id -> markReturnIncomplete(id, employeeId));
		return due.size();
	}

	@Transactional(readOnly = true)
	public FileDownload downloadFile(Long accountId, Long id, Long fileId) {
		owned(accountId, id, false);
		List<StoredFile> values = jdbc.query("""
				SELECT a.filename_original,a.content_type,a.size_bytes,a.storage_path,a.content_bytes
				FROM app_archivos a
				WHERE a.id=? AND (
				  EXISTS (SELECT 1 FROM app_consignacion_fotos f WHERE f.solicitud_id=? AND f.archivo_id=a.id)
				  OR EXISTS (SELECT 1 FROM app_consignacion_documentos_origen d WHERE d.solicitud_id=? AND d.archivo_id=a.id)
				  OR EXISTS (SELECT 1 FROM app_documentos d WHERE d.referencia_tipo='consignacion'
				      AND d.referencia_id=? AND d.archivo_id=a.id AND d.estado='disponible')
				)
				""", (rs, row) -> new StoredFile(rs.getString("filename_original"), rs.getString("content_type"),
					rs.getLong("size_bytes"), rs.getString("storage_path"), rs.getBytes("content_bytes")), fileId, id, id, id);
		if (values.isEmpty() || values.get(0).sizeBytes() <= 0) {
			throw notFound("Archivo inexistente o no disponible");
		}
		StoredFile file = values.get(0);
		if (file.contentBytes() != null && file.contentBytes().length > 0) {
			return new FileDownload(file.filename(), file.contentType(), file.contentBytes());
		}
		if (!storage.exists(file.storagePath())) throw notFound("Archivo inexistente o no disponible");
		try (var input = storage.load(file.storagePath())) {
			byte[] content = input.readAllBytes();
			if (content.length == 0) throw notFound("Archivo inexistente o no disponible");
			return new FileDownload(file.filename(), file.contentType(), content);
		} catch (java.io.IOException | IllegalStateException exception) {
			throw notFound("Archivo inexistente o no disponible");
		}
	}

	private Detail detail(Consignment value) {
		BigDecimal estimatedNet = value.baseValue() == null || value.sellerCommissionPct() == null ? null
				: value.baseValue().subtract(value.baseValue().multiply(value.sellerCommissionPct())
						.divide(new BigDecimal("100"), 2, RoundingMode.HALF_UP));
		return new Detail(value.id(), value.title(), value.description(), value.segment(), value.category(),
				value.category(), value.history(), value.artist(), value.objectDate(), value.state(),
				value.requiresDocuments(), value.rejectionReason(), value.productId(), value.itemId(), value.auctionId(),
				value.baseValue(), value.currency(), value.buyerCommissionPct(), value.sellerCommissionPct(),
				estimatedNet, value.agreementText(), value.agreementSentAt(), value.agreementAcceptedAt(),
				auctionDateTime(value.auctionId()), value.location(), policy(value), photos(value.id()),
				originDocuments(value.id()), generatedDocuments(value.id()), returnDto(returnRow(value.id(), false)),
				liquidationOrNull(value.id()),
				value.createdAt(), value.updatedAt());
	}

	private Consignment owned(Long accountId, Long id, boolean lock) {
		accountAllowed(accountId);
		Consignment value = lock ? lock(id) : consignment(id, "");
		if (!value.accountId().equals(accountId)) throw forbidden("La consignación no pertenece al usuario", "RESOURCE_NOT_OWNED");
		return value;
	}

	private Consignment lock(Long id) {
		List<Long> locked = jdbc.query("""
				SELECT id FROM app_solicitudes_consignacion WHERE id=? FOR UPDATE
				""", (rs, row) -> rs.getLong(1), id);
		if (locked.isEmpty()) throw notFound("Consignación inexistente");
		return consignment(id, "");
	}

	private Consignment consignment(Long id, String suffix) {
		List<Consignment> values = jdbc.query("""
				SELECT s.*,d.identificador owner_id FROM app_solicitudes_consignacion s
				LEFT JOIN app_cuentas c ON c.id=s.cuenta_id LEFT JOIN duenios d ON d.identificador=c.persona_id
				WHERE s.id=?
				""" + suffix, (rs, row) -> new Consignment(rs.getLong("id"), rs.getLong("cuenta_id"),
						rs.getInt("cliente_id"), (Integer) rs.getObject("owner_id"), (Integer) rs.getObject("producto_id"),
						(Integer) rs.getObject("item_catalogo_id"), (Integer) rs.getObject("subasta_id"),
						rs.getString("titulo"), rs.getString("descripcion"), rs.getString("segmento"),
						rs.getString("categoria_sugerida"), rs.getString("historia"), rs.getString("artista_disenador"),
						rs.getString("fecha_objeto"), rs.getString("estado"), rs.getBoolean("requiere_documentacion_origen"),
						rs.getString("motivo_rechazo"), (Integer) rs.getObject("revisor_empleado_id"),
						rs.getBigDecimal("valor_base_propuesto"), rs.getString("moneda_propuesta"),
						rs.getBigDecimal("comision_comprador_pct"), rs.getBigDecimal("comision_vendedor_pct"),
						rs.getString("acuerdo_texto"), rs.getString("ubicacion_fisica"),
						rs.getObject("acuerdo_enviado_at", OffsetDateTime.class),
						rs.getObject("acuerdo_aceptado_at", OffsetDateTime.class),
						rs.getObject("created_at", OffsetDateTime.class), rs.getObject("updated_at", OffsetDateTime.class)),
				id);
		if (values.isEmpty()) throw notFound("Consignacion inexistente");
		return values.get(0);
	}

	private List<File> photos(Long id) {
		return jdbc.query("""
				SELECT a.id,a.filename_original,a.content_type,a.size_bytes,a.storage_path FROM app_consignacion_fotos f
				JOIN app_archivos a ON a.id=f.archivo_id WHERE f.solicitud_id=? ORDER BY f.orden
				""", (rs, row) -> file(id, rs.getLong("id"), rs.getString("filename_original"),
						rs.getString("content_type"), rs.getLong("size_bytes"), "disponible",
						rs.getString("storage_path"), false, null), id);
	}

	private List<File> originDocuments(Long id) {
		return jdbc.query("""
				SELECT a.id,a.filename_original,a.content_type,a.size_bytes,a.storage_path,d.estado FROM app_consignacion_documentos_origen d
				JOIN app_archivos a ON a.id=d.archivo_id WHERE d.solicitud_id=? ORDER BY d.created_at,d.id
				""", (rs, row) -> file(id, rs.getLong("id"), rs.getString("filename_original"),
						rs.getString("content_type"), rs.getLong("size_bytes"), rs.getString("estado"),
						rs.getString("storage_path"), false, null), id);
	}

	private List<File> generatedDocuments(Long id) {
		return jdbc.query("""
				SELECT a.id,a.filename_original,a.content_type,a.size_bytes,a.storage_path,a.content_bytes,d.estado,d.tipo
				FROM app_documentos d JOIN app_archivos a ON a.id=d.archivo_id
				WHERE d.referencia_tipo='consignacion' AND d.referencia_id=? AND d.estado='disponible'
				ORDER BY d.created_at,d.id
				""", (rs, row) -> file(id, rs.getLong("id"), rs.getString("filename_original"),
						rs.getString("content_type"), rs.getLong("size_bytes"), rs.getString("estado"),
						rs.getString("storage_path"), rs.getBytes("content_bytes") != null,
						rs.getString("tipo")), id);
	}

	private File file(Long consignmentId, Long fileId, String filename, String contentType, Long sizeBytes,
			String state, String storagePath, boolean databaseContent, String type) {
		boolean available = sizeBytes > 0 && (databaseContent || storage.exists(storagePath));
		String url = available ? "/api/consignaciones/" + consignmentId + "/archivos/" + fileId + "/descargar" : null;
		return new File(fileId, filename, contentType, sizeBytes, state, available, url, type);
	}

	private Policy policy(Consignment value) {
		if (value.productId() == null) return null;
		List<Policy> values = jdbc.query("""
				SELECT s."nroPoliza",s.compania,s."polizaCombinada",s.importe FROM productos p
				JOIN seguros s ON s."nroPoliza"=p.seguro WHERE p.identificador=?
				""", (rs, row) -> new Policy(rs.getString("nroPoliza"), rs.getString("compania"),
						rs.getString("polizaCombinada").equals("si"), rs.getBigDecimal("importe"), value.location()),
				value.productId());
		return values.isEmpty() ? null : values.get(0);
	}

	private LocalDateTime auctionDateTime(Integer auctionId) {
		if (auctionId == null) return null;
		List<LocalDateTime> values = jdbc.query("SELECT fecha,hora FROM subastas WHERE identificador=?",
				(rs, row) -> {
					LocalDate date = rs.getObject("fecha", LocalDate.class);
					LocalTime time = rs.getObject("hora", LocalTime.class);
					return date == null || time == null ? null : LocalDateTime.of(date, time);
				}, auctionId);
		return values.isEmpty() ? null : values.get(0);
	}

	private String insurance(Consignment value, Integer auctionId, String combinedPolicy) {
		if (combinedPolicy == null || combinedPolicy.isBlank()) {
			String number = "QB-" + value.id();
			jdbc.update("INSERT INTO seguros(\"nroPoliza\",compania,\"polizaCombinada\",importe) VALUES (?,'QuickBid Seguros','no',?)",
					number, value.baseValue());
			return number;
		}
		String number = combinedPolicy.trim();
		List<PolicyOwner> policies = jdbc.query("""
				SELECT p.duenio,s.subasta_id FROM productos p JOIN app_solicitudes_consignacion s ON s.producto_id=p.identificador
				WHERE p.seguro=?
				""", (rs, row) -> new PolicyOwner(rs.getInt("duenio"), rs.getInt("subasta_id")), number);
		if (policies.isEmpty() || policies.stream().anyMatch(policy -> !policy.ownerId().equals(value.ownerId())
				|| !policy.auctionId().equals(auctionId))) {
			throw conflict("La póliza combinada exige mismo dueño y subasta", "INVALID_COMBINED_POLICY");
		}
		jdbc.update("UPDATE seguros SET \"polizaCombinada\"='si',importe=importe+? WHERE \"nroPoliza\"=?", value.baseValue(),
				number);
		return number;
	}

	private void copyLegacyPhotos(Long id, Integer productId) {
		List<String> paths = jdbc.query("""
				SELECT a.storage_path FROM app_consignacion_fotos f JOIN app_archivos a ON a.id=f.archivo_id
				WHERE f.solicitud_id=? ORDER BY f.orden
				""", (rs, row) -> rs.getString(1), id);
		for (String path : paths) {
			try (var input = storage.load(path)) {
				jdbc.update("INSERT INTO fotos(producto,foto) VALUES (?,?)", productId, input.readAllBytes());
			} catch (Exception exception) {
				throw new IllegalStateException("No se pudieron copiar las fotos legacy", exception);
			}
		}
	}

	private Long saveImage(Long accountId, String context, MultipartFile file) {
		byte[] bytes = images.validate(file);
		return save(accountId, context, filename(file), file.getContentType(), bytes);
	}

	private Long saveDocument(Long accountId, String context, MultipartFile file) {
		byte[] bytes = documents.validate(file);
		return save(accountId, context, filename(file), file.getContentType(), bytes);
	}

	private Long save(Long accountId, String context, String filename, String contentType, byte[] bytes) {
		var stored = storage.store(filename, contentType, new ByteArrayInputStream(bytes));
		return files.save(new ArchivoApp(accountId, context, filename, contentType, stored.sizeBytes(),
				stored.storagePath(), stored.checksum())).getId();
	}

	private void createGeneratedDocument(Long accountId, Long id, String type, String text) {
		Consignment value = owned(accountId, id, false);
		List<String> lines = new ArrayList<>();
		lines.add("Documento: " + consignmentDocumentLabel(type));
		lines.add("Referencia: CONSIGNACION-" + id);
		lines.add("Emitido: " + OffsetDateTime.now());
		lines.add("Consignante: " + accountDisplayName(accountId));
		lines.add("Bien: " + value.title());
		lines.add("Segmento: " + value.segment());
		lines.add("Estado: " + value.state());
		lines.add(text);
		if (value.baseValue() != null && value.currency() != null)
			lines.add("Valor base: " + value.baseValue() + " " + value.currency());
		if (value.buyerCommissionPct() != null)
			lines.add("Comisión comprador: " + value.buyerCommissionPct() + "%");
		if (value.sellerCommissionPct() != null)
			lines.add("Comisión vendedor: " + value.sellerCommissionPct() + "%");
		if (type.equals("acuerdo_consignacion")) {
			if (value.agreementSentAt() != null) lines.add("Acuerdo propuesto: " + value.agreementSentAt());
			if (value.agreementAcceptedAt() != null) lines.add("Acuerdo aceptado: " + value.agreementAcceptedAt());
			if (value.agreementText() != null) {
				lines.add("Texto completo del acuerdo aceptado:");
				lines.add(value.agreementText());
			}
		}
		if (type.equals("poliza_consignacion")) {
			Policy policy = policy(value);
			if (policy != null) {
				lines.add("Número de póliza: " + policy.numero());
				lines.add("Compañía aseguradora: " + policy.compania());
				lines.add("Tipo: " + (Boolean.TRUE.equals(policy.combinada()) ? "combinada" : "individual"));
				lines.add("Importe asegurado: " + policy.importe() + " " + value.currency());
			}
			if (value.location() != null) lines.add("Ubicación física: " + value.location());
			lines.add("Id de consignación: " + value.id());
			lines.add("Id de producto: " + value.productId());
			lines.add("Id de subasta: " + value.auctionId());
			lines.add("Id de item de catalogo: " + value.itemId());
		}
		List<DocumentReturn> returns = jdbc.query("""
				SELECT modalidad,costo,moneda,estado FROM app_consignacion_devoluciones
				WHERE solicitud_id=? ORDER BY id DESC LIMIT 1
				""", (rs, row) -> new DocumentReturn(rs.getString("modalidad"), rs.getBigDecimal("costo"),
					rs.getString("moneda"), rs.getString("estado")), id);
		if (!returns.isEmpty()) {
			DocumentReturn returnInfo = returns.get(0);
			lines.add("Devolución: " + returnInfo.modality() + " / Estado: " + returnInfo.state());
			lines.add("Costo de devolución: " + returnInfo.cost() + " " + returnInfo.currency());
		}
		List<DocumentLiquidation> liquidations = jdbc.query("""
				SELECT monto_bruto,comision,monto_neto,estado FROM app_liquidaciones_consignacion
				WHERE solicitud_id=?
				""", (rs, row) -> new DocumentLiquidation(rs.getBigDecimal("monto_bruto"),
					rs.getBigDecimal("comision"), rs.getBigDecimal("monto_neto"), rs.getString("estado")), id);
		if (!liquidations.isEmpty()) {
			DocumentLiquidation liquidation = liquidations.get(0);
			String currency = value.currency() == null ? "" : " " + value.currency();
			lines.add("Monto bruto: " + liquidation.gross() + currency);
			lines.add("Comisión: " + liquidation.commission() + currency);
			lines.add("Monto neto: " + liquidation.net() + currency);
			lines.add("Estado de liquidación: " + liquidation.state());
		}
		lines.add("Validez: documento digital emitido por QuickBid.");
		byte[] bytes = pdfs.generate("QuickBid", lines);
		String filename = type + "-" + id + ".pdf";
		String storagePath = "db-generated/" + UUID.randomUUID() + ".pdf";
		String checksum = HexFormat.of().formatHex(digest(bytes));
		Long fileId = files.save(new ArchivoApp(accountId, "documento_consignacion", filename, "application/pdf",
				(long) bytes.length, storagePath, checksum)).getId();
		jdbc.update("UPDATE app_archivos SET content_bytes=? WHERE id=?", bytes, fileId);
		jdbc.update("INSERT INTO app_documentos(tipo,referencia_tipo,referencia_id,archivo_id,estado) VALUES (?,'consignacion',?,?,'disponible')",
				type, id, fileId);
	}

	private void createReturn(Long id, String reason) {
		if (count("SELECT COUNT(*) FROM app_consignacion_devoluciones WHERE solicitud_id=?", id) == 0) {
			jdbc.update("""
					INSERT INTO app_consignacion_devoluciones(solicitud_id,motivo,costo,moneda,estado)
					VALUES (?,?,0,'ARS','pendiente_decision')
					""", id, reason);
		}
	}

	private ReturnRow returnRow(Long id, boolean lock) {
		List<ReturnRow> values = jdbc.query("""
				SELECT id,modalidad,costo,moneda,estado,pago_id,direccion_envio_id,direccion,piso,codigo_postal,
					localidad,provincia FROM app_consignacion_devoluciones WHERE solicitud_id=?
				""" + (lock ? " FOR UPDATE" : ""), (rs, row) -> new ReturnRow(rs.getLong("id"),
						rs.getString("modalidad"), rs.getBigDecimal("costo"), rs.getString("moneda"), rs.getString("estado"),
						(Long) rs.getObject("pago_id"), (Long) rs.getObject("direccion_envio_id"),
						rs.getString("direccion"), rs.getString("piso"), rs.getString("codigo_postal"),
						rs.getString("localidad"), rs.getString("provincia")), id);
		return values.isEmpty() ? null : values.get(0);
	}

	private ConsignmentDtos.Return returnDto(ReturnRow value) {
		return value == null ? null : new ConsignmentDtos.Return(value.id(), value.modality(), value.cost(),
				value.currency(), value.state(), value.paymentId(), value.addressId(), addressSummary(value));
	}

	private String consignmentDocumentLabel(String type) {
		return switch (type) {
			case "acuerdo_consignacion" -> "Acuerdo de consignación";
			case "poliza_consignacion" -> "Póliza de consignación";
			case "comprobante_envio_devolucion" -> "Comprobante de envío de devolución";
			case "liquidacion_venta" -> "Liquidación de venta";
			default -> type.replace('_', ' ');
		};
	}

	private String accountDisplayName(Long accountId) {
		List<String> names = jdbc.query("""
				SELECT p.nombre FROM app_cuentas c
				JOIN personas p ON p.identificador=c.persona_id
				WHERE c.id=?
				""", (rs, row) -> rs.getString(1), accountId);
		return names.isEmpty() ? "Cuenta " + accountId : names.get(0);
	}

	private byte[] digest(byte[] content) {
		try {
			return MessageDigest.getInstance("SHA-256").digest(content);
		} catch (java.security.NoSuchAlgorithmException exception) {
			throw new IllegalStateException("SHA-256 no disponible", exception);
		}
	}

	private String addressSummary(ReturnRow value) {
		if (value.address() == null) return null;
		return value.address() + (value.floor() == null ? "" : ", " + value.floor()) + ", "
				+ value.locality() + ", " + value.province() + " (" + value.postalCode() + ")";
	}

	private Liquidation liquidationOrNull(Long id) {
		List<Liquidation> values = jdbc.query("""
				SELECT id,compra_id,monto_bruto,comision,monto_neto,cuenta_destino,estado,paid_at
				FROM app_liquidaciones_consignacion WHERE solicitud_id=?
				""", (rs, row) -> new Liquidation(rs.getLong("id"), rs.getLong("compra_id"),
						rs.getBigDecimal("monto_bruto"), rs.getBigDecimal("comision"), rs.getBigDecimal("monto_neto"),
						rs.getString("cuenta_destino"), rs.getString("estado"),
						rs.getObject("paid_at", OffsetDateTime.class)), id);
		return values.isEmpty() ? null : values.get(0);
	}

	private Liquidation liquidation(Long id) {
		return liquidationOrNull(id);
	}

	private ReturnPayment replayReturnPayment(Long accountId, Long returnId, Long methodId, String key) {
		List<ReturnPayment> values = jdbc.query("""
				SELECT p.id,p.consignacion_devolucion_id,p.medio_pago_id,p.monto,p.moneda,p.estado,s.cuenta_id
				FROM app_pagos p JOIN app_consignacion_devoluciones d ON d.id=p.consignacion_devolucion_id
				JOIN app_solicitudes_consignacion s ON s.id=d.solicitud_id WHERE p.idempotency_key=?
				""", (rs, row) -> {
			if (rs.getLong("cuenta_id") != accountId || rs.getLong("consignacion_devolucion_id") != returnId
					|| rs.getLong("medio_pago_id") != methodId) {
				throw conflict("Clave de idempotencia reutilizada", "IDEMPOTENCY_CONFLICT");
			}
			return new ReturnPayment(rs.getLong("id"), rs.getLong("consignacion_devolucion_id"),
					rs.getLong("medio_pago_id"), rs.getBigDecimal("monto"), rs.getString("moneda"),
					rs.getString("estado"), true);
		}, key);
		return values.isEmpty() ? null : values.get(0);
	}

	private PaymentMethod paymentMethod(Long accountId, Long id) {
		List<PaymentMethod> values = jdbc.query("""
				SELECT cuenta_id,tipo,moneda,estado,limite_monto,consumo_actual,verificado_hasta,deleted_at
				FROM app_medios_pago WHERE id=?
				""", (rs, row) -> new PaymentMethod(rs.getLong("cuenta_id"), rs.getString("tipo"), rs.getString("moneda"),
						rs.getString("estado"), rs.getBigDecimal("limite_monto"), rs.getBigDecimal("consumo_actual"),
						rs.getObject("verificado_hasta", OffsetDateTime.class),
						rs.getObject("deleted_at", OffsetDateTime.class)), id);
		if (values.isEmpty()) throw notFound("Medio de pago inexistente");
		PaymentMethod value = values.get(0);
		if (!value.accountId().equals(accountId)) throw forbidden("El medio no pertenece al usuario", "RESOURCE_NOT_OWNED");
		return value;
	}

	private void validatePayment(PaymentMethod value, String currency, BigDecimal amount) {
		if (!value.currency().equals(currency)) throw unprocessable("Moneda incompatible", "PAYMENT_METHOD_CURRENCY_MISMATCH");
		if (!value.state().equals("verificado") || value.deletedAt() != null) {
			throw forbidden("El medio no está verificado", "PAYMENT_METHOD_NOT_VERIFIED");
		}
		if (value.verifiedUntil() == null || !value.verifiedUntil().isAfter(OffsetDateTime.now())) {
			throw forbidden("La verificación del medio venció", "PAYMENT_METHOD_VERIFICATION_EXPIRED");
		}
		if (value.limit() == null || amount.compareTo(value.limit().subtract(value.consumed())) > 0) {
			throw unprocessable("Fondos o límite insuficientes", "INSUFFICIENT_FUNDS_OR_LIMIT");
		}
	}

	private void validateLiquidationDestination(PaymentMethod value) {
		if (value.deletedAt() != null || value.state().equals("eliminado")) {
			throw forbidden("La cuenta bancaria destino fue eliminada", "BANK_ACCOUNT_NOT_AVAILABLE");
		}
	}

	private Purchase purchase(Integer itemId) {
		List<Purchase> values = jdbc.query("SELECT id,monto_adjudicacion FROM app_compras WHERE item_catalogo_id=?",
				(rs, row) -> new Purchase(rs.getLong("id"), rs.getBigDecimal("monto_adjudicacion")), itemId);
		if (values.isEmpty()) throw conflict("Todavia no existe compra para liquidar", "PURCHASE_NOT_AVAILABLE");
		return values.get(0);
	}

	private Auction auction(Integer id) {
		List<Auction> values = jdbc.query("""
				SELECT e.moneda,s.ubicacion FROM app_subasta_ext e JOIN subastas s ON s.identificador=e.subasta_id
				WHERE e.subasta_id=?
				""", (rs, row) -> new Auction(rs.getString("moneda"), rs.getString("ubicacion")), id);
		if (values.isEmpty()) throw notFound("Subasta inexistente");
		return values.get(0);
	}

	private void rejectInitial(Long id, Integer employeeId, String reason) {
		required(reason, "motivo");
		Consignment value = lock(id);
		jdbc.update("""
				UPDATE app_solicitudes_consignacion SET estado='rechazo_inicial',motivo_rechazo=?,
					revisor_empleado_id=?,updated_at=CURRENT_TIMESTAMP WHERE id=?
				""", reason.trim(), employeeId, id);
		notify(value.accountId(), "consignacion_rechazada", "Consignación rechazada",
				"La revisión inicial fue rechazada. Revisá el motivo informado.", id);
	}

	private CuentaApp accountAllowed(Long id) {
		CuentaApp value = account(id);
		if (!Set.of("activa", "restriccion_multa").contains(value.getEstado())) {
			throw forbidden("La cuenta no puede consignar", "ACCOUNT_BLOCKED");
		}
		return value;
	}

	private CuentaApp account(Long id) {
		return accounts.findById(id).orElseThrow(() -> notFound("Cuenta inexistente"));
	}

	private void notify(Long accountId, String type, String title, String description, Long referenceId) {
		jdbc.update("""
				INSERT INTO app_notificaciones(cuenta_id,tipo,titulo,descripcion,referencia_tipo,referencia_id)
				VALUES (?,?,?,?,'consignacion',?)
				""", accountId, type, title, description, referenceId);
		mail.critical(accountId, type);
	}

	private void audit(Long accountId, String action, Long id) {
		audit.record(new AuditEvent("usuario", accountId, action, "consignacion", id, "{}"));
	}

	private void auditAdmin(Integer employeeId, String action, Long id) {
		audit.record(new AuditEvent("admin", employeeId.longValue(), action, "consignacion", id, "{}"));
	}

	private void updateState(Long id, String state) {
		jdbc.update("UPDATE app_solicitudes_consignacion SET estado=?,updated_at=CURRENT_TIMESTAMP WHERE id=?", state, id);
	}

	private long insert(String sql, SqlBinder binder) {
		return insert(sql, "id", binder);
	}

	private long insert(String sql, String generatedColumn, SqlBinder binder) {
		GeneratedKeyHolder keys = new GeneratedKeyHolder();
		jdbc.update(connection -> {
			PreparedStatement statement = connection.prepareStatement(sql, new String[] { generatedColumn });
			binder.bind(statement);
			return statement;
		}, keys);
		return keys.getKey().longValue();
	}

	private int count(String sql, Object... args) {
		return jdbc.queryForObject(sql, Integer.class, args);
	}

	private SegmentAndCategory classifyPublicCreation(String segment) {
		required(segment, "segmento");
		String normalizedSegment = segment.trim().toLowerCase();
		return new SegmentAndCategory(normalizedSegment, "comun");
	}

	private String currency(String value) {
		if (value == null || !Set.of("ARS", "USD").contains(value.trim().toUpperCase())) {
			throw bad("Moneda inválida", "INVALID_CURRENCY");
		}
		return value.trim().toUpperCase();
	}

	private BigDecimal commissionPct(BigDecimal value) {
		BigDecimal result = value == null ? DEFAULT_COMMISSION : value;
		if (result.compareTo(BigDecimal.ONE) < 0 || result.compareTo(new BigDecimal("99")) > 0) {
			throw bad("Comision fuera de rango", "INVALID_COMMISSION");
		}
		return result;
	}

	private String filterPredicate(String filter) {
		if (filter == null || filter.isBlank() || filter.equalsIgnoreCase("activas")) {
			return "AND estado NOT IN ('rechazo_inicial','rechazo_revision_fisica','acuerdo_rechazado',"
					+ "'devolucion_pendiente','devolucion_incompleta','vendida','comprada_por_empresa','liquidada')";
		}
		return switch (filter.toLowerCase()) {
			case "rechazadas" -> "AND estado IN ('rechazo_inicial','rechazo_revision_fisica','acuerdo_rechazado','devolucion_pendiente','devolucion_incompleta')";
			case "vendidas" -> "AND estado IN ('vendida','comprada_por_empresa','liquidada')";
			case "todas" -> "";
			default -> throw bad("Filtro invalido", "INVALID_FILTER");
		};
	}

	private String action(String state) {
		return switch (state) {
			case "documentacion_adicional" -> "adjuntar_documentacion";
			case "acuerdo_pendiente" -> "revisar_acuerdo";
			case "devolucion_pendiente" -> "gestionar_devolucion";
			default -> null;
		};
	}

	private String custodyLocation(String auctionLocation) {
		String location = blankToNull(auctionLocation);
		if (location != null) {
			location = blankToNull(location.replaceAll("(?i)\\bdemo\\b", "").replaceAll("\\s+", " "));
		}
		return location == null
				? "Depósito de custodia QuickBid, Buenos Aires"
				: "Depósito de custodia QuickBid - " + location;
	}

	private String join(String first, String second) {
		if (first == null || first.isBlank()) return blankToNull(second);
		if (second == null || second.isBlank()) return first.trim();
		return first.trim() + "\n" + second.trim();
	}

	private String filename(MultipartFile file) {
		String original = file.getOriginalFilename();
		if (original == null || original.isBlank()) return "archivo";
		String normalized = original.replace('\\', '/');
		String name = normalized.substring(normalized.lastIndexOf('/') + 1).replaceAll("\\p{Cntrl}", "").trim();
		return name.isBlank() ? "archivo" : name;
	}

	private String blankToNull(String value) {
		return value == null || value.isBlank() ? null : value.trim();
	}

	private String normalizeIdempotencyKey(String value) {
		String normalized = blankToNull(value);
		if (normalized == null) return null;
		if (!normalized.matches("[A-Za-z0-9._:-]{8,100}")) {
			throw bad("La clave de idempotencia no es valida", "INVALID_IDEMPOTENCY_KEY");
		}
		return normalized;
	}

	private Long findConsignmentByIdempotencyKey(Long accountId, String key) {
		if (key == null) return null;
		List<Long> values = jdbc.query("""
				SELECT id FROM app_solicitudes_consignacion
				WHERE cuenta_id=? AND idempotency_key=?
				""", (rs, row) -> rs.getLong(1), accountId, key);
		return values.isEmpty() ? null : values.get(0);
	}

	private void required(String value, String field) {
		if (value == null || value.isBlank()) throw bad("Falta " + field, "INVALID_FIELD");
	}

	private String truncate(String value, int max) {
		return value.length() <= max ? value : value.substring(0, max);
	}

	private String normalizedKey(String key) {
		return key == null || key.isBlank() ? UUID.randomUUID().toString() : key.trim();
	}

	private String json(String value) {
		return value == null ? "" : value.replace("\\", "\\\\").replace("\"", "\\\"");
	}

	private void checkPage(int page, int size) {
		if (page < 0 || size < 1 || size > 100) throw bad("Paginación inválida", "INVALID_PAGE");
	}

	private BusinessException invalidState() {
		return conflict("Transicion de estado invalida", "INVALID_STATE_TRANSITION");
	}

	private BusinessException bad(String message, String code) {
		return new BusinessException(HttpStatus.BAD_REQUEST, message, code);
	}

	private BusinessException forbidden(String message, String code) {
		return new BusinessException(HttpStatus.FORBIDDEN, message, code);
	}

	private BusinessException conflict(String message, String code) {
		return new BusinessException(HttpStatus.CONFLICT, message, code);
	}

	private BusinessException unprocessable(String message, String code) {
		return new BusinessException(HttpStatus.UNPROCESSABLE_ENTITY, message, code);
	}

	private BusinessException notFound(String message) {
		return new BusinessException(HttpStatus.NOT_FOUND, message, "RESOURCE_NOT_FOUND");
	}

	@FunctionalInterface
	private interface SqlBinder {
		void bind(PreparedStatement statement) throws java.sql.SQLException;
	}

	private record Consignment(Long id, Long accountId, Integer clientId, Integer ownerId, Integer productId,
			Integer itemId, Integer auctionId, String title, String description, String segment, String category,
			String history, String artist, String objectDate, String state, Boolean requiresDocuments,
			String rejectionReason, Integer reviewerId, BigDecimal baseValue, String currency,
			BigDecimal buyerCommissionPct, BigDecimal sellerCommissionPct, String agreementText, String location,
			OffsetDateTime agreementSentAt, OffsetDateTime agreementAcceptedAt,
			OffsetDateTime createdAt, OffsetDateTime updatedAt) {
	}

	private record SegmentAndCategory(String segment, String category) {
	}

	private record StoredFile(String filename, String contentType, Long sizeBytes, String storagePath,
			byte[] contentBytes) {
	}

	private record DocumentReturn(String modality, BigDecimal cost, String currency, String state) {
	}

	private record DocumentLiquidation(BigDecimal gross, BigDecimal commission, BigDecimal net, String state) {
	}

	private record ReturnRow(Long id, String modality, BigDecimal cost, String currency, String state, Long paymentId,
			Long addressId, String address, String floor, String postalCode, String locality, String province) {
	}

	private record PaymentMethod(Long accountId, String type, String currency, String state, BigDecimal limit,
			BigDecimal consumed, OffsetDateTime verifiedUntil, OffsetDateTime deletedAt) {
	}

	private record PolicyOwner(Integer ownerId, Integer auctionId) {
	}

	private record Auction(String currency, String location) {
	}

	private record Purchase(Long id, BigDecimal amount) {
	}
}
