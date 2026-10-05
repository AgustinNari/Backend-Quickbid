package com.example.quickbid.quickbid.service;

import java.math.BigDecimal;
import java.sql.PreparedStatement;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Set;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.env.Environment;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.example.quickbid.quickbid.audit.AuditEvent;
import com.example.quickbid.quickbid.audit.AuditService;
import com.example.quickbid.quickbid.dto.admin.AdminDtos;
import com.example.quickbid.quickbid.dto.admin.AdminDtos.Account;
import com.example.quickbid.quickbid.dto.admin.AdminDtos.Agreement;
import com.example.quickbid.quickbid.dto.admin.AdminDtos.AssignAuction;
import com.example.quickbid.quickbid.dto.admin.AdminDtos.Auction;
import com.example.quickbid.quickbid.dto.admin.AdminDtos.AuctionCreate;
import com.example.quickbid.quickbid.dto.admin.AdminDtos.AuctionUpdate;
import com.example.quickbid.quickbid.dto.admin.AdminDtos.CatalogItem;
import com.example.quickbid.quickbid.dto.admin.AdminDtos.Consignment;
import com.example.quickbid.quickbid.dto.admin.AdminDtos.DemoResetStatus;
import com.example.quickbid.quickbid.dto.admin.AdminDtos.PaymentMethod;
import com.example.quickbid.quickbid.dto.admin.AdminDtos.PurchaseSimulation;
import com.example.quickbid.quickbid.dto.admin.AdminDtos.Status;
import com.example.quickbid.quickbid.dto.response.ConsignmentDtos.Liquidation;
import com.example.quickbid.quickbid.dto.response.MedioPagoResponse;
import com.example.quickbid.quickbid.dto.response.PurchaseDtos.Detail;
import com.example.quickbid.quickbid.dto.response.PurchaseDtos.Payment;
import com.example.quickbid.quickbid.dto.response.SubastaDtos.AuctionLifecycleEvent;
import com.example.quickbid.quickbid.entity.app.CuentaApp;
import com.example.quickbid.quickbid.exception.BusinessException;
import com.example.quickbid.quickbid.repository.app.AdminQueryRepository;
import com.example.quickbid.quickbid.repository.app.AuctionQueryRepository;
import com.example.quickbid.quickbid.repository.app.CuentaAppRepository;
import com.example.quickbid.quickbid.repository.app.SolicitudRegistroRepository;
import com.example.quickbid.quickbid.repository.legacy.ClienteRepository;
import com.example.quickbid.quickbid.service.PurchaseService.PaymentOutcome;
import com.example.quickbid.quickbid.websocket.PurchaseRealtimePublisher;

@Service
public class AdminService {
    private static final Set<String> CATEGORIES = Set.of("comun", "especial", "plata", "oro", "platino");
	private static final Set<String> PAYMENT_METHOD_STATES = Set.of("pendiente_verificacion", "verificado", "rechazado", "vencido", "eliminado");
	private final JdbcTemplate jdbc;
	private final SolicitudRegistroRepository registrations;
	private final RegistrationApprovalService registrationApproval;
	private final CuentaAppRepository accounts;
	private final ClienteRepository clients;
	private final CategoriaService categories;
	private final MedioPagoService paymentMethods;
	private final PurchaseService purchases;
	private final AuctionTimerService auctionTimers;
	private final ConsignmentService consignments;
	private final AuditService audit;
	private final Environment environment;
	private final MailNotificationService mail;
	private final AdminQueryRepository queries;
	private final AuctionQueryRepository auctionQueries;
	private final PurchaseRealtimePublisher realtime;
	private final NotificationCleanupService notificationCleanup;
	private final boolean demoResetEnabled;
	private final boolean demoShortAuctionsEnabled;
	private final boolean demoAutoOpenAuctionsEnabled;
	private final boolean demoFirstLotWaitsForFirstBid;
	private final int demoAuctionStartDelaySeconds;

	public AdminService(JdbcTemplate jdbc, SolicitudRegistroRepository registrations,
			RegistrationApprovalService registrationApproval, CuentaAppRepository accounts, ClienteRepository clients,
			CategoriaService categories, MedioPagoService paymentMethods, PurchaseService purchases,
			AuctionTimerService auctionTimers, ConsignmentService consignments, AuditService audit,
			Environment environment, MailNotificationService mail, AdminQueryRepository queries,
			AuctionQueryRepository auctionQueries, PurchaseRealtimePublisher realtime,
			NotificationCleanupService notificationCleanup,
			@Value("${app.demo.reset-enabled:false}") boolean demoResetEnabled,
			@Value("${app.demo.short-auctions-enabled:false}") boolean demoShortAuctionsEnabled,
			@Value("${app.demo.auto-open-auctions-enabled:false}") boolean demoAutoOpenAuctionsEnabled,
			@Value("${app.demo.first-lot-waits-for-first-bid:false}") boolean demoFirstLotWaitsForFirstBid,
			@Value("${app.demo.auction-start-delay-seconds:120}") int demoAuctionStartDelaySeconds) {
		this.jdbc = jdbc;
		this.registrations = registrations;
		this.registrationApproval = registrationApproval;
		this.accounts = accounts;
		this.clients = clients;
		this.categories = categories;
		this.paymentMethods = paymentMethods;
		this.purchases = purchases;
		this.auctionTimers = auctionTimers;
		this.consignments = consignments;
		this.audit = audit;
		this.environment = environment;
		this.mail = mail;
		this.queries = queries;
		this.auctionQueries = auctionQueries;
		this.realtime = realtime;
		this.notificationCleanup = notificationCleanup;
		this.demoResetEnabled = demoResetEnabled;
		this.demoShortAuctionsEnabled = demoShortAuctionsEnabled;
		this.demoAutoOpenAuctionsEnabled = demoAutoOpenAuctionsEnabled;
		this.demoFirstLotWaitsForFirstBid = demoFirstLotWaitsForFirstBid;
		this.demoAuctionStartDelaySeconds = demoAuctionStartDelaySeconds;
	}

