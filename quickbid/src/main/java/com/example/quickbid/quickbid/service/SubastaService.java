package com.example.quickbid.quickbid.service;

import java.math.BigDecimal;
import java.sql.Date;
import java.sql.Time;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;

import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.example.quickbid.quickbid.audit.AuditEvent;
import com.example.quickbid.quickbid.audit.AuditService;
import com.example.quickbid.quickbid.dto.response.SubastaDtos.AuthenticatedCatalog;
import com.example.quickbid.quickbid.dto.response.SubastaDtos.AuthenticatedDetail;
import com.example.quickbid.quickbid.dto.response.SubastaDtos.AuthenticatedItem;
import com.example.quickbid.quickbid.dto.response.SubastaDtos.AuthenticatedSummary;
import com.example.quickbid.quickbid.dto.response.SubastaDtos.CurrentBid;
import com.example.quickbid.quickbid.dto.response.SubastaDtos.BidHistory;
import com.example.quickbid.quickbid.dto.response.SubastaDtos.Page;
import com.example.quickbid.quickbid.dto.response.SubastaDtos.PaymentOption;
import com.example.quickbid.quickbid.dto.response.SubastaDtos.PublicCatalog;
import com.example.quickbid.quickbid.dto.response.SubastaDtos.PublicDetail;
import com.example.quickbid.quickbid.dto.response.SubastaDtos.PublicItem;
import com.example.quickbid.quickbid.dto.response.SubastaDtos.PublicSummary;
import com.example.quickbid.quickbid.dto.response.SubastaDtos.Registration;
import com.example.quickbid.quickbid.dto.response.SubastaDtos.Rematador;
import com.example.quickbid.quickbid.dto.response.SubastaDtos.Verification;
import com.example.quickbid.quickbid.entity.app.CuentaApp;
import com.example.quickbid.quickbid.entity.app.InscripcionSubasta;
import com.example.quickbid.quickbid.entity.app.MedioPago;
import com.example.quickbid.quickbid.entity.app.NotificacionApp;
import com.example.quickbid.quickbid.exception.BusinessException;
import com.example.quickbid.quickbid.repository.app.AuctionQueryRepository;
import com.example.quickbid.quickbid.repository.app.CuentaAppRepository;
import com.example.quickbid.quickbid.repository.app.InscripcionSubastaRepository;
import com.example.quickbid.quickbid.repository.app.MedioPagoRepository;
import com.example.quickbid.quickbid.repository.app.NotificacionAppRepository;

@Service
public class SubastaService {
	private static final Set<String> ENROLLMENT_PAYMENT_STATES = Set.of("pendiente_verificacion", "verificado", "vencido");
	private static final Set<String> ACTIVE_ENROLLMENT_STATES = Set.of("pendiente_validacion", "aprobada");
	private static final Set<String> AUCTION_STATES = Set.of("programada", "abierta", "en_vivo", "cerrada", "finalizada");
	private static final Set<String> AUCTION_CATEGORIES = Set.of("comun", "especial", "plata", "oro", "platino");
	private static final Set<String> ONE_UNIT_INCREMENT_CATEGORIES = Set.of("oro", "platino");
	private static final Set<String> CURRENCIES = Set.of("ARS", "USD");
	private static final String AUCTION_SELECT = """
			SELECT s.identificador, e.titulo, e.descripcion, s.fecha, s.hora, s.ubicacion,
			       s.categoria, e.moneda, e.segmento, e.estado_operativo, e.permite_inscripcion_online,
			       p.nombre rematador_nombre, sub.matricula rematador_matricula, sub.region rematador_region,
			       (
			           SELECT '/api/items/fotos/' || f.identificador
			           FROM catalogos c2
			           JOIN "itemsCatalogo" i2 ON i2.catalogo=c2.identificador
			           JOIN fotos f ON f.producto=i2.producto
			           WHERE c2.subasta=s.identificador
			           ORDER BY i2.identificador, f.identificador
			           LIMIT 1
			       ) imagen_principal_url
			FROM subastas s JOIN app_subasta_ext e ON e.subasta_id=s.identificador
			LEFT JOIN subastadores sub ON sub.identificador=s.subastador
			LEFT JOIN personas p ON p.identificador=sub.identificador
			""";

	private final JdbcTemplate jdbc;
	private final CuentaAppRepository cuentas;
	private final MedioPagoRepository medios;
	private final InscripcionSubastaRepository inscripciones;
	private final NotificacionAppRepository notificaciones;
	private final AuctionQueryRepository auctionQueries;
	private final AuditService audit;

	public SubastaService(JdbcTemplate jdbc, CuentaAppRepository cuentas, MedioPagoRepository medios,
			InscripcionSubastaRepository inscripciones, NotificacionAppRepository notificaciones,
			AuctionQueryRepository auctionQueries, AuditService audit) {
		this.jdbc = jdbc;
		this.cuentas = cuentas;
		this.medios = medios;
		this.inscripciones = inscripciones;
		this.notificaciones = notificaciones;
		this.auctionQueries = auctionQueries;
		this.audit = audit;
	}

	@Transactional(readOnly = true)
	public Page<?> list(String estado, String categoria, String moneda, LocalDate fechaDesde, LocalDate fechaHasta,
			String q, int page, int size, boolean authenticated) {
		checkPage(page, size);
		String normalizedState = optionalLower(estado);
		String normalizedCategory = optionalLower(categoria);
		String normalizedCurrency = optionalUpper(moneda);
		checkFilter("estado", normalizedState, AUCTION_STATES);
		checkFilter("categoria", normalizedCategory, AUCTION_CATEGORIES);
		checkFilter("moneda", normalizedCurrency, CURRENCIES);
		if (fechaDesde != null && fechaHasta != null && fechaDesde.isAfter(fechaHasta)) {
			throw unprocessable("Rango de fechas invalido", "INVALID_FILTER");
		}
		StringBuilder where = new StringBuilder(" WHERE 1=1");
		List<Object> args = new ArrayList<>();
		filter(where, args, "e.estado_operativo", normalizedState);
		filter(where, args, "s.categoria", normalizedCategory);
		filter(where, args, "e.moneda", normalizedCurrency);
		if (fechaDesde != null) { where.append(" AND s.fecha>=?"); args.add(fechaDesde); }
		if (fechaHasta != null) { where.append(" AND s.fecha<=?"); args.add(fechaHasta); }
		if (q != null && !q.isBlank()) {
			where.append(" AND LOWER(e.titulo) LIKE ?");
			args.add("%" + q.toLowerCase(Locale.ROOT) + "%");
		}
		long total = jdbc.queryForObject(
				"SELECT COUNT(*) FROM subastas s JOIN app_subasta_ext e ON e.subasta_id=s.identificador" + where,
				Long.class,
				args.toArray());
		args.add(size); args.add(page * size);
		List<?> content = jdbc.query(AUCTION_SELECT + where + " ORDER BY s.fecha,s.hora LIMIT ? OFFSET ?",
				(rs, row) -> summary(auction(rs), authenticated), args.toArray());
		return new Page<>(content, page, size, total, (int) Math.ceil((double) total / size));
	}

	@Transactional(readOnly = true)
	public Object detail(Integer id, boolean authenticated) {
		return detail(auction(id), authenticated);
	}

	@Transactional(readOnly = true)
	public Object catalog(Integer id, boolean authenticated) {
		auction(id);
		return jdbc.query("""
				SELECT c.identificador,c.descripcion FROM catalogos c WHERE c.subasta=?
				""", rs -> {
			if (!rs.next()) throw notFound("Catalogo inexistente");
			int catalogId = rs.getInt("identificador");
			return catalog(id, catalogId, rs.getString("descripcion"), authenticated);
		}, id);
	}

	@Transactional(readOnly = true)
	public Object item(Integer id, boolean authenticated) {
		return jdbc.query("""
				SELECT i.identificador,i.producto,p."descripcionCatalogo",i."precioBase",i.comision
				FROM "itemsCatalogo" i JOIN productos p ON p.identificador=i.producto
				WHERE i.identificador=?
				""", rs -> {
			if (!rs.next()) throw notFound("Item inexistente");
			return item(rs.getInt("identificador"), rs.getInt("producto"), rs.getString("descripcionCatalogo"),
					rs.getBigDecimal("precioBase"), rs.getBigDecimal("comision"), authenticated, null, "sin_estado",
					false, false, null, null, null);
		}, id);
	}