	@Transactional(readOnly = true)
	public List<AdminDtos.Registration> registrations() {
		return registrations.findAll().stream().map(this::registration).toList();
	}

	@Transactional(readOnly = true)
	public AdminDtos.Registration registration(Long id) {
		return registrations.findById(id).map(this::registration).orElseThrow(() -> notFound("Solicitud inexistente"));
	}

	public void approveRegistration(Long id, String document, String category, Integer employeeId) {
		registrationApproval.approve(id, document, employeeId, category);
	}

	public void rejectRegistration(Long id, String reason, Integer employeeId) {
		registrationApproval.reject(id, reason, employeeId);
	}

	@Transactional
	public Account block(Long id, Integer employeeId) {
		CuentaApp account = account(id);
		account.changeState("deshabilitada_admin");
		audit(employeeId, "usuario.bloqueado_admin", "cuenta", id);
		return account(account);
	}

	@Transactional
	public Account unblock(Long id, Integer employeeId) {
		CuentaApp account = account(id);
		if (!Set.of("deshabilitada_admin", "bloqueada_permanente").contains(account.getEstado())) {
			throw conflict("La cuenta no está bloqueada", "INVALID_STATE_TRANSITION");
		}
		account.changeState("activa");
		audit(employeeId, "usuario.desbloqueado_admin", "cuenta", id);
		return account(account);
	}

	@Transactional
	public Account points(Long id, Integer delta, Integer employeeId) {
		CuentaApp account = account(id);
		if (account.getPuntos() + delta < 0) throw bad("Los puntos no pueden quedar negativos", "INVALID_POINTS");
		categories.addPoints(account, delta, "ajuste_admin", "cuenta", id);
		audit(employeeId, "usuario.puntos_ajustados", "cuenta", id);
		return account(account);
	}

	@Transactional
	public Account category(Long id, String category, Integer employeeId) {
		CuentaApp account = account(id);
		String value = category.toLowerCase();
		if (!CATEGORIES.contains(value)) throw bad("Categoría inválida", "INVALID_CATEGORY");
		account.updateCategory(value);
		clients.findById(account.getClienteId()).orElseThrow(() -> notFound("Cliente inexistente")).updateCategory(value);
		audit(employeeId, "usuario.categoria_forzada", "cuenta", id);
		return account(account);
	}

	@Transactional(readOnly = true)
	public List<PaymentMethod> paymentMethods(String state) {
		String normalizedState = optionalLower(state);
		if (normalizedState != null && !PAYMENT_METHOD_STATES.contains(normalizedState)) {
			throw unprocessable("Estado de medio de pago invalido", "INVALID_FILTER");
		}
		return queries.findPaymentMethods(normalizedState);
	}

	public MedioPagoResponse verifyPaymentMethod(Long id, BigDecimal approvedLimit, Integer employeeId) {
		return paymentMethods.verify(id, employeeId, approvedLimit);
	}

	public MedioPagoResponse rejectPaymentMethod(Long id, String reason, Integer employeeId) {
		return paymentMethods.reject(id, employeeId, reason);
	}

	public Status expirePaymentMethods(Integer employeeId) {
		int expired = paymentMethods.expireVerified();
		audit(employeeId, "medio_pago.verificaciones_vencidas", "medio_pago", null);
		return new Status("procesado", expired + " medios vencidos");
	}

	public Status processDueJobs(Integer employeeId) {
		int expiredPaymentMethods = paymentMethods.expireVerified();
		int fines = purchases.expireDueFines();
		int purchases = this.purchases.abandonDueExtraPayments();
		int returns = consignments.expireDueReturns(employeeId);
		int notifications = notificationCleanup.cleanupOld();
		audit(employeeId, "vencimientos.procesados", "sistema", null);
		return new Status("procesado", "Medios vencidos: " + expiredPaymentMethods + ", multas vencidas: " + fines
				+ ", compras abandonadas: " + purchases + ", devoluciones vencidas: " + returns
				+ ", notificaciones eliminadas: " + notifications);
	}

	public Status expireDueFines(Integer employeeId) {
		int expired = purchases.expireDueFines();
		audit(employeeId, "multas.vencidas_procesadas", "multa", null);
		return new Status("procesado", expired + " multas vencidas");
	}