	@Transactional(readOnly = true)
	public Verification verification(Long cuentaId, Integer subastaId) {
		CuentaApp cuenta = account(cuentaId);
		Auction auction = auction(subastaId);
		List<MedioPago> own = medios.findAllByCuentaIdAndDeletedAtIsNullOrderByCreatedAtDesc(cuentaId);
		List<MedioPago> compatibleEnrollment = own.stream().filter(m -> enrollmentCompatible(m, auction.moneda())).toList();
		List<MedioPago> validBid = own.stream().filter(m -> bidCompatible(m, auction.moneda())).toList();
		List<MedioPago> revalidables = compatibleEnrollment.stream().filter(this::requiresReview).toList();
		boolean restricted = cuenta.getEstado().equals("restriccion_multa");
		boolean blocked = !Set.of("activa", "restriccion_multa").contains(cuenta.getEstado());
		boolean active = cuenta.getEstado().equals("activa");
		boolean categoryOk = categoryOrder(cuenta.getCategoriaCalculada()) >= categoryOrder(auction.categoria());
		boolean started = started(auction);
		boolean enrollmentClosed = !enrollmentOpen(auction);
		Integer activeItem = activeItem(subastaId);
		boolean noActiveItem = activeItem == null;
		boolean otherParticipation = participatingInOtherAuction(cuentaId, subastaId);
		boolean enrolled = inscripciones.findFirstBySubastaIdAndCuentaIdAndEstadoInOrderByCreatedAtDesc(
				subastaId, cuentaId, ACTIVE_ENROLLMENT_STATES).isPresent();
		boolean canEnroll = active && categoryOk && !enrollmentClosed && !compatibleEnrollment.isEmpty() && !enrolled;
		boolean canBid = active && categoryOk && auction.estadoOperativo().equals("en_vivo") && !noActiveItem
				&& !validBid.isEmpty() && !otherParticipation && bidWindowOpen(subastaId);
		boolean hasEnrollmentState = own.stream().anyMatch(this::enrollmentState);
		boolean hasVerified = own.stream().anyMatch(m -> m.getEstado().equals("verificado"));
		return new Verification(true, canEnroll, canBid, false,
				compatibleEnrollment.isEmpty(), validBid.isEmpty(), validBid.isEmpty() && !revalidables.isEmpty(),
				!categoryOk, !categoryOk, compatibleEnrollment.isEmpty() && hasEnrollmentState,
				validBid.isEmpty() && hasVerified, restricted, blocked, enrolled, enrollmentClosed,
				started, !auction.estadoOperativo().equals("en_vivo"), noActiveItem, otherParticipation,
				options(compatibleEnrollment), options(validBid), options(revalidables));
	}

	@Transactional
	public Registration enroll(Long cuentaId, Integer subastaId, Long medioPagoId) {
		CuentaApp cuenta = account(cuentaId);
		Auction auction = auction(subastaId);
		if (!cuenta.getEstado().equals("activa")) {
			throw forbidden("La cuenta no puede inscribirse", "ACCOUNT_RESTRICTED_BY_FINE");
		}
		if (categoryOrder(cuenta.getCategoriaCalculada()) < categoryOrder(auction.categoria())) {
			throw forbidden("Categoría insuficiente", "AUCTION_CATEGORY_FORBIDDEN");
		}
		if (!enrollmentOpen(auction)) {
			throw conflict("La inscripción ya cerró", "AUCTION_ENROLLMENT_CLOSED");
		}
		var existing = inscripciones.findFirstBySubastaIdAndCuentaIdAndEstadoInOrderByCreatedAtDesc(
				subastaId, cuentaId, ACTIVE_ENROLLMENT_STATES);
		if (existing.isPresent()) return registration(existing.get(), true);
		MedioPago medio = selectEnrollmentPayment(cuentaId, auction.moneda(), medioPagoId);
		boolean review = requiresReview(medio);
		InscripcionSubasta enrollment = inscripciones.save(new InscripcionSubasta(subastaId, cuentaId, medio.getId(), review));
		notificaciones.save(new NotificacionApp(cuentaId, "inscripcion_subasta", "Inscripción registrada",
				review ? "Tu inscripción quedó pendiente de revisión del medio de pago." : "Tu inscripción fue aprobada.",
				"subasta", subastaId.longValue()));
		audit.record(new AuditEvent("usuario", cuentaId, "subasta.inscripcion_creada", "subasta", subastaId.longValue(),
				"{\"medioPagoId\":" + medio.getId() + "}"));
		return registration(enrollment, false);
	}

	@Transactional(readOnly = true)
	public CurrentBid currentBid(Long cuentaId, Integer subastaId) {
		Verification verification = verification(cuentaId, subastaId);
		if (verification.cuentaBloqueada()) throw forbidden("La cuenta no puede ver informacion live", "ACCOUNT_BLOCKED");
		Auction auction = auction(subastaId);
		return jdbc.query("""
				SELECT v.item_catalogo_activo_id,v.version,v.retencion_hasta,v.lote_finaliza_estimado_at,
				       v.proximo_lote_programado_at,v.subasta_finaliza_programado_at,i."precioBase",
				       (SELECT MAX(p.monto) FROM app_pujas_live p
				        WHERE p.subasta_id=v.subasta_id AND p.item_catalogo_id=v.item_catalogo_activo_id
				          AND p.estado IN ('aceptada','ganadora')) mejor_oferta,
				       (SELECT p.cuenta_id FROM app_pujas_live p
				        WHERE p.subasta_id=v.subasta_id AND p.item_catalogo_id=v.item_catalogo_activo_id
				          AND p.estado IN ('aceptada','ganadora')
				        ORDER BY p.monto DESC,p.secuencia DESC LIMIT 1) mejor_postor_id
				FROM app_subasta_estado_vivo v
				LEFT JOIN "itemsCatalogo" i ON i.identificador=v.item_catalogo_activo_id
				WHERE v.subasta_id=?
				""", rs -> {
			if (!rs.next()) throw notFound("Estado vivo inexistente");
			OffsetDateTime now = OffsetDateTime.now();
			OffsetDateTime retentionUntil = rs.getObject("retencion_hasta", OffsetDateTime.class);
			OffsetDateTime lotDeadline = rs.getObject("lote_finaliza_estimado_at", OffsetDateTime.class);
			OffsetDateTime nextLotAt = rs.getObject("proximo_lote_programado_at", OffsetDateTime.class);
			OffsetDateTime closeAuctionAt = rs.getObject("subasta_finaliza_programado_at", OffsetDateTime.class);
			Long bestAccountId = (Long) rs.getObject("mejor_postor_id");
			Integer activeItem = (Integer) rs.getObject("item_catalogo_activo_id");
			BigDecimal basePrice = rs.getBigDecimal("precioBase");
			BigDecimal bestOffer = rs.getBigDecimal("mejor_oferta");
			if (activeItem == null) {
				boolean finished = auction.estadoOperativo().equals("finalizada");
				OffsetDateTime deadline = nextLotAt != null ? nextLotAt : closeAuctionAt;
				String timerType = nextLotAt != null ? "esperando_proximo_lote"
						: closeAuctionAt != null ? "cerrando_subasta" : null;
				String state = finished ? "finalizada" : timerType != null ? timerType : "cerrado";
				String message = nextLotAt != null
						? "Preparando próximo lote."
						: closeAuctionAt != null ? "Subasta en cierre." : finished ? "La subasta finalizó." : null;
				NextLot nextLot = nextLot(subastaId);
				return new CurrentBid(subastaId, null, bestOffer, auction.moneda(), rs.getLong("version"),
						verification.puedePujar(), verification.puedePujar() ? null
								: "El usuario puede ver live pero no cumple las condiciones para pujar",
						basePrice, minimumIncrement(auction.categoria(), basePrice), now, null,
						remainingSeconds(now, deadline), bestAccountId != null && bestAccountId.equals(cuentaId),
						state, true, finished ? "volver" : "esperar_siguiente_lote", false, deadline != null,
						message, deadline, timerType, nextLotAt, closeAuctionAt,
						nextLot == null ? null : nextLot.itemId(), nextLot == null ? null : nextLot.order(), List.of());
			}
			OffsetDateTime deadline = retentionUntil != null ? retentionUntil : lotDeadline;
			Long remaining = remainingSeconds(now, deadline);
			boolean waitingFirstBid = bestOffer == null && retentionUntil == null && lotDeadline == null;
			String timerType = retentionUntil != null ? "retencion_ganadora"
					: lotDeadline != null ? "sin_pujas_empresa"
					: waitingFirstBid ? "esperando_primera_puja" : null;
			boolean finished = auction.estadoOperativo().equals("finalizada");
			boolean expired = deadline != null && !deadline.isAfter(now);
			String state = finished ? "finalizada" : expired ? "esperando_resolucion" : "activo";
			String message = waitingFirstBid
					? "Esperando primera puja. El timer inicia con la primera oferta."
					: finished ? "La subasta finalizó." : expired ? "El tiempo para pujar venció. El lote espera resolución." : null;
			return new CurrentBid(subastaId, activeItem,
					bestOffer, auction.moneda(), rs.getLong("version"), verification.puedePujar() && !expired,
					expired ? message : verification.puedePujar() ? null : "El usuario puede ver live pero no cumple las condiciones para pujar",
					basePrice, minimumIncrement(auction.categoria(), basePrice), now,
					retentionUntil, remaining, bestAccountId != null && bestAccountId.equals(cuentaId),
					state, false, expired ? "esperar_resolucion" : "pujar", waitingFirstBid, deadline != null, message, deadline,
					timerType, nextLotAt, closeAuctionAt, null, null, recentBids(subastaId, activeItem));
		}, subastaId);
	}