	public Status abandonDuePurchases(Integer employeeId) {
		int abandoned = purchases.abandonDueExtraPayments();
		audit(employeeId, "compras.vencidas_procesadas", "compra", null);
		return new Status("procesado", abandoned + " compras abandonadas");
	}

	public Status expireDueConsignmentReturns(Integer employeeId) {
		int expired = consignments.expireDueReturns(employeeId);
		audit(employeeId, "consignaciones.devoluciones_vencidas_procesadas", "consignacion", null);
		return new Status("procesado", expired + " devoluciones vencidas");
	}

	public Status cleanupNotifications(Integer employeeId) {
		int deleted = notificationCleanup.cleanupOld();
		audit(employeeId, "notificaciones.limpieza_antiguas", "notificacion", null);
		return new Status("procesado", deleted + " notificaciones eliminadas");
	}

	@Transactional
	public Auction createAuction(AuctionCreate request, Integer employeeId) {
		validateAuction(request.fecha(), request.hora(), request.categoria(), request.moneda(), request.montoMinimo(), request.montoMaximo());
		long id = insert("""
				INSERT INTO subastas(fecha,hora,estado,ubicacion,categoria) VALUES (?,?,'abierta',?,?)
				""", "identificador", statement -> {
			statement.setObject(1, request.fecha());
			statement.setObject(2, request.hora());
			statement.setString(3, request.ubicacion());
			statement.setString(4, request.categoria().toLowerCase());
		});
		jdbc.update("""
				INSERT INTO app_subasta_ext(subasta_id,titulo,descripcion,moneda,segmento,estado_operativo,
					permite_inscripcion_online, monto_minimo, monto_maximo) VALUES (?,?,?,?,?,'programada',?,?,?)
				""", id, request.titulo(), request.descripcion(), request.moneda().toUpperCase(), request.segmento(),
				request.permiteInscripcionOnline() == null || request.permiteInscripcionOnline(), request.montoMinimo(), request.montoMaximo());
		jdbc.update("INSERT INTO app_subasta_estado_vivo(subasta_id,version,usuarios_conectados) VALUES (?,0,0)", id);
		audit(employeeId, "subasta.creada", "subasta", id);

		jdbc.update("""
			INSERT INTO catalogos(identificador, descripcion, subasta, responsable) VALUES (?,?,?,?)
			""", id+2000, "Catálogo de subasta " + id, id, 1004);

		return auction(Math.toIntExact(id));
	}

	@Transactional
	public Auction updateAuction(Integer id, AuctionUpdate request, Integer employeeId) {
		validateAuction(request.fecha(), request.hora(), request.categoria(), null, request.montoMinimo(), request.montoMaximo());
		if (!auctionQueries.existsAuction(id)) throw notFound("Subasta inexistente");
		jdbc.update("UPDATE subastas SET fecha=?,hora=?,ubicacion=?,categoria=? WHERE identificador=?", request.fecha(),
				request.hora(), request.ubicacion(), request.categoria().toLowerCase(), id);
		jdbc.update("""
				UPDATE app_subasta_ext SET titulo=?,descripcion=?,segmento=?,permite_inscripcion_online=?,
					monto_minimo=?, monto_maximo=?, updated_at=CURRENT_TIMESTAMP WHERE subasta_id=?
				""", request.titulo(), request.descripcion(), request.segmento(),
				request.permiteInscripcionOnline() == null || request.permiteInscripcionOnline(), request.montoMinimo(), request.montoMaximo(), id);
		audit(employeeId, "subasta.actualizada", "subasta", id.longValue());
		return auction(id);
	}

	@Transactional
	public Auction openAuction(Integer id, Integer employeeId) {
		requireAuction(id);
		boolean becameLive = auctionQueries.isNotLive(id);
		jdbc.update("UPDATE app_subasta_ext SET estado_operativo='en_vivo',updated_at=CURRENT_TIMESTAMP WHERE subasta_id=?", id);
		if (becameLive) {
			Long version = startLiveLifecycle(id);
			auctionQueries.findNotificationRecipientsForAuctionStart(id).forEach(accountId -> {
				jdbc.update("""
						INSERT INTO app_notificaciones(cuenta_id,tipo,titulo,descripcion,referencia_tipo,referencia_id)
						VALUES (?,'subasta_inscripta_proxima_inicio','Subasta disponible en vivo',
							'Una subasta en la que manifestaste interés ya está disponible en vivo.','subasta',?)
						""", accountId, id);
				mail.critical(accountId, "subasta_inscripta_proxima_inicio");
			});
			realtime.afterCommit(new AuctionLifecycleEvent("SUBASTA_INICIADA", id, null, version));
		}
		audit(employeeId, "subasta.abierta", "subasta", id.longValue());
		return auction(id);
	}

	private Long startLiveLifecycle(Integer auctionId) {
		int updated = jdbc.update("""
				UPDATE app_subasta_estado_vivo
				SET item_catalogo_activo_id=NULL,version=version+1,retencion_hasta=NULL,
					lote_finaliza_estimado_at=NULL,proximo_lote_programado_at=CURRENT_TIMESTAMP,
					subasta_finaliza_programado_at=NULL,updated_at=CURRENT_TIMESTAMP
				WHERE subasta_id=?
				""", auctionId);
		if (updated == 0) {
			jdbc.update("""
					INSERT INTO app_subasta_estado_vivo(subasta_id,version,usuarios_conectados,proximo_lote_programado_at)
					VALUES (?,1,0,CURRENT_TIMESTAMP)
					""", auctionId);
		}
		return jdbc.queryForObject("SELECT version FROM app_subasta_estado_vivo WHERE subasta_id=?", Long.class,
				auctionId);
	}

	public Status closeAuction(Integer id, Integer employeeId) {
		purchases.closeAuction(id);
		audit(employeeId, "subasta.cerrada_admin", "subasta", id.longValue());
		return new Status("cerrada", "Subasta cerrada");
	}

	@Transactional
	public Status setActiveItem(Integer id, Integer itemId, Integer employeeId) {
		requireAuction(id);
		if (!auctionQueries.existsAuctionItem(id, itemId)) throw bad("El item no pertenece a la subasta", "INVALID_AUCTION_ITEM");
		OffsetDateTime lotDeadline = shouldWaitForFirstDemoBid(id, itemId) ? null : OffsetDateTime.now().plusSeconds(60);
		jdbc.update("""
				UPDATE app_subasta_estado_vivo SET item_catalogo_activo_id=?,version=version+1,
					lote_iniciado_at=CURRENT_TIMESTAMP,retencion_hasta=NULL,lote_finaliza_estimado_at=?,
					proximo_lote_programado_at=NULL,subasta_finaliza_programado_at=NULL,updated_at=CURRENT_TIMESTAMP
				WHERE subasta_id=?
				""", itemId, lotDeadline, id);
		Long version = jdbc.queryForObject("SELECT version FROM app_subasta_estado_vivo WHERE subasta_id=?",
				Long.class, id);
		realtime.afterCommit(new AuctionLifecycleEvent("LOTE_ACTIVADO", id, itemId, version, lotDeadline));
		audit(employeeId, "subasta.item_activo_actualizado", "subasta", id.longValue());
		return new Status("item_activo", "Item activo " + itemId);
	}

	public Detail closeLot(Integer id, PaymentOutcome outcome, Integer employeeId) {
		Detail detail = purchases.closeLot(id, outcome);
		audit(employeeId, "subasta.lote_cerrado_admin", "compra", detail.id());
		return detail;
	}

	public Status processAuctionTimers(Integer employeeId) {
		var result = auctionTimers.processDueTimers();
		audit(employeeId, "subasta.timers_procesados", "subasta", null);
		return new Status("procesado", "Lotes cerrados: " + result.lotesCerrados()
				+ ", lotes activados: " + result.lotesActivados()
				+ ", subastas finalizadas: " + result.subastasFinalizadas()
				+ ", subastas demo abiertas: " + result.subastasDemoAbiertas());
	}

	@Transactional
	public Integer addCatalogItem(Integer auctionId, CatalogItem request, Integer employeeId) {


		if (!auctionQueries.existsCatalogForAuction(auctionId, request.catalogoId())) {
			throw bad("Catalogo incompatible", "INVALID_CATALOG");
		}
		if (!auctionQueries.existsProduct(request.productoId())) {
			throw notFound("Producto inexistente");
		}
		int montoMin = auctionQueries.getMinPrice(auctionId);
		int montoMax = auctionQueries.getMaxPrice(auctionId);
		if (request.precioBase().signum() <= 0 || request.comision().signum() <= 0) {
			throw bad("Importes invalidos", "INVALID_AMOUNT");
		}
		if (montoMin > 0 && request.precioBase().compareTo(BigDecimal.valueOf(montoMin)) < 0) {
			throw bad("Precio base menor al minimo de la subasta", "INVALID_AMOUNT");
		}
		if (montoMax > 0 && request.precioBase().compareTo(BigDecimal.valueOf(montoMax)) > 0) {
			throw bad("Precio base mayor al maximo de la subasta", "INVALID_AMOUNT");
		}

		long id = insert("""
				INSERT INTO "itemsCatalogo"(catalogo,producto,"precioBase",comision,subastado) VALUES (?,?,?,?,'no')
				""", "identificador", statement -> {
			statement.setInt(1, request.catalogoId());
			statement.setInt(2, request.productoId());
			statement.setBigDecimal(3, request.precioBase());
			statement.setBigDecimal(4, request.comision());
		});
		audit(employeeId, "catalogo.item_agregado", "item_catalogo", id);
		return Math.toIntExact(id);
	}