	private boolean bidWindowOpen(Integer auctionId) {
		List<OffsetDateTime> deadlines = jdbc.query("""
				SELECT COALESCE(retencion_hasta,lote_finaliza_estimado_at) deadline
				FROM app_subasta_estado_vivo WHERE subasta_id=?
				""", (rs, row) -> rs.getObject("deadline", OffsetDateTime.class), auctionId);
		return deadlines.size() == 1 && (deadlines.get(0) == null || deadlines.get(0).isAfter(OffsetDateTime.now()));
	}

	private List<BidHistory> recentBids(Integer auctionId, Integer itemId) {
		return jdbc.query("""
				SELECT p.id,p.monto,p.version_estado,p.estado,
				 (SELECT a."numeroPostor" FROM asistentes a JOIN app_cuentas c ON c.cliente_id=a.cliente
				  WHERE c.id=p.cuenta_id AND a.subasta=p.subasta_id ORDER BY a.identificador LIMIT 1) numero_postor
				FROM app_pujas_live p WHERE p.subasta_id=? AND p.item_catalogo_id=?
				 AND p.estado IN ('aceptada','superada','ganadora')
				ORDER BY p.secuencia DESC,p.id DESC LIMIT 10
				""", (rs, row) -> {
			Integer number = (Integer) rs.getObject("numero_postor");
			return new BidHistory(rs.getLong("id"),rs.getBigDecimal("monto"),rs.getLong("version_estado"),
					number,number == null ? "Postor" : "Postor #" + number,rs.getString("estado"));
		}, auctionId, itemId);
	}

	private BigDecimal minimumIncrement(String category, BigDecimal basePrice) {
		if (basePrice == null) return null;
		return ONE_UNIT_INCREMENT_CATEGORIES.contains(category)
				? BigDecimal.ONE
				: basePrice.multiply(new BigDecimal("0.01"));
	}

	private List<?> items(Integer catalogId, boolean authenticated) {
		return jdbc.query("""
				SELECT i.identificador,i.producto,p."descripcionCatalogo",i."precioBase",i.comision,
				       ROW_NUMBER() OVER (ORDER BY i.identificador) orden_lote,
				       i.subastado,v.item_catalogo_activo_id,co.id compra_id,co.comprador_empresa
				FROM "itemsCatalogo" i
				JOIN productos p ON p.identificador=i.producto
				JOIN catalogos c ON c.identificador=i.catalogo
				LEFT JOIN app_subasta_estado_vivo v ON v.subasta_id=c.subasta
				LEFT JOIN app_compras co ON co.item_catalogo_id=i.identificador
				WHERE i.catalogo=? ORDER BY i.identificador
				""", (rs, row) -> {
			Integer itemId = rs.getInt("identificador");
			Integer activeItem = (Integer) rs.getObject("item_catalogo_activo_id");
			boolean active = activeItem != null && activeItem.equals(itemId);
			boolean auctioned = "si".equalsIgnoreCase(rs.getString("subastado"));
			Long purchaseId = (Long) rs.getObject("compra_id");
			Boolean companyBuyer = (Boolean) rs.getObject("comprador_empresa");
			String state = catalogItemState(active, auctioned, purchaseId, companyBuyer);
			String result = catalogItemResult(state);
			return item(itemId, rs.getInt("producto"), rs.getString("descripcionCatalogo"),
					rs.getBigDecimal("precioBase"), rs.getBigDecimal("comision"), authenticated,
					rs.getInt("orden_lote"), state, active, auctioned, result,
					authenticated ? purchaseId : null, authenticated ? companyBuyer : null);
		}, catalogId);
	}