	public Payment simulatePayment(Long purchaseId, PurchaseSimulation request, boolean success, Integer employeeId) {
		String type = request.tipo() == null ? "extras" : request.tipo().toLowerCase();
		Payment payment = type.equals("multa")
				? success ? purchases.simulateSuccessfulFine(request.cuentaId(), purchaseId, request.medioPagoId())
						: purchases.simulateFailedFine(request.cuentaId(), purchaseId, request.medioPagoId())
				: success ? purchases.simulateSuccessfulExtras(request.cuentaId(), purchaseId, request.medioPagoId())
						: purchases.simulateFailedExtras(request.cuentaId(), purchaseId, request.medioPagoId());
		audit(employeeId, success ? "pago.simulacion_exitosa" : "pago.simulacion_fallida", "pago", payment.id());
		return payment;
	}

	public Status abandonPurchase(Long id, boolean pickupFailure, Integer employeeId) {
		purchases.abandon(id, pickupFailure);
		audit(employeeId, "compra.abandonada_admin", "compra", id);
		return new Status("abandonada", "Compra marcada como abandonada");
	}

	public Status expireFine(Long id, Integer employeeId) {
		purchases.expireFine(id, true);
		audit(employeeId, "multa.vencida_admin", "multa", id);
		return new Status("vencida", "Multa vencida");
	}

	public Status markFinePaid(Long id, Integer employeeId) {
		purchases.markFinePaid(id);
		audit(employeeId, "multa.pagada_admin", "multa", id);
		return new Status("pagada", "Multa marcada como pagada");
	}

	@Transactional(readOnly = true)
	public List<Consignment> consignments() {
		return queries.findConsignments();
	}

	public void requestDocuments(Long id, Integer employeeId) {
		consignments.requestOriginDocuments(id, employeeId);
	}

	public void rejectConsignment(Long id, String reason, Integer employeeId) {
		consignments.rejectDigitalReview(id, employeeId, reason);
	}

	public void approveDigitalReview(Long id, Integer employeeId) {
		consignments.approveDigitalReview(id, employeeId);
	}

	public void assignConsignmentCategory(Long id, String category, Integer employeeId) {
		consignments.assignAuctionCategory(id, employeeId, category);
	}

	public void reviewDocuments(Long id, boolean approved, String reason, Integer employeeId) {
		consignments.reviewOriginDocuments(id, employeeId, approved, reason);
	}

	public void markPhysicalReception(Long id, Integer employeeId) {
		consignments.markPhysicalReception(id, employeeId);
	}

	public void approvePhysicalReview(Long id, Integer employeeId) {
		consignments.approvePhysicalReview(id, employeeId);
	}

	public void rejectPhysicalReview(Long id, String reason, Integer employeeId) {
		consignments.rejectPhysicalReview(id, employeeId, reason);
	}

	public Integer verifyOwner(Long accountId, boolean financial, boolean judicial, int risk, Integer employeeId) {
		return consignments.verifyConsignor(accountId, employeeId, financial, judicial, risk);
	}

	public void proposeAgreement(Long id, Agreement request, Integer employeeId) {
		consignments.proposeAgreement(
				id,
				employeeId,
				request.valorBase(),
				request.moneda(),
				request.comisionCompradorPct(),
				request.comisionVendedorPct(),
				request.condiciones());
	}

	public Integer assignAuction(Long id, AssignAuction request, Integer employeeId) {
		return consignments.assignAuctionAndInsurance(
				id,
				employeeId,
				request.subastaId(),
				request.catalogoId(),
				request.polizaCombinada());
	}

	public Liquidation liquidate(Long id, Long paymentMethodId, Integer employeeId) {
		return consignments.liquidate(id, employeeId, paymentMethodId);
	}

	public void markReturnIncomplete(Long id, Integer employeeId) {
		consignments.markReturnIncomplete(id, employeeId);
	}

	public Status seed(String scope, Integer employeeId) {
		requireDevOrTest();
		audit(employeeId, "seed." + scope + ".solicitado", "seed", null);
		return new Status("sin_cambios", "Flyway ya carga el seed " + scope + " de forma idempotente por base");
	}

	@Transactional
	public DemoResetStatus resetDemo(Integer employeeId) {
		if (!demoResetEnabled) {
			throw forbidden("El reset demo no está habilitado", "DEMO_RESET_DISABLED");
		}
		final int auctionId = demoAuctionId();
		List<Integer> demoItems = demoItemIds(auctionId);
		if (demoItems.isEmpty()) {
			if (auctionId != 6011) {
				return resetLegacyDemo(employeeId, auctionId);
			}
			throw conflict("No hay lotes demo configurados; recrea la base demo", "DEMO_RECREATE_REQUIRED");
		}
		Integer itemId = demoItems.get(0);
		releaseDemoReservations(auctionId);
		deleteDemoNotifications(auctionId);
		deleteDemoPurchases(auctionId);
		deleteDemoBids(auctionId);
		resetDemoCatalogState(auctionId);
		resetDemoAccountsAndPaymentMethods(auctionId);
		LocalDateTime scheduledAt = LocalDateTime.now().plusSeconds(Math.max(0, demoAuctionStartDelaySeconds));
		jdbc.update("UPDATE subastas SET fecha=?,hora=?,estado='abierta' WHERE identificador=?",
				scheduledAt.toLocalDate(), scheduledAt.toLocalTime(), auctionId);
		jdbc.update("""
				UPDATE app_subasta_ext SET estado_operativo='programada',updated_at=CURRENT_TIMESTAMP
				WHERE subasta_id=?
				""", auctionId);
		jdbc.update("""
				UPDATE app_subasta_estado_vivo
				SET item_catalogo_activo_id=NULL,version=version+1,usuarios_conectados=0,
					lote_iniciado_at=NULL,retencion_hasta=NULL,lote_finaliza_estimado_at=NULL,
					proximo_lote_programado_at=NULL,subasta_finaliza_programado_at=NULL,
					updated_at=CURRENT_TIMESTAMP
				WHERE subasta_id=?
				""", auctionId);
		audit(employeeId, "seed.reset_demo_app_owned", "subasta", (long) auctionId);
		return new DemoResetStatus("preparada",
				"Subasta " + auctionId + " programada para demo; el lote inicial será " + itemId,
				auctionId, scheduledAt, itemId, demoItems, demoAutoOpenAuctionsEnabled,
				demoFirstLotWaitsForFirstBid);
	}