	private Object item(Integer id, Integer productId, String description, BigDecimal base, BigDecimal commission,
			boolean authenticated, Integer order, String state, Boolean active, Boolean auctioned, String result,
			Long purchaseId, Boolean companyBuyer) {
		List<Integer> photoIds = jdbc.query("SELECT identificador FROM fotos WHERE producto=? ORDER BY identificador",
				(rs, row) -> rs.getInt(1), productId);
		List<String> photoUrls = photoIds.stream().map(this::photoUrl).toList();
		String mainPhotoUrl = photoUrls.isEmpty() ? null : photoUrls.get(0);
		ItemMetadata metadata = itemMetadata(id, productId);
		return authenticated ? new AuthenticatedItem(id, productId, description, photoIds, photoUrls, mainPhotoUrl,
				base, commission, order, state, state, active, auctioned, result, purchaseId, companyBuyer,
				metadata.ownerLabel(), metadata.objectDate(), metadata.history(), metadata.extendedHistory(),
				metadata.artist(), metadata.segment(), metadata.category(), metadata.consignmentId())
				: new PublicItem(id, productId, description, photoIds, photoUrls, mainPhotoUrl, order, "sin_estado",
						"sin_estado", null, null, null, null, null, metadata.ownerLabel(), metadata.objectDate(),
						metadata.history(), metadata.extendedHistory(), metadata.artist(), metadata.segment(),
						metadata.category(), metadata.consignmentId());
	}

	private ItemMetadata itemMetadata(Integer itemId, Integer productId) {
		List<ItemMetadata> values = jdbc.query("""
				SELECT s.id consignacion_id,s.historia,s.historia_extendida,s.artista_disenador,s.fecha_objeto,
				       s.segmento,s.categoria_sugerida,
				       CASE WHEN p.duenio IS NULL THEN NULL ELSE 'Propietario registrado' END duenio_actual
				FROM productos p
				LEFT JOIN app_solicitudes_consignacion s ON s.id=(
				    SELECT sx.id FROM app_solicitudes_consignacion sx
				    WHERE sx.item_catalogo_id=? OR sx.producto_id=?
				    ORDER BY CASE WHEN sx.item_catalogo_id=? THEN 0 ELSE 1 END, sx.id DESC
				    LIMIT 1
				)
				WHERE p.identificador=?
				""", (rs, row) -> new ItemMetadata((Long) rs.getObject("consignacion_id"),
						rs.getString("duenio_actual"), rs.getString("fecha_objeto"), rs.getString("historia"),
						rs.getString("historia_extendida"), rs.getString("artista_disenador"),
						rs.getString("segmento"), rs.getString("categoria_sugerida")),
				itemId, productId, itemId, productId);
		return values.isEmpty() ? ItemMetadata.empty() : values.get(0);
	}

	private String catalogItemState(boolean active, boolean auctioned, Long purchaseId, Boolean companyBuyer) {
		if (active) return "en_vivo";
		if (auctioned && purchaseId != null && Boolean.TRUE.equals(companyBuyer)) return "comprado_por_empresa";
		if (auctioned && purchaseId != null) return "vendido";
		if (auctioned) return "no_vendido";
		return "pendiente";
	}

	private String catalogItemResult(String state) {
		return switch (state) {
			case "vendido" -> "adjudicado";
			case "comprado_por_empresa" -> "comprado_por_empresa";
			case "no_vendido" -> "no_vendido";
			default -> null;
		};
	}

	private Long remainingSeconds(OffsetDateTime now, OffsetDateTime deadline) {
		if (deadline == null) return null;
		return Math.max(0, java.time.Duration.between(now, deadline).toSeconds());
	}

	private NextLot nextLot(Integer subastaId) {
		List<NextLot> values = jdbc.query("""
				SELECT item_id,orden_lote FROM (
					SELECT i.identificador item_id,ROW_NUMBER() OVER (ORDER BY i.identificador) orden_lote,i.subastado
					FROM "itemsCatalogo" i JOIN catalogos c ON c.identificador=i.catalogo
					WHERE c.subasta=?
				) ordered
				WHERE subastado='no'
				ORDER BY orden_lote LIMIT 1
				""", (rs, row) -> new NextLot(rs.getInt("item_id"), rs.getInt("orden_lote")), subastaId);
		return values.isEmpty() ? null : values.get(0);
	}

	private record NextLot(Integer itemId, Integer order) {
	}

	private String photoUrl(Integer photoId) {
		return "/api/items/fotos/" + photoId;
	}