	private DemoResetStatus resetLegacyDemo(Integer employeeId, Integer auctionId) {
		List<Integer> items = jdbc.query("""
				SELECT i.identificador FROM "itemsCatalogo" i
				JOIN catalogos c ON c.identificador=i.catalogo
				WHERE c.subasta=?
				ORDER BY i.identificador
				""", (rs, row) -> rs.getInt(1), auctionId);
		if (items.isEmpty()) {
			throw conflict("No hay lotes demo configurados; recrea la base demo", "DEMO_RECREATE_REQUIRED");
		}
		Integer itemId = items.get(items.size() - 1);
		jdbc.update("""
				UPDATE app_reservas_medio_pago SET estado='liberada',released_at=CURRENT_TIMESTAMP
				WHERE estado='activa' AND puja_id IN (
					SELECT id FROM app_pujas_live WHERE subasta_id=?
				)
				""", auctionId);
		jdbc.update("UPDATE app_pujas_live SET estado='superada' WHERE subasta_id=? AND estado='aceptada'",
				auctionId);
		jdbc.update("UPDATE \"itemsCatalogo\" SET subastado='no' WHERE identificador=?", itemId);
		jdbc.update("UPDATE subastas SET estado='abierta' WHERE identificador=?", auctionId);
		jdbc.update("""
				UPDATE app_subasta_ext SET estado_operativo='abierta',updated_at=CURRENT_TIMESTAMP
				WHERE subasta_id=?
				""", auctionId);
		jdbc.update("""
				UPDATE app_subasta_estado_vivo
				SET item_catalogo_activo_id=NULL,version=version+1,retencion_hasta=NULL,
					lote_finaliza_estimado_at=NULL,proximo_lote_programado_at=NULL,
					subasta_finaliza_programado_at=NULL,updated_at=CURRENT_TIMESTAMP
				WHERE subasta_id=?
				""", auctionId);
		audit(employeeId, "seed.reset_demo_legacy", "subasta", (long) auctionId);
		return new DemoResetStatus("preparada",
				"Subasta demo legacy " + auctionId + " lista para abrir manualmente; activar item " + itemId,
				auctionId, null, itemId, List.of(itemId), demoAutoOpenAuctionsEnabled,
				demoFirstLotWaitsForFirstBid);
	}

	private boolean shouldWaitForFirstDemoBid(Integer auctionId, Integer itemId) {
		return demoFirstLotWaitsForFirstBid && auctionId == 6011 && itemId != null
				&& itemId.equals(firstDemoItemId(auctionId))
				&& count("SELECT COUNT(*) FROM app_pujas_live WHERE subasta_id=?", auctionId) == 0;
	}

	private Integer firstDemoItemId(Integer auctionId) {
		List<Integer> values = demoItemIds(auctionId);
		return values.isEmpty() ? null : values.get(0);
	}

	private List<Integer> demoItemIds(Integer auctionId) {
		return jdbc.query("""
				SELECT i.identificador FROM "itemsCatalogo" i
				JOIN catalogos c ON c.identificador=i.catalogo
				WHERE c.subasta=? AND i.identificador IN (9011,9012,9013)
				ORDER BY i.identificador
				""", (rs, row) -> rs.getInt(1), auctionId);
	}

	private void releaseDemoReservations(Integer auctionId) {
		jdbc.update("""
				UPDATE app_reservas_medio_pago SET estado='liberada',released_at=CURRENT_TIMESTAMP
				WHERE estado='activa' AND puja_id IN (
					SELECT id FROM app_pujas_live WHERE subasta_id=? AND item_catalogo_id IN (9011,9012,9013)
				)
				""", auctionId);
	}

	private void deleteDemoPurchases(Integer auctionId) {
		if (count("SELECT COUNT(*) FROM app_compras WHERE subasta_id=? AND item_catalogo_id IN (9011,9012,9013)",
				auctionId) == 0) {
			return;
		}
		List<Long> fileIds = jdbc.query("""
				SELECT archivo_id FROM app_documentos
				WHERE referencia_tipo='compra' AND referencia_id IN (
					SELECT id FROM app_compras WHERE subasta_id=? AND item_catalogo_id IN (9011,9012,9013)
				)
				""", (rs, row) -> rs.getLong(1), auctionId);
		jdbc.update("""
				DELETE FROM app_documentos WHERE referencia_tipo='compra' AND referencia_id IN (
					SELECT id FROM app_compras WHERE subasta_id=? AND item_catalogo_id IN (9011,9012,9013)
				)
				""", auctionId);
		for (Long fileId : fileIds) {
			jdbc.update("DELETE FROM app_archivos WHERE id=?", fileId);
		}
		jdbc.update("""
				DELETE FROM app_pagos WHERE compra_id IN (
					SELECT id FROM app_compras WHERE subasta_id=? AND item_catalogo_id IN (9011,9012,9013)
				) OR multa_id IN (
					SELECT id FROM app_multas WHERE compra_id IN (
						SELECT id FROM app_compras WHERE subasta_id=? AND item_catalogo_id IN (9011,9012,9013)
					)
				)
				""", auctionId, auctionId);
		jdbc.update("""
				DELETE FROM app_entregas WHERE compra_id IN (
					SELECT id FROM app_compras WHERE subasta_id=? AND item_catalogo_id IN (9011,9012,9013)
				)
				""", auctionId);
		jdbc.update("""
				DELETE FROM app_multas WHERE compra_id IN (
					SELECT id FROM app_compras WHERE subasta_id=? AND item_catalogo_id IN (9011,9012,9013)
				)
				""", auctionId);
		jdbc.update("""
				DELETE FROM app_liquidaciones_consignacion WHERE compra_id IN (
					SELECT id FROM app_compras WHERE subasta_id=? AND item_catalogo_id IN (9011,9012,9013)
				)
				""", auctionId);
		jdbc.update("DELETE FROM app_compras WHERE subasta_id=? AND item_catalogo_id IN (9011,9012,9013)",
				auctionId);
	}

	private void deleteDemoNotifications(Integer auctionId) {
		jdbc.update("""
				DELETE FROM app_notificaciones
				WHERE referencia_tipo='puja' AND referencia_id IN (
					SELECT id FROM app_pujas_live WHERE subasta_id=? AND item_catalogo_id IN (9011,9012,9013)
				)
				""", auctionId);
		jdbc.update("""
				DELETE FROM app_notificaciones
				WHERE referencia_tipo='compra' AND referencia_id IN (
					SELECT id FROM app_compras WHERE subasta_id=? AND item_catalogo_id IN (9011,9012,9013)
				)
				""", auctionId);
		jdbc.update("""
				DELETE FROM app_notificaciones
				WHERE referencia_tipo='multa' AND referencia_id IN (
					SELECT id FROM app_multas WHERE compra_id IN (
						SELECT id FROM app_compras WHERE subasta_id=? AND item_catalogo_id IN (9011,9012,9013)
					)
				)
				""", auctionId);
		jdbc.update("""
				DELETE FROM app_notificaciones
				WHERE referencia_tipo='subasta' AND referencia_id=? AND tipo<>'demo_lista'
				""", auctionId);
	}

	private void deleteDemoBids(Integer auctionId) {
		jdbc.update("""
				DELETE FROM app_reservas_medio_pago WHERE puja_id IN (
					SELECT id FROM app_pujas_live WHERE subasta_id=? AND item_catalogo_id IN (9011,9012,9013)
				)
				""", auctionId);
		jdbc.update("DELETE FROM app_pujas_live WHERE subasta_id=? AND item_catalogo_id IN (9011,9012,9013)",
				auctionId);
		jdbc.update("DELETE FROM pujos WHERE item IN (9011,9012,9013)");
		jdbc.update("DELETE FROM \"registroDeSubasta\" WHERE subasta=? AND producto IN (8011,8012,8013)",
				auctionId);
	}

	private void resetDemoCatalogState(Integer auctionId) {
		jdbc.update("UPDATE \"itemsCatalogo\" SET subastado='no' WHERE identificador IN (9011,9012,9013)");
		jdbc.update("""
				UPDATE app_solicitudes_consignacion
				SET estado='en_subasta',updated_at=CURRENT_TIMESTAMP
				WHERE subasta_id=? AND item_catalogo_id IN (9011,9012,9013)
				""", auctionId);
	}