	private MedioPago selectEnrollmentPayment(Long cuentaId, String currency, Long medioPagoId) {
		List<MedioPago> compatible = medios.findAllByCuentaIdAndDeletedAtIsNullOrderByCreatedAtDesc(cuentaId).stream()
				.filter(m -> enrollmentCompatible(m, currency)).toList();
		if (medioPagoId != null) {
			MedioPago selected = medios.findById(medioPagoId).orElseThrow(() -> notFound("Medio de pago inexistente"));
			if (!selected.getCuentaId().equals(cuentaId)) throw forbidden("El medio no pertenece al usuario", "RESOURCE_NOT_OWNED");
			if (!enrollmentCompatible(selected, currency)) throw unprocessable("Medio de pago incompatible", "PAYMENT_METHOD_CURRENCY_MISMATCH");
			return selected;
		}
		List<MedioPago> principal = compatible.stream().filter(MedioPago::getPrincipal).toList();
		if (principal.size() == 1) return principal.get(0);
		if (compatible.size() == 1) return compatible.get(0);
		if (compatible.isEmpty()) throw unprocessable("No existe un medio compatible", "PAYMENT_METHOD_CURRENCY_MISMATCH");
		throw conflict("Debe elegir un medio de pago", "PAYMENT_METHOD_SELECTION_REQUIRED");
	}

	private boolean enrollmentCompatible(MedioPago medio, String currency) {
		return medio.getDeletedAt() == null && medio.getMoneda().equals(currency) && enrollmentState(medio);
	}

	private boolean enrollmentState(MedioPago medio) {
		return ENROLLMENT_PAYMENT_STATES.contains(medio.getEstado());
	}

	private boolean bidCompatible(MedioPago medio, String currency) {
		return medio.getDeletedAt() == null && medio.getMoneda().equals(currency) && medio.getEstado().equals("verificado")
				&& medio.getVerificadoHasta() != null && medio.getVerificadoHasta().isAfter(OffsetDateTime.now());
	}

	private boolean requiresReview(MedioPago medio) {
		return medio.getEstado().equals("pendiente_verificacion") || medio.getEstado().equals("vencido")
				|| medio.getEstado().equals("verificado")
				&& (medio.getVerificadoHasta() == null || !medio.getVerificadoHasta().isAfter(OffsetDateTime.now()));
	}

	private List<PaymentOption> options(List<MedioPago> values) {
		return values.stream().map(m -> new PaymentOption(m.getId(), m.getTipo(), m.getMoneda(), m.getEstado(),
				m.getPrincipal(), m.getAliasVisible(), m.getUltimos4(), bidCompatible(m, m.getMoneda()), requiresReview(m))).toList();
	}

	private boolean enrollmentOpen(Auction auction) {
		return auction.allowsEnrollment() && Set.of("programada", "abierta").contains(auction.estadoOperativo())
				&& auction.start().isAfter(LocalDateTime.now().plusMinutes(60));
	}

	private boolean started(Auction auction) {
		return Set.of("en_vivo", "cerrada", "finalizada").contains(auction.estadoOperativo())
				|| !auction.start().isAfter(LocalDateTime.now());
	}

	private Integer activeItem(Integer subastaId) {
		return auctionQueries.findActiveItem(subastaId);
	}

	private boolean participatingInOtherAuction(Long cuentaId, Integer subastaId) {
		return auctionQueries.existsParticipationInOtherLiveAuction(cuentaId, subastaId);
	}

	private Auction auction(Integer id) {
		List<Auction> values = jdbc.query(AUCTION_SELECT + " WHERE s.identificador=?", (rs, row) -> auction(rs), id);
		if (values.isEmpty()) throw notFound("Subasta inexistente");
		return values.get(0);
	}

	private Auction auction(java.sql.ResultSet rs) throws java.sql.SQLException {
		Date date = rs.getDate("fecha");
		Time time = rs.getTime("hora");
		return new Auction(rs.getInt("identificador"), rs.getString("titulo"), rs.getString("descripcion"),
				date.toLocalDate(), time.toLocalTime(), rs.getString("ubicacion"), rs.getString("categoria"),
				rs.getString("moneda"), rs.getString("segmento"), rs.getString("estado_operativo"),
				rs.getBoolean("permite_inscripcion_online"),
				rematador(rs.getString("rematador_nombre"), rs.getString("rematador_matricula"),
						rs.getString("rematador_region")),
				rs.getString("imagen_principal_url"));
	}

	private Object summary(Auction a, boolean authenticated) {
		if (authenticated) return new AuthenticatedSummary(a.id(), a.title(), a.description(), a.date(), a.time(),
				a.location(), a.category(), a.currency(), a.segment(), a.operationalState(), a.mainImageUrl());
		return new PublicSummary(a.id(), a.title(), a.description(), a.date(), a.time(), a.location(), a.category(),
				a.currency(), a.segment(), publicState(a.operationalState(), false), a.mainImageUrl());
	}