	private void resetDemoAccountsAndPaymentMethods(Integer auctionId) {
		if (auctionId != 6011) return;
		jdbc.update("""
				UPDATE app_medios_pago
				SET consumo_actual=0, estado='verificado', deleted_at=NULL, updated_at=CURRENT_TIMESTAMP,
					verificado_hasta=?
				WHERE id IN (5001,5010)
				""", LocalDateTime.now().plusDays(7));
		jdbc.update("""
				UPDATE app_cuentas SET estado='activa', puntos=900, categoria_calculada='plata',
					intentos_login=0, updated_at=CURRENT_TIMESTAMP
				WHERE id=3001
				""");
		jdbc.update("""
				UPDATE app_cuentas SET estado='activa', puntos=850, categoria_calculada='plata',
					intentos_login=0, updated_at=CURRENT_TIMESTAMP
				WHERE id=3005
				""");
		jdbc.update("UPDATE clientes SET categoria='plata' WHERE identificador IN (2001,2005)");
		jdbc.update("""
				UPDATE app_cuentas SET estado='restriccion_multa', puntos=300, categoria_calculada='especial',
					updated_at=CURRENT_TIMESTAMP
				WHERE id=3002
				""");
		jdbc.update("UPDATE clientes SET categoria='especial' WHERE identificador=2002");
		jdbc.update("""
				UPDATE app_cuentas SET estado='bloqueada_permanente', puntos=0, categoria_calculada='comun',
					updated_at=CURRENT_TIMESTAMP
				WHERE id=3003
				""");
		jdbc.update("UPDATE clientes SET categoria='comun' WHERE identificador=2003");
		jdbc.update("""
				UPDATE app_cuentas SET estado='activa', puntos=80, categoria_calculada='comun',
					updated_at=CURRENT_TIMESTAMP
				WHERE id=3006
				""");
		jdbc.update("UPDATE clientes SET categoria='comun' WHERE identificador=2006");
	}

	private int demoAuctionId() {
		return auctionQueries.existsAuction(6011) ? 6011 : 6004;
	}

	private Auction auction(Integer id) {
		return queries.findAuction(id).orElseThrow(() -> notFound("Subasta inexistente"));
	}

	private void requireAuction(Integer id) { auction(id); }

	private void validateAuction(LocalDate date, LocalTime time, String category, String currency, int montoMinimo, int montoMaximo) {
		if (demoShortAuctionsEnabled) {
			if (!LocalDateTime.of(date, time).isAfter(LocalDateTime.now().plusMinutes(5))) {
				throw bad("La fecha y hora deben superar cinco minutos", "INVALID_AUCTION_DATE");
			}
		} else if (!date.isAfter(LocalDate.now().plusDays(10))) {
			throw bad("La fecha debe superar diez dias", "INVALID_AUCTION_DATE");
		}
		if (!CATEGORIES.contains(category.toLowerCase())) throw bad("Categoría inválida", "INVALID_CATEGORY");
		if (currency != null && !Set.of("ARS", "USD").contains(currency.toUpperCase())) throw bad("Moneda inválida", "INVALID_CURRENCY");
		if (montoMinimo < 0 || montoMaximo < 0 || montoMinimo > montoMaximo) throw bad("Montos inválidos", "INVALID_AMOUNT_RANGE");
	}

	private void requireDevOrTest() {
		if (Set.of(environment.getActiveProfiles()).stream().noneMatch(profile -> profile.equals("dev") || profile.equals("test"))) {
			throw forbidden("Endpoint disponible solo en dev/test", "DEV_ONLY_ENDPOINT");
		}
	}

	private String optionalLower(String value) {
		return value == null || value.isBlank() ? null : value.trim().toLowerCase();
	}

	private CuentaApp account(Long id) {
		return accounts.findById(id).orElseThrow(() -> notFound("Cuenta inexistente"));
	}

	private int count(String sql, Object... args) {
		Integer value = jdbc.queryForObject(sql, Integer.class, args);
		return value == null ? 0 : value;
	}

	private AdminDtos.Registration registration(com.example.quickbid.quickbid.entity.app.SolicitudRegistro value) {
		return new AdminDtos.Registration(
				value.getId(),
				value.getEmail(),
				value.getNombre(),
				value.getApellido(),
				value.getEstado(),
				value.getMotivoRechazo(),
				value.getPersonaId(),
				value.getClienteId());
	}

	private Account account(CuentaApp value) {
		return new Account(
				value.getId(),
				value.getEmail(),
				value.getEstado(),
				value.getPuntos(),
				value.getCategoriaCalculada());
	}

	private void audit(Integer employeeId, String action, String entity, Long id) {
		audit.record(new AuditEvent("admin", employeeId.longValue(), action, entity, id, "{}"));
	}

	private long insert(String sql, String column, SqlBinder binder) {
		GeneratedKeyHolder keys = new GeneratedKeyHolder();
		jdbc.update(connection -> {
			PreparedStatement statement = connection.prepareStatement(sql, new String[] { column });
			binder.bind(statement);
			return statement;
		}, keys);
		return keys.getKey().longValue();
	}

	private BusinessException bad(String message, String code) { return new BusinessException(HttpStatus.BAD_REQUEST, message, code); }
	private BusinessException forbidden(String message, String code) { return new BusinessException(HttpStatus.FORBIDDEN, message, code); }
	private BusinessException conflict(String message, String code) { return new BusinessException(HttpStatus.CONFLICT, message, code); }
	private BusinessException unprocessable(String message, String code) { return new BusinessException(HttpStatus.UNPROCESSABLE_ENTITY, message, code); }
	private BusinessException notFound(String message) { return new BusinessException(HttpStatus.NOT_FOUND, message, "RESOURCE_NOT_FOUND"); }

	@FunctionalInterface
	private interface SqlBinder { void bind(PreparedStatement statement) throws java.sql.SQLException; }
}