	private Object detail(Auction a, boolean authenticated) {
		if (authenticated) return new AuthenticatedDetail(a.id(), a.title(), a.description(), a.date(), a.time(),
				a.location(), a.category(), a.currency(), a.segment(), a.operationalState(), a.allowsEnrollment(), true,
				a.auctioneer(), a.mainImageUrl());
		return new PublicDetail(a.id(), a.title(), a.description(), a.date(), a.time(), a.location(), a.category(),
				a.currency(), a.segment(), publicState(a.operationalState(), false), a.auctioneer(), a.mainImageUrl());
	}

	private Rematador rematador(String name, String license, String region) {
		if (blank(name) && blank(license) && blank(region)) return null;
		return new Rematador(cleanVisibleSeedText(name), cleanVisibleSeedText(license), blankToNull(region));
	}

	private String cleanVisibleSeedText(String value) {
		String normalized = blankToNull(value);
		if (normalized == null) return null;
		return normalized.replace("Martillero Demo", "Martillero QuickBid")
				.replace("MAT-DEMO-", "MAT-QB-");
	}

	private boolean blank(String value) {
		return value == null || value.isBlank();
	}

	private String blankToNull(String value) {
		return blank(value) ? null : value.trim();
	}

	@SuppressWarnings("unchecked")
	private Object catalog(Integer subastaId, Integer catalogId, String description, boolean authenticated) {
		List<?> values = items(catalogId, authenticated);
		return authenticated ? new AuthenticatedCatalog(subastaId, catalogId, description, (List<AuthenticatedItem>) values)
				: new PublicCatalog(subastaId, catalogId, description, (List<PublicItem>) values);
	}

	private String publicState(String state, boolean authenticated) {
		return !authenticated && state.equals("en_vivo") ? "abierta" : state;
	}

	private Registration registration(InscripcionSubasta value, boolean existing) {
		return new Registration(value.getId(), value.getSubastaId(), value.getMedioPagoId(), value.getEstado(), existing,
				value.getEstado().equals("pendiente_validacion"), value.getCreatedAt());
	}

	private CuentaApp account(Long id) {
		return cuentas.findById(id).orElseThrow(() -> notFound("Cuenta inexistente"));
	}

	private int categoryOrder(String category) {
		return switch (category) {
			case "comun" -> 1;
			case "especial" -> 2;
			case "plata" -> 3;
			case "oro" -> 4;
			case "platino" -> 5;
			default -> 0;
		};
	}

	private void filter(StringBuilder where, List<Object> args, String field, String value) {
		if (value != null && !value.isBlank()) { where.append(" AND ").append(field).append("=?"); args.add(value); }
	}

	private String optionalLower(String value) {
		return value == null || value.isBlank() ? null : value.trim().toLowerCase(Locale.ROOT);
	}

	private String optionalUpper(String value) {
		return value == null || value.isBlank() ? null : value.trim().toUpperCase(Locale.ROOT);
	}

	private void checkFilter(String field, String value, Set<String> allowed) {
		if (value != null && !allowed.contains(value)) {
			throw unprocessable("Filtro " + field + " invalido", "INVALID_FILTER");
		}
	}

	private void checkPage(int page, int size) {
		if (page < 0 || size < 1 || size > 100) throw bad("Paginación inválida", "INVALID_PAGE");
	}

	private BusinessException bad(String message, String code) { return new BusinessException(HttpStatus.BAD_REQUEST, message, code); }
	private BusinessException forbidden(String message, String code) { return new BusinessException(HttpStatus.FORBIDDEN, message, code); }
	private BusinessException conflict(String message, String code) { return new BusinessException(HttpStatus.CONFLICT, message, code); }
	private BusinessException unprocessable(String message, String code) { return new BusinessException(HttpStatus.UNPROCESSABLE_ENTITY, message, code); }
	private BusinessException notFound(String message) { return new BusinessException(HttpStatus.NOT_FOUND, message, "RESOURCE_NOT_FOUND"); }

	private record Auction(Integer id, String title, String description, LocalDate date, LocalTime time, String location,
			String category, String currency, String segment, String operationalState, boolean allowsEnrollment,
			Rematador auctioneer, String mainImageUrl) {
		LocalDateTime start() { return LocalDateTime.of(date, time); }
		String categoria() { return category; }
		String moneda() { return currency; }
		String estadoOperativo() { return operationalState; }
	}

	private record ItemMetadata(Long consignmentId, String ownerLabel, String objectDate, String history,
			String extendedHistory, String artist, String segment, String category) {
		static ItemMetadata empty() {
			return new ItemMetadata(null, null, null, null, null, null, null, null);
		}
	}
}
