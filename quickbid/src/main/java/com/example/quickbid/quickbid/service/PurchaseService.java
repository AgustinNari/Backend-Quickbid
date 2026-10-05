package com.example.quickbid.quickbid.service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.io.IOException;
import java.sql.PreparedStatement;
import java.security.MessageDigest;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.HexFormat;
import java.util.Set;
import java.util.UUID;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.example.quickbid.quickbid.audit.AuditEvent;
import com.example.quickbid.quickbid.audit.AuditService;
import com.example.quickbid.quickbid.dto.request.PurchaseDeliveryRequest;
import com.example.quickbid.quickbid.dto.request.PurchasePaymentRequest;
import com.example.quickbid.quickbid.dto.response.PurchaseDtos;
import com.example.quickbid.quickbid.dto.response.PurchaseDtos.Detail;
import com.example.quickbid.quickbid.dto.response.PurchaseDtos.Document;
import com.example.quickbid.quickbid.dto.response.PurchaseDtos.Fine;
import com.example.quickbid.quickbid.dto.response.PurchaseDtos.LotClosedEvent;
import com.example.quickbid.quickbid.dto.response.PurchaseDtos.Page;
import com.example.quickbid.quickbid.dto.response.PurchaseDtos.Payment;
import com.example.quickbid.quickbid.dto.response.PurchaseDtos.Summary;
import com.example.quickbid.quickbid.dto.response.SubastaDtos.AuctionLifecycleEvent;
import com.example.quickbid.quickbid.entity.app.CuentaApp;
import com.example.quickbid.quickbid.entity.app.ArchivoApp;
import com.example.quickbid.quickbid.exception.BusinessException;
import com.example.quickbid.quickbid.repository.app.CuentaAppRepository;
import com.example.quickbid.quickbid.repository.app.DireccionEnvioRepository;
import com.example.quickbid.quickbid.repository.app.ArchivoAppRepository;
import com.example.quickbid.quickbid.repository.app.PurchaseReadRepository;
import com.example.quickbid.quickbid.repository.app.PurchaseReadRepository.DocumentFile;
import com.example.quickbid.quickbid.storage.FileDownload;
import com.example.quickbid.quickbid.storage.StorageService;
import com.example.quickbid.quickbid.websocket.PurchaseRealtimePublisher;

@Service
public class PurchaseService {
	private static final BigDecimal FINE_RATE = new BigDecimal("0.10");
	private static final int WINNING_BID_POINTS = 80;
	private static final int EXTRAS_PAID_POINTS = 20;
	private static final int NEXT_LOT_DELAY_SECONDS = 60;
	private static final int AUCTION_CLOSE_DELAY_SECONDS = 120;
	private static final Set<String> PURCHASE_STATES = Set.of("adjudicacion_pendiente", "multa_activa",
			"pagos_extra_pendientes", "pagada", "entrega_pendiente", "retiro_pendiente",
			"abandonada_por_incumplimiento_pago", "abandonada_por_incumplimiento_retiro", "completada");
	private final JdbcTemplate jdbc;
	private final CuentaAppRepository accounts;
	private final CategoriaService categories;
	private final AuditService audit;
	private final PurchaseRealtimePublisher realtime;
	private final MailNotificationService mail;
	private final PurchaseReadRepository reads;
	private final DireccionEnvioRepository addresses;
	private final ArchivoAppRepository files;
	private final StorageService storage;
	private final SimplePdfService pdfs;
	private final PaymentFailureSimulationService paymentFailureSimulation;
	private final BigDecimal shippingFlatCost;
	private final int completedPoints;
	private final int fineGeneratedPointsPenalty;
	private final int fineExpiredPointsPenalty;

	public PurchaseService(JdbcTemplate jdbc, CuentaAppRepository accounts, CategoriaService categories,
			AuditService audit, PurchaseRealtimePublisher realtime, MailNotificationService mail,
			PurchaseReadRepository reads, DireccionEnvioRepository addresses, ArchivoAppRepository files,
			StorageService storage, SimplePdfService pdfs, PaymentFailureSimulationService paymentFailureSimulation,
			@Value("${app.purchase.shipping-flat-cost:5000}") BigDecimal shippingFlatCost,
			@Value("${app.purchase.completed-points:60}") int completedPoints,
			@Value("${app.purchase.fine-generated-points-penalty:90}") int fineGeneratedPointsPenalty,
			@Value("${app.purchase.fine-expired-points-penalty:250}") int fineExpiredPointsPenalty) {
		this.jdbc = jdbc;
		this.accounts = accounts;
		this.categories = categories;
		this.audit = audit;
		this.realtime = realtime;
		this.mail = mail;
		this.reads = reads;
		this.addresses = addresses;
		this.files = files;
		this.storage = storage;
		this.pdfs = pdfs;
		this.paymentFailureSimulation = paymentFailureSimulation;
		this.shippingFlatCost = shippingFlatCost;
		this.completedPoints = completedPoints;
		this.fineGeneratedPointsPenalty = fineGeneratedPointsPenalty;
		this.fineExpiredPointsPenalty = fineExpiredPointsPenalty;
	}

	@Transactional(readOnly = true)
	public Page<Summary> list(Long accountId, String state, int page, int size) {
		account(accountId);
		checkPage(page, size);
		String normalizedState = normalizedState(state);
		long total = reads.countByAccountId(accountId, normalizedState);
		List<Summary> values = reads.findByAccountId(accountId, normalizedState, page, size);
		return new Page<>(values, page, size, total, (int) Math.ceil((double) total / size));
	}

	@Transactional(readOnly = true)
	public Detail detail(Long accountId, Long purchaseId) {
		return detail(ownedPurchase(accountId, purchaseId, false));
	}

	@Transactional(readOnly = true)
	public List<Document> documents(Long accountId, Long purchaseId) {
		ownedPurchase(accountId, purchaseId, false);
		return reads.findAvailableDocuments(purchaseId).stream().map(file -> document(purchaseId, file)).toList();
	}

	@Transactional(readOnly = true)
	public FileDownload downloadDocument(Long accountId, Long purchaseId, Long documentId) {
		ownedPurchase(accountId, purchaseId, false);
		DocumentFile file = reads.findDocument(purchaseId, documentId);
		if (file == null) throw notFound("Documento inexistente o no disponible");
		if (file.contentBytes() != null && file.contentBytes().length > 0) {
			return new FileDownload(file.filename(), file.contentType(), file.contentBytes());
		}
		if (!storage.exists(file.storagePath())) throw notFound("Documento inexistente o no disponible");
		try (var input = storage.load(file.storagePath())) {
			byte[] content = input.readAllBytes();
			if (content.length == 0) throw notFound("Documento inexistente o no disponible");
			return new FileDownload(file.filename(), file.contentType(), content);
		} catch (IOException | IllegalStateException exception) {
			throw notFound("Documento inexistente o no disponible");
		}
	}

	@Transactional(readOnly = true)
	public PurchaseDtos.DeliveryPreview previewDelivery(Long accountId, Long purchaseId, PurchaseDeliveryRequest request) {
		Purchase purchase = ownedPurchase(accountId, purchaseId, false);
		if (!purchase.state().equals("pagos_extra_pendientes")) {
			throw conflict("La compra no admite configurar entrega", "INVALID_STATE_TRANSITION");
		}
		Long addressId = null;
		BigDecimal cost = BigDecimal.ZERO;
		if (request.tipo().equals("envio")) {
			addressId = shippingAddress(accountId, request.direccionEnvioId());
			cost = shippingFlatCost;
		}
		return new PurchaseDtos.DeliveryPreview(request.tipo(), addressId, cost, purchase.currency(),
				purchase.buyerCommission(), purchase.buyerCommission().add(cost));
	}

	@Transactional
	public PurchaseDtos.Delivery configureDelivery(Long accountId, Long purchaseId, PurchaseDeliveryRequest request) {
		Purchase purchase = ownedPurchase(accountId, purchaseId, true);
		if (!purchase.state().equals("pagos_extra_pendientes")) {
			throw conflict("La compra no admite configurar entrega", "INVALID_STATE_TRANSITION");
		}
		Long addressId = null;
		BigDecimal cost = BigDecimal.ZERO;
		boolean losesCoverage = request.tipo().equals("retiro");
		if (request.tipo().equals("envio")) {
			addressId = shippingAddress(accountId, request.direccionEnvioId());
			cost = shippingFlatCost;
		}
		List<Long> existing = jdbc.query("SELECT id FROM app_entregas WHERE compra_id=?", (rs, row) -> rs.getLong(1),
				purchaseId);
		if (existing.isEmpty()) {
			jdbc.update("""
					INSERT INTO app_entregas(compra_id,tipo,direccion_envio_id,costo_envio,estado,perdio_cobertura_seguro)
					VALUES (?,?,?,?,'pendiente',?)
					""", purchaseId, request.tipo(), addressId, cost, losesCoverage);
		} else {
			jdbc.update("""
					UPDATE app_entregas SET tipo=?,direccion_envio_id=?,costo_envio=?,estado='pendiente',
						perdio_cobertura_seguro=?,updated_at=CURRENT_TIMESTAMP WHERE compra_id=?
					""", request.tipo(), addressId, cost, losesCoverage, purchaseId);
		}
		audit.record(new AuditEvent("usuario", accountId, "compra.entrega_configurada", "compra", purchaseId,
				"{\"tipo\":\"" + request.tipo() + "\"}"));
		return delivery(purchaseId);
	}

	@Transactional
	public Payment payExtras(Long accountId, Long purchaseId, PurchasePaymentRequest request) {
		return payExtras(accountId, purchaseId, request.medioPagoId(), normalizedKey(request.idempotencyKey()),
				PaymentOutcome.AUTO);
	}

	@Transactional
	public Payment payFine(Long accountId, Long purchaseId, PurchasePaymentRequest request) {
		return payFine(accountId, purchaseId, request.medioPagoId(), normalizedKey(request.idempotencyKey()),
				PaymentOutcome.AUTO);
	}

	@Transactional
	public Detail closeLot(Integer auctionId, PaymentOutcome outcome) {
		LiveState live = lockLiveState(auctionId);
		if (live.itemId() == null) {
			Long existing = latestPurchaseForAuction(auctionId);
			if (existing != null) return internalDetail(existing);
			throw conflict("El lote ya fue cerrado", "LOT_ALREADY_CLOSED");
		}
		Auction auction = auction(auctionId);
		if (!auction.state().equals("en_vivo")) throw conflict("La subasta no está en vivo", "AUCTION_NOT_LIVE");
		Item item = item(live.itemId());
		Long existingPurchase = purchaseForItem(item.id());
		if (existingPurchase != null) return internalDetail(existingPurchase);
		WinningBid winning = winningBid(auctionId, item.id());
		Long purchaseId;
		Long buyerAccountId = null;
		Long winningBidId = null;
		BigDecimal amount;
		if (winning == null) {
			amount = item.basePrice();
			Commissions commissions = commissions(item, amount, true);
			purchaseId = insertPurchase(auctionId, item, null, true, null, amount, auction.currency(), null,
					"pagos_extra_pendientes", commissions);
			updateConsignment(item.id(), "comprada_por_empresa");
		} else {
			buyerAccountId = winning.accountId();
			winningBidId = winning.id();
			amount = winning.amount();
			jdbc.update("UPDATE app_pujas_live SET estado='superada' WHERE subasta_id=? AND item_catalogo_id=? AND estado='aceptada' AND id<>?",
					auctionId, item.id(), winning.id());
			jdbc.update("UPDATE app_pujas_live SET estado='ganadora' WHERE id=?", winning.id());
			jdbc.update("UPDATE pujos SET ganador='no' WHERE item=?", item.id());
			jdbc.update("UPDATE pujos SET ganador='si' WHERE identificador=?", winning.legacyBidId());
			Commissions commissions = commissions(item, winning.amount(), false);
			purchaseId = insertPurchase(auctionId, item, winning.accountId(), false, winning.id(), winning.amount(),
					auction.currency(), winning.paymentId(), "adjudicacion_pendiente", commissions);
			registerLegacySale(auctionId, item, winning, commissions.seller());
			updateConsignment(item.id(), "vendida");
			categories.addPoints(account(winning.accountId()), WINNING_BID_POINTS, "puja_ganada", "puja", winning.id());
			automaticAdjudicationPayment(purchaseId, winning, auction.currency(), outcome);
		}
		jdbc.update("UPDATE \"itemsCatalogo\" SET subastado='si' WHERE identificador=?", item.id());
		long nextVersion = live.version() + 1;
		LotSchedule schedule = scheduleAfterLotClose(auctionId, nextVersion);
		notifyOwner(item.id(), purchaseId);
		audit.record(new AuditEvent("sistema", null, "subasta.lote_cerrado", "compra", purchaseId,
				"{\"subastaId\":" + auctionId + ",\"itemId\":" + item.id() + "}"));
		realtime.afterCommit(new LotClosedEvent("LOTE_CERRADO", auctionId, item.id(), purchaseId, winningBidId, amount,
				auction.currency(), winning == null, nextVersion, schedule.nextLotAt(), schedule.closeAuctionAt()),
				buyerAccountId);
		return internalDetail(purchaseId);
	}

	@Transactional
	public void closeAuction(Integer auctionId) {
		LiveState live = lockLiveState(auctionId);
		if (live.itemId() != null) throw conflict("Existe un lote activo", "INVALID_STATE_TRANSITION");
		jdbc.update("UPDATE app_subasta_ext SET estado_operativo='finalizada',updated_at=CURRENT_TIMESTAMP WHERE subasta_id=?",
				auctionId);
		long nextVersion = live.version() + 1;
		jdbc.update("""
				UPDATE app_subasta_estado_vivo
				SET version=?,retencion_hasta=NULL,lote_finaliza_estimado_at=NULL,
					proximo_lote_programado_at=NULL,subasta_finaliza_programado_at=NULL,updated_at=CURRENT_TIMESTAMP
				WHERE subasta_id=?
				""", nextVersion, auctionId);
		audit.record(new AuditEvent("sistema", null, "subasta.cerrada", "subasta", auctionId.longValue(), "{}"));
		realtime.afterCommit(new AuctionLifecycleEvent("SUBASTA_FINALIZADA", auctionId, null, nextVersion));
	}

	@Transactional
	public Payment simulateSuccessfulExtras(Long accountId, Long purchaseId, Long paymentId) {
		return payExtras(accountId, purchaseId, paymentId, "internal-extra-success-" + UUID.randomUUID(),
				PaymentOutcome.SUCCESS);
	}

	@Transactional
	public Payment simulateFailedExtras(Long accountId, Long purchaseId, Long paymentId) {
		return payExtras(accountId, purchaseId, paymentId, "internal-extra-failure-" + UUID.randomUUID(),
				PaymentOutcome.FAILURE);
	}

	@Transactional
	public Payment simulateSuccessfulFine(Long accountId, Long purchaseId, Long paymentId) {
		return payFine(accountId, purchaseId, paymentId, "internal-fine-success-" + UUID.randomUUID(),
				PaymentOutcome.SUCCESS);
	}

	@Transactional
	public Payment simulateFailedFine(Long accountId, Long purchaseId, Long paymentId) {
		return payFine(accountId, purchaseId, paymentId, "internal-fine-failure-" + UUID.randomUUID(),
				PaymentOutcome.FAILURE);
	}

	@Transactional
	public void expireFine(Long fineId, boolean force) {
		FineRow fine = lockFine(fineId);
		if (!fine.state().equals("pendiente")) throw conflict("La multa no está pendiente", "INVALID_STATE_TRANSITION");
		if (!force && fine.expiresAt().isAfter(OffsetDateTime.now())) {
			throw conflict("La multa todavía no venció", "FINE_NOT_EXPIRED");
		}
		jdbc.update("UPDATE app_multas SET estado='vencida' WHERE id=?", fineId);
		CuentaApp account = account(fine.accountId());
		account.changeState("bloqueada_permanente");
		categories.addPoints(account, -fineExpiredPointsPenalty, "multa_vencida", "multa", fineId);
		notify(fine.accountId(), "multa_vencida", "Cuenta bloqueada",
				"La multa venció y la cuenta quedó bloqueada permanentemente.", "multa", fineId);
		audit.record(new AuditEvent("sistema", null, "multa.vencida", "multa", fineId, "{}"));
	}

	@Transactional
	public int expireDueFines() {
		List<Long> due = jdbc.query("""
				SELECT id FROM app_multas WHERE estado='pendiente' AND vence_at<=CURRENT_TIMESTAMP
				ORDER BY vence_at,id FOR UPDATE SKIP LOCKED
				""", (rs, row) -> rs.getLong(1));
		due.forEach(id -> expireFine(id, false));
		return due.size();
	}

	@Transactional
	public void markFinePaid(Long fineId) {
		FineRow fine = lockFine(fineId);
		if (!fine.state().equals("pendiente")) throw conflict("La multa no está pendiente", "INVALID_STATE_TRANSITION");
		jdbc.update("UPDATE app_multas SET estado='pagada',paid_at=CURRENT_TIMESTAMP WHERE id=?", fineId);
		Long purchaseId = jdbc.queryForObject("SELECT compra_id FROM app_multas WHERE id=?", Long.class, fineId);
		jdbc.update("UPDATE app_compras SET estado='pagos_extra_pendientes',updated_at=CURRENT_TIMESTAMP WHERE id=?",
				purchaseId);
		if (pendingFineCount(fine.accountId()) == 0) account(fine.accountId()).changeState("activa");
		createDocument(purchaseId, "recibo_multa", "recibo-multa-" + fineId + ".pdf");
		notify(fine.accountId(), "multa_pagada", "Multa pagada",
				"La restricción por multa fue regularizada manualmente.", "multa", fineId);
		audit.record(new AuditEvent("admin", null, "multa.marcada_pagada", "multa", fineId, "{}"));
	}

	@Transactional
	public void completeDelivery(Long purchaseId) {
		completeDelivery(purchaseId, "envio");
	}

	@Transactional
	public void completePickup(Long purchaseId) {
		completeDelivery(purchaseId, "retiro");
	}

	@Transactional
	public void abandon(Long purchaseId, boolean pickupFailure) {
		Purchase purchase = lockPurchase(purchaseId);
		String state = pickupFailure ? "abandonada_por_incumplimiento_retiro" : "abandonada_por_incumplimiento_pago";
		jdbc.update("UPDATE app_compras SET estado=?,updated_at=CURRENT_TIMESTAMP WHERE id=?", state, purchaseId);
		if (purchase.accountId() != null) {
			notify(purchase.accountId(), "compra_abandonada", "Compra abandonada",
					"La compra fue marcada por incumplimiento.", "compra", purchaseId);
		}
		audit.record(new AuditEvent("sistema", null, "compra.abandonada", "compra", purchaseId,
				"{\"estado\":\"" + state + "\"}"));
	}

	@Transactional
	public int abandonDueExtraPayments() {
		List<Long> due = jdbc.query("""
				SELECT id FROM app_compras
				WHERE estado='pagos_extra_pendientes' AND updated_at<=?
				ORDER BY updated_at,id FOR UPDATE SKIP LOCKED
				""", (rs, row) -> rs.getLong(1), OffsetDateTime.now().minusHours(72));
		due.forEach(id -> abandon(id, false));
		return due.size();
	}

	private void automaticAdjudicationPayment(Long purchaseId, WinningBid winning, String currency,
			PaymentOutcome outcome) {
		PaymentAttempt attempt = adjudicationPaymentAttempt(winning, currency, outcome);
		Payment payment = insertPayment(purchaseId, null, winning.paymentId(), winning.amount(), currency,
				attempt.success() ? "aprobado" : "rechazado", "auto-adjudicacion-" + purchaseId,
				attempt.errorCode(), attempt.errorDetail());
		if (attempt.success()) {
			consumeReservation(winning.id(), winning.paymentId(), winning.amount());
			jdbc.update("UPDATE app_compras SET estado='pagos_extra_pendientes',updated_at=CURRENT_TIMESTAMP WHERE id=?",
					purchaseId);
			categories.addPoints(account(winning.accountId()), completedPoints, "compra_concretada", "compra", purchaseId);
			notify(winning.accountId(), "pago_adjudicacion_exitoso", "Pago de adjudicacion aprobado",
					"El monto adjudicado fue cobrado. Restan comision y entrega.", "compra", purchaseId);
			audit.record(new AuditEvent("sistema", null, "pago.adjudicacion_aprobado", "pago", payment.id(), "{}"));
		} else {
			releaseReservation(winning.id());
			Long fineId = createFine(winning.accountId(), purchaseId, winning.amount(), currency);
			jdbc.update("UPDATE app_compras SET estado='multa_activa',updated_at=CURRENT_TIMESTAMP WHERE id=?", purchaseId);
			CuentaApp account = account(winning.accountId());
			account.changeState("restriccion_multa");
			categories.addPoints(account, -fineGeneratedPointsPenalty, "multa_generada", "multa", fineId);
			notify(winning.accountId(), "multa_generada", "Multa pendiente",
					"El cobro automatico fallo. Debes pagar obligacion y multa dentro de 72 horas.", "multa", fineId);
			audit.record(new AuditEvent("sistema", null, "multa.generada", "multa", fineId,
					"{\"compraId\":" + purchaseId + "}"));
		}
		notify(winning.accountId(), "lote_ganado", "Lote ganado", "Ganaste el lote subastado.", "compra", purchaseId);
	}

	private PaymentAttempt adjudicationPaymentAttempt(WinningBid winning, String currency, PaymentOutcome outcome) {
		boolean validPayment = paymentSucceeds(winning.accountId(), winning.paymentId(), currency, winning.amount(), outcome);
		if (!validPayment) {
			return new PaymentAttempt(false, "INSUFFICIENT_FUNDS_OR_LIMIT",
					"Falla de cobro simulada o fondos insuficientes.");
		}
		if (outcome == PaymentOutcome.AUTO && paymentFailureSimulation.shouldFailAdjudicationPayment()) {
			return new PaymentAttempt(false, "ADJUDICATION_EXTERNAL_PAYMENT_FAILURE",
					"Falla externa simulada durante el cobro automatico de adjudicacion.");
		}
		return new PaymentAttempt(true, null, null);
	}

	private Payment payExtras(Long accountId, Long purchaseId, Long paymentId, String key, PaymentOutcome outcome) {
		Payment replay = replayPayment(accountId, purchaseId, paymentId, key);
		if (replay != null) return replay;
		Purchase purchase = ownedPurchase(accountId, purchaseId, true);
		if (!purchase.state().equals("pagos_extra_pendientes")) {
			throw conflict("La compra no admite pago de extras", "INVALID_STATE_TRANSITION");
		}
		if (activeFine(purchaseId) != null) throw conflict("La compra posee una multa activa", "FINE_PAYMENT_REQUIRED");
		PurchaseDtos.Delivery delivery = delivery(purchaseId);
		if (delivery == null) throw conflict("Primero debés elegir envío o retiro", "DELIVERY_SELECTION_REQUIRED");
		BigDecimal amount = purchase.buyerCommission().add(delivery.costoEnvio());
		boolean success = paymentSucceeds(accountId, paymentId, purchase.currency(), amount, outcome);
		Payment payment = insertPayment(purchaseId, null, paymentId, amount, purchase.currency(),
				success ? "aprobado" : "rechazado", key, success ? null : "INSUFFICIENT_FUNDS_OR_LIMIT");
		if (success) {
			consume(paymentId, amount);
			freezeDeliveryAddress(purchaseId);
			String nextState = delivery.tipo().equals("envio") ? "entrega_pendiente" : "retiro_pendiente";
			jdbc.update("UPDATE app_entregas SET estado='pagada',updated_at=CURRENT_TIMESTAMP WHERE compra_id=?", purchaseId);
			jdbc.update("UPDATE app_compras SET estado=?,updated_at=CURRENT_TIMESTAMP WHERE id=?", nextState, purchaseId);
			categories.addPoints(account(accountId), EXTRAS_PAID_POINTS, "extras_pagados", "compra", purchaseId);
			createDocument(purchaseId, "factura_compra", "factura-compra-" + purchaseId + ".pdf");
			notify(accountId, nextState, nextState.equals("entrega_pendiente") ? "Entrega pendiente" : "Retiro pendiente",
					"El pago de extras fue aprobado.", "compra", purchaseId);
		}
		audit.record(new AuditEvent("usuario", accountId, success ? "pago.extras_aprobado" : "pago.extras_rechazado",
				"pago", payment.id(), "{}"));
		return payment(payment.id(), false);
	}

	private Payment payFine(Long accountId, Long purchaseId, Long paymentId, String key, PaymentOutcome outcome) {
		Payment replay = replayPayment(accountId, purchaseId, paymentId, key);
		if (replay != null) return replay;
		Purchase purchase = ownedPurchase(accountId, purchaseId, true);
		if (!purchase.state().equals("multa_activa")) {
			throw conflict("La compra no posee multa activa", "INVALID_STATE_TRANSITION");
		}
		FineRow fine = activeFine(purchaseId);
		if (fine == null) throw conflict("La multa no está disponible", "INVALID_STATE_TRANSITION");
		BigDecimal amount = purchase.amount().add(fine.amount());
		boolean success = paymentSucceeds(accountId, paymentId, purchase.currency(), amount, outcome);
		Payment payment = insertPayment(purchaseId, fine.id(), paymentId, amount, purchase.currency(),
				success ? "aprobado" : "rechazado", key, success ? null : "INSUFFICIENT_FUNDS_OR_LIMIT");
		if (success) {
			consume(paymentId, amount);
			jdbc.update("UPDATE app_multas SET estado='pagada',paid_at=CURRENT_TIMESTAMP WHERE id=?", fine.id());
			jdbc.update("UPDATE app_compras SET estado='pagos_extra_pendientes',updated_at=CURRENT_TIMESTAMP WHERE id=?",
					purchaseId);
			if (pendingFineCount(accountId) == 0) account(accountId).changeState("activa");
			createDocument(purchaseId, "recibo_multa", "recibo-multa-" + fine.id() + ".pdf");
			notify(accountId, "multa_pagada", "Multa pagada", "La restricción por multa fue regularizada.", "multa",
					fine.id());
			audit.record(new AuditEvent("usuario", accountId, "multa.pagada", "multa", fine.id(), "{}"));
		}
		audit.record(new AuditEvent("usuario", accountId, success ? "pago.multa_aprobado" : "pago.multa_rechazado",
				"pago", payment.id(), "{}"));
		return payment(payment.id(), false);
	}

	private boolean paymentSucceeds(Long accountId, Long paymentId, String currency, BigDecimal amount,
			PaymentOutcome outcome) {
		PaymentMethod method = paymentMethod(accountId, paymentId);
		if (!method.currency().equals(currency)) throw unprocessable("Moneda incompatible", "PAYMENT_METHOD_CURRENCY_MISMATCH");
		if (!method.state().equals("verificado") || method.deletedAt() != null) {
			throw forbidden("El medio no está verificado", "PAYMENT_METHOD_NOT_VERIFIED");
		}
		if (method.verifiedUntil() == null || !method.verifiedUntil().isAfter(OffsetDateTime.now())) {
			throw forbidden("La verificación del medio venció", "PAYMENT_METHOD_VERIFICATION_EXPIRED");
		}
		if (outcome == PaymentOutcome.FAILURE) return false;
		if (outcome == PaymentOutcome.SUCCESS) return true;
		if (method.limit() == null
				|| amount.compareTo(method.limit().subtract(method.consumed()).subtract(activeReservations(paymentId))) > 0) {
			return false;
		}
		return !method.type().equals("cheque_certificado")
				|| method.guarantee() != null && amount.compareTo(method.guarantee()) <= 0;
	}

	private PaymentMethod paymentMethod(Long accountId, Long paymentId) {
		List<PaymentMethod> values = jdbc.query("""
				SELECT cuenta_id,tipo,moneda,estado,limite_monto,consumo_actual,saldo_garantia,verificado_hasta,deleted_at
				FROM app_medios_pago WHERE id=?
				""", (rs, row) -> new PaymentMethod(rs.getLong("cuenta_id"), rs.getString("tipo"), rs.getString("moneda"),
						rs.getString("estado"), rs.getBigDecimal("limite_monto"), rs.getBigDecimal("consumo_actual"),
						rs.getBigDecimal("saldo_garantia"), rs.getObject("verificado_hasta", OffsetDateTime.class),
						rs.getObject("deleted_at", OffsetDateTime.class)), paymentId);
		if (values.isEmpty()) throw notFound("Medio de pago inexistente");
		PaymentMethod method = values.get(0);
		if (!method.accountId().equals(accountId)) throw forbidden("El medio no pertenece al usuario", "RESOURCE_NOT_OWNED");
		return method;
	}

	private Payment replayPayment(Long accountId, Long purchaseId, Long paymentId, String key) {
		List<ExistingPayment> values = jdbc.query("""
				SELECT p.id,c.cuenta_comprador_id FROM app_pagos p
				LEFT JOIN app_compras c ON c.id=p.compra_id WHERE p.idempotency_key=?
				""", (rs, row) -> new ExistingPayment(rs.getLong("id"), (Long) rs.getObject("cuenta_comprador_id")), key);
		if (values.isEmpty()) return null;
		ExistingPayment existing = values.get(0);
		Payment payment = payment(existing.id(), true);
		if (!accountId.equals(existing.accountId()) || !payment.compraId().equals(purchaseId)
				|| !payment.medioPagoId().equals(paymentId)) {
			throw conflict("Clave de idempotencia reutilizada", "IDEMPOTENCY_CONFLICT");
		}
		return payment;
	}

	private Payment insertPayment(Long purchaseId, Long fineId, Long paymentId, BigDecimal amount, String currency,
			String state, String key, String errorCode) {
		return insertPayment(purchaseId, fineId, paymentId, amount, currency, state, key, errorCode,
				errorCode == null ? null : "Falla de cobro simulada o fondos insuficientes.");
	}

	private Payment insertPayment(Long purchaseId, Long fineId, Long paymentId, BigDecimal amount, String currency,
			String state, String key, String errorCode, String errorDetail) {
		long id = insert("""
				INSERT INTO app_pagos(compra_id,multa_id,medio_pago_id,monto,moneda,estado,referencia_externa,
					idempotency_key,error_codigo,error_detalle)
				VALUES (?,?,?,?,?,?,?, ?,?,?)
				""", statement -> {
			statement.setLong(1, purchaseId);
			if (fineId == null) statement.setNull(2, java.sql.Types.BIGINT); else statement.setLong(2, fineId);
			statement.setLong(3, paymentId);
			statement.setBigDecimal(4, amount);
			statement.setString(5, currency);
			statement.setString(6, state);
			statement.setString(7, "SIM-" + UUID.randomUUID());
			statement.setString(8, key);
			statement.setString(9, errorCode);
			statement.setString(10, errorDetail);
		});
		return payment(id, false);
	}

	private Payment payment(Long id, boolean replay) {
		return jdbc.query("""
				SELECT p.id,p.compra_id,p.multa_id,p.medio_pago_id,p.monto,p.moneda,p.estado,p.error_codigo,c.estado compra_estado
				FROM app_pagos p JOIN app_compras c ON c.id=p.compra_id WHERE p.id=?
				""", rs -> {
			if (!rs.next()) throw notFound("Pago inexistente");
			return new Payment(rs.getLong("id"), rs.getLong("compra_id"), (Long) rs.getObject("multa_id"),
					rs.getLong("medio_pago_id"), rs.getBigDecimal("monto"), rs.getString("moneda"),
					rs.getString("estado"), rs.getString("error_codigo"), rs.getString("compra_estado"), replay);
		}, id);
	}

	private Long createFine(Long accountId, Long purchaseId, BigDecimal adjudication, String currency) {
		BigDecimal amount = adjudication.multiply(FINE_RATE).setScale(2, RoundingMode.HALF_UP);
		return insert("""
				INSERT INTO app_multas(cuenta_id,compra_id,monto,moneda,porcentaje,estado,vence_at)
				VALUES (?,?,?,? ,10,'pendiente',?)
				""", statement -> {
			statement.setLong(1, accountId);
			statement.setLong(2, purchaseId);
			statement.setBigDecimal(3, amount);
			statement.setString(4, currency);
			statement.setObject(5, OffsetDateTime.now().plusHours(72));
		});
	}

	private Long insertPurchase(Integer auctionId, Item item, Long accountId, boolean company, Long bidId,
			BigDecimal amount, String currency, Long paymentId, String state, Commissions commissions) {
		return insert("""
				INSERT INTO app_compras(subasta_id,item_catalogo_id,producto_id,cuenta_comprador_id,comprador_empresa,
					puja_id,monto_adjudicacion,moneda,estado,medio_pago_id,comision_comprador,comision_vendedor)
				VALUES (?,?,?,?,?,?,?,?,?,?,?,?)
				""", statement -> {
			statement.setInt(1, auctionId);
			statement.setInt(2, item.id());
			statement.setInt(3, item.productId());
			if (accountId == null) statement.setNull(4, java.sql.Types.BIGINT); else statement.setLong(4, accountId);
			statement.setBoolean(5, company);
			if (bidId == null) statement.setNull(6, java.sql.Types.BIGINT); else statement.setLong(6, bidId);
			statement.setBigDecimal(7, amount);
			statement.setString(8, currency);
			statement.setString(9, state);
			if (paymentId == null) statement.setNull(10, java.sql.Types.BIGINT); else statement.setLong(10, paymentId);
			statement.setBigDecimal(11, commissions.buyer());
			statement.setBigDecimal(12, commissions.seller());
		});
	}

	private void registerLegacySale(Integer auctionId, Item item, WinningBid winning, BigDecimal sellerCommission) {
		List<LegacySale> sales = jdbc.query("""
				SELECT p.duenio,c.cliente_id FROM productos p JOIN app_cuentas c ON c.id=?
				WHERE p.identificador=? AND p.duenio IS NOT NULL
				""", (rs, row) -> new LegacySale(rs.getInt("duenio"), rs.getInt("cliente_id")), winning.accountId(),
				item.productId());
		if (!sales.isEmpty()) {
			LegacySale sale = sales.get(0);
			jdbc.update("""
					INSERT INTO "registroDeSubasta"(subasta,duenio,producto,cliente,importe,comision)
					VALUES (?,?,?,?,?,?)
					""", auctionId, sale.ownerId(), item.productId(), sale.clientId(), winning.amount(), sellerCommission);
		}
	}

	private void consume(Long paymentId, BigDecimal amount) {
		jdbc.update("UPDATE app_medios_pago SET consumo_actual=consumo_actual+?,updated_at=CURRENT_TIMESTAMP WHERE id=?",
				amount, paymentId);
	}

	private void consumeReservation(Long bidId, Long paymentId, BigDecimal amount) {
		List<Reservation> values = jdbc.query("""
				SELECT id,estado FROM app_reservas_medio_pago WHERE puja_id=? FOR UPDATE
				""", (rs, row) -> new Reservation(rs.getLong("id"), rs.getString("estado")), bidId);
		if (values.isEmpty()) {
			consume(paymentId, amount);
			return;
		}
		Reservation reservation = values.get(0);
		if (reservation.state().equals("consumida")) return;
		if (reservation.state().equals("activa")) {
			jdbc.update("""
					UPDATE app_reservas_medio_pago
					SET estado='consumida',consumed_at=CURRENT_TIMESTAMP
					WHERE id=? AND estado='activa'
					""", reservation.id());
			consume(paymentId, amount);
		}
	}

	private void releaseReservation(Long bidId) {
		jdbc.update("""
				UPDATE app_reservas_medio_pago
				SET estado='liberada',released_at=CURRENT_TIMESTAMP
				WHERE puja_id=? AND estado='activa'
				""", bidId);
	}

	private BigDecimal activeReservations(Long paymentId) {
		BigDecimal value = jdbc.queryForObject("""
				SELECT COALESCE(SUM(monto),0) FROM app_reservas_medio_pago
				WHERE medio_pago_id=? AND estado='activa'
				""", BigDecimal.class, paymentId);
		return value == null ? BigDecimal.ZERO : value;
	}

	private LotSchedule scheduleAfterLotClose(Integer auctionId, long nextVersion) {
		OffsetDateTime now = OffsetDateTime.now();
		Integer remaining = jdbc.queryForObject("""
				SELECT COUNT(*) FROM "itemsCatalogo" i
				JOIN catalogos c ON c.identificador=i.catalogo
				WHERE c.subasta=? AND i.subastado='no'
				""", Integer.class, auctionId);
		OffsetDateTime nextLotAt = remaining != null && remaining > 0 ? now.plusSeconds(NEXT_LOT_DELAY_SECONDS) : null;
		OffsetDateTime closeAuctionAt = remaining == null || remaining == 0 ? now.plusSeconds(AUCTION_CLOSE_DELAY_SECONDS) : null;
		jdbc.update("""
				UPDATE app_subasta_estado_vivo
				SET item_catalogo_activo_id=NULL,version=?,retencion_hasta=NULL,lote_finaliza_estimado_at=NULL,
					proximo_lote_programado_at=?,subasta_finaliza_programado_at=?,updated_at=CURRENT_TIMESTAMP
				WHERE subasta_id=?
				""", nextVersion, nextLotAt, closeAuctionAt, auctionId);
		return new LotSchedule(nextLotAt, closeAuctionAt);
	}

	private record LotSchedule(OffsetDateTime nextLotAt, OffsetDateTime closeAuctionAt) {
	}

	private void updateConsignment(Integer itemId, String state) {
		jdbc.update("""
				UPDATE app_solicitudes_consignacion SET estado=?,updated_at=CURRENT_TIMESTAMP WHERE item_catalogo_id=?
				""", state, itemId);
	}

	private void notifyOwner(Integer itemId, Long purchaseId) {
		jdbc.query("SELECT cuenta_id FROM app_solicitudes_consignacion WHERE item_catalogo_id=?", rs -> {
			notify(rs.getLong(1), "lote_cerrado", "Lote cerrado", "El lote consignado fue cerrado.", "compra", purchaseId);
		}, itemId);
	}

	private void notify(Long accountId, String type, String title, String description, String referenceType,
			Long referenceId) {
		jdbc.update("""
				INSERT INTO app_notificaciones(cuenta_id,tipo,titulo,descripcion,referencia_tipo,referencia_id)
				VALUES (?,?,?,?,?,?)
				""", accountId, type, title, description, referenceType, referenceId);
		mail.critical(accountId, type);
	}

	private void completeDelivery(Long purchaseId, String type) {
		Purchase purchase = lockPurchase(purchaseId);
		String requiredState = type.equals("envio") ? "entrega_pendiente" : "retiro_pendiente";
		if (!purchase.state().equals(requiredState)) throw conflict("Estado incompatible", "INVALID_STATE_TRANSITION");
		Integer changed = jdbc.update("""
				UPDATE app_entregas SET estado='completada',updated_at=CURRENT_TIMESTAMP WHERE compra_id=? AND tipo=?
				""", purchaseId, type);
		if (changed == 0) throw conflict("Entrega inexistente", "INVALID_STATE_TRANSITION");
		jdbc.update("UPDATE app_compras SET estado='completada',updated_at=CURRENT_TIMESTAMP WHERE id=?", purchaseId);
		if (purchase.accountId() != null) {
			notify(purchase.accountId(), "compra_completada", "Compra completada",
					"El bien ya esta en posesion del comprador.", "compra", purchaseId);
		}
		audit.record(new AuditEvent("sistema", null, "compra.completada", "compra", purchaseId,
				"{\"tipo\":\"" + type + "\"}"));
	}

	private Long shippingAddress(Long accountId, Long requestedId) {
		return (requestedId == null
				? addresses.findFirstByCuentaIdAndPrincipalTrueAndDeletedAtIsNull(accountId)
				: addresses.findByIdAndCuentaIdAndDeletedAtIsNull(requestedId, accountId))
				.map(com.example.quickbid.quickbid.entity.app.DireccionEnvio::getId)
				.orElseThrow(() -> unprocessable("Dirección de envío requerida", "SHIPPING_ADDRESS_REQUIRED"));
	}

	private void freezeDeliveryAddress(Long purchaseId) {
		PurchaseDtos.Delivery delivery = delivery(purchaseId);
		if (delivery == null || !delivery.tipo().equals("envio") || delivery.direccionEnvioId() == null
				|| delivery.direccionSnapshotJson() != null) {
			return;
		}
		String snapshot = jdbc.query("""
				SELECT alias,destinatario,calle,numero,piso,codigo_postal,localidad,provincia,pais,telefono
				FROM app_direcciones_envio WHERE id=?
				""", rs -> {
			if (!rs.next()) throw unprocessable("Dirección de envío requerida", "SHIPPING_ADDRESS_REQUIRED");
			return "{"
					+ json("alias", rs.getString("alias")) + ","
					+ json("destinatario", rs.getString("destinatario")) + ","
					+ json("calle", rs.getString("calle")) + ","
					+ json("numero", rs.getString("numero")) + ","
					+ json("piso", rs.getString("piso")) + ","
					+ json("codigoPostal", rs.getString("codigo_postal")) + ","
					+ json("localidad", rs.getString("localidad")) + ","
					+ json("provincia", rs.getString("provincia")) + ","
					+ json("pais", rs.getString("pais")) + ","
					+ json("telefono", rs.getString("telefono")) + "}";
		}, delivery.direccionEnvioId());
		jdbc.update("""
				UPDATE app_entregas
				SET direccion_snapshot_json=?,direccion_snapshot_at=CURRENT_TIMESTAMP,updated_at=CURRENT_TIMESTAMP
				WHERE compra_id=?
				""", snapshot, purchaseId);
	}

	private Detail internalDetail(Long purchaseId) {
		return detail(lockPurchase(purchaseId));
	}

	private Detail detail(Purchase purchase) {
		return new Detail(purchase.id(), purchase.auctionId(), purchase.itemId(), purchase.productId(), purchase.bidId(),
				purchase.amount(), purchase.currency(), purchase.state(), purchase.paymentId(), delivery(purchase.id()),
				fine(purchase.id()), purchase.buyerCommission(), purchase.sellerCommission(), purchase.createdAt());
	}

	private PurchaseDtos.Delivery delivery(Long purchaseId) {
		List<PurchaseDtos.Delivery> values = jdbc.query("""
				SELECT id,tipo,direccion_envio_id,costo_envio,estado,perdio_cobertura_seguro,
				       direccion_snapshot_json,direccion_snapshot_at
				FROM app_entregas WHERE compra_id=?
				""", (rs, row) -> new PurchaseDtos.Delivery(rs.getLong("id"), rs.getString("tipo"),
						(Long) rs.getObject("direccion_envio_id"), rs.getBigDecimal("costo_envio"), rs.getString("estado"),
						rs.getBoolean("perdio_cobertura_seguro"), rs.getString("direccion_snapshot_json"),
						rs.getObject("direccion_snapshot_at", OffsetDateTime.class)), purchaseId);
		return values.isEmpty() ? null : values.get(0);
	}

	private Fine fine(Long purchaseId) {
		List<Fine> values = jdbc.query("""
				SELECT id,monto,moneda,estado,vence_at,paid_at FROM app_multas WHERE compra_id=? ORDER BY id DESC LIMIT 1
				""", (rs, row) -> new Fine(rs.getLong("id"), rs.getBigDecimal("monto"), rs.getString("moneda"),
						rs.getString("estado"), rs.getObject("vence_at", OffsetDateTime.class),
						rs.getObject("paid_at", OffsetDateTime.class)), purchaseId);
		return values.isEmpty() ? null : values.get(0);
	}

	private Purchase ownedPurchase(Long accountId, Long purchaseId, boolean lock) {
		Purchase purchase = lock ? lockPurchase(purchaseId) : purchase(purchaseId);
		if (purchase.accountId() == null || !purchase.accountId().equals(accountId)) {
			throw forbidden("La compra no pertenece al usuario", "RESOURCE_NOT_OWNED");
		}
		return purchase;
	}

	private Purchase lockPurchase(Long purchaseId) {
		return purchase(purchaseId, " FOR UPDATE");
	}

	private Purchase purchase(Long purchaseId) {
		return purchase(purchaseId, "");
	}

	private Purchase purchase(Long purchaseId, String suffix) {
		List<Purchase> values = jdbc.query("""
				SELECT id,subasta_id,item_catalogo_id,producto_id,cuenta_comprador_id,comprador_empresa,puja_id,
				       monto_adjudicacion,moneda,estado,medio_pago_id,comision_comprador,comision_vendedor,created_at
				FROM app_compras WHERE id=?
				""" + suffix, (rs, row) -> new Purchase(rs.getLong("id"), rs.getInt("subasta_id"),
						rs.getInt("item_catalogo_id"), rs.getInt("producto_id"), (Long) rs.getObject("cuenta_comprador_id"),
						rs.getBoolean("comprador_empresa"), (Long) rs.getObject("puja_id"),
						rs.getBigDecimal("monto_adjudicacion"), rs.getString("moneda"), rs.getString("estado"),
						(Long) rs.getObject("medio_pago_id"), rs.getBigDecimal("comision_comprador"),
						rs.getBigDecimal("comision_vendedor"), rs.getObject("created_at", OffsetDateTime.class)),
				purchaseId);
		if (values.isEmpty()) throw notFound("Compra inexistente");
		return values.get(0);
	}

	private FineRow activeFine(Long purchaseId) {
		List<FineRow> values = jdbc.query("""
				SELECT id,cuenta_id,monto,estado,vence_at FROM app_multas WHERE compra_id=? AND estado='pendiente'
				ORDER BY id DESC LIMIT 1
				""", (rs, row) -> fineRow(rs), purchaseId);
		return values.isEmpty() ? null : values.get(0);
	}

	private FineRow lockFine(Long fineId) {
		List<FineRow> values = jdbc.query("""
				SELECT id,cuenta_id,monto,estado,vence_at FROM app_multas WHERE id=? FOR UPDATE
				""", (rs, row) -> fineRow(rs), fineId);
		if (values.isEmpty()) throw notFound("Multa inexistente");
		return values.get(0);
	}

	private FineRow fineRow(java.sql.ResultSet rs) throws java.sql.SQLException {
		return new FineRow(rs.getLong("id"), rs.getLong("cuenta_id"), rs.getBigDecimal("monto"), rs.getString("estado"),
				rs.getObject("vence_at", OffsetDateTime.class));
	}

	private int pendingFineCount(Long accountId) {
		return jdbc.queryForObject("SELECT COUNT(*) FROM app_multas WHERE cuenta_id=? AND estado='pendiente'",
				Integer.class, accountId);
	}

	private LiveState lockLiveState(Integer auctionId) {
		List<LiveState> values = jdbc.query("""
				SELECT item_catalogo_activo_id,version FROM app_subasta_estado_vivo WHERE subasta_id=? FOR UPDATE
				""", (rs, row) -> new LiveState((Integer) rs.getObject("item_catalogo_activo_id"), rs.getLong("version")),
				auctionId);
		if (values.isEmpty()) throw notFound("Estado vivo inexistente");
		return values.get(0);
	}

	private Auction auction(Integer id) {
		List<Auction> values = jdbc.query("SELECT moneda,estado_operativo FROM app_subasta_ext WHERE subasta_id=?",
				(rs, row) -> new Auction(rs.getString("moneda"), rs.getString("estado_operativo")), id);
		if (values.isEmpty()) throw notFound("Subasta inexistente");
		return values.get(0);
	}

	private Item item(Integer id) {
		List<Item> values = jdbc.query("""
				SELECT identificador,producto,"precioBase",comision FROM "itemsCatalogo" WHERE identificador=?
				""", (rs, row) -> new Item(rs.getInt("identificador"), rs.getInt("producto"),
						rs.getBigDecimal("precioBase"), rs.getBigDecimal("comision")), id);
		if (values.isEmpty()) throw notFound("Item inexistente");
		return values.get(0);
	}

	private Commissions commissions(Item item, BigDecimal amount, boolean company) {
		BigDecimal buyer = commissionFor(item, amount);
		BigDecimal seller = sellerCommissionFor(item, amount, buyer);
		return new Commissions(company ? BigDecimal.ZERO.setScale(2) : buyer, seller);
	}

	private BigDecimal commissionFor(Item item, BigDecimal amount) {
		if (item.basePrice().signum() == 0) return BigDecimal.ZERO.setScale(2);
		return amount.multiply(item.commission()).divide(item.basePrice(), 2, RoundingMode.HALF_UP);
	}

	private BigDecimal sellerCommissionFor(Item item, BigDecimal amount, BigDecimal legacyFallback) {
		List<BigDecimal> percentages = jdbc.query("""
				SELECT comision_vendedor_pct FROM app_solicitudes_consignacion
				WHERE item_catalogo_id=? AND comision_vendedor_pct IS NOT NULL
				ORDER BY id DESC LIMIT 1
				""", (rs, row) -> rs.getBigDecimal(1), item.id());
		if (percentages.isEmpty()) return legacyFallback;
		return amount.multiply(percentages.get(0)).divide(new BigDecimal("100"), 2, RoundingMode.HALF_UP);
	}

	private void createDocument(Long purchaseId, String type, String filename) {
		Purchase purchase = purchase(purchaseId);
		Integer fineReceiptCount = jdbc.queryForObject("""
				SELECT COUNT(*) FROM app_documentos
				WHERE referencia_tipo='compra' AND referencia_id=? AND tipo='recibo_multa'
				""", Integer.class, purchaseId);
		boolean extrasAfterFine = type.equals("factura_compra") && fineReceiptCount != null && fineReceiptCount > 0;
		List<String> lines = new ArrayList<>();
		lines.add("Documento: " + (extrasAfterFine ? "Comprobante de extras" : purchaseDocumentLabel(type)));
		lines.add("Referencia: COMPRA-" + purchaseId);
		lines.add("Emitido: " + OffsetDateTime.now());
		lines.add("Comprador: " + accountDisplayName(purchase.accountId()));
		lines.add("Item: " + purchase.itemId() + " / Producto: " + purchase.productId());
		lines.add("Estado: " + purchase.state());
		BigDecimal documentTotal = BigDecimal.ZERO;
		if (type.equals("recibo_multa")) {
			lines.add("Artículo: " + purchase.amount() + " " + purchase.currency());
			documentTotal = documentTotal.add(purchase.amount());
			List<BigDecimal> fineAmounts = jdbc.query("""
					SELECT monto FROM app_multas WHERE compra_id=? AND estado='pagada'
					ORDER BY paid_at DESC NULLS LAST,id DESC LIMIT 1
					""", (rs, row) -> rs.getBigDecimal(1), purchaseId);
			BigDecimal fineAmount = fineAmounts.isEmpty() ? BigDecimal.ZERO : fineAmounts.get(0);
			lines.add("Multa: " + fineAmount + " " + purchase.currency());
			documentTotal = documentTotal.add(fineAmount);
		} else {
			if (!extrasAfterFine) {
				lines.add("Monto de adjudicacion: " + purchase.amount() + " " + purchase.currency());
				documentTotal = documentTotal.add(purchase.amount());
			}
			if (purchase.buyerCommission() != null) {
				lines.add("Comision comprador: " + purchase.buyerCommission() + " " + purchase.currency());
				documentTotal = documentTotal.add(purchase.buyerCommission());
			}
		}
		List<DocumentDelivery> deliveries = jdbc.query("""
				SELECT tipo,costo_envio,estado FROM app_entregas WHERE compra_id=?
				""", (rs, row) -> new DocumentDelivery(rs.getString("tipo"), rs.getBigDecimal("costo_envio"),
					rs.getString("estado")), purchaseId);
		if (!deliveries.isEmpty()) {
			DocumentDelivery delivery = deliveries.get(0);
			if (!type.equals("recibo_multa")) {
				lines.add("Entrega: " + delivery.type() + " / Estado: " + delivery.state());
				lines.add("Costo de entrega: " + delivery.cost() + " " + purchase.currency());
				documentTotal = documentTotal.add(delivery.cost());
			}
		}
		lines.add("Total documentado: " + documentTotal + " " + purchase.currency());
		lines.add("Validez: comprobante digital emitido por QuickBid.");
		byte[] content = pdfs.generate("QuickBid", lines);
		String storagePath = "db-generated/" + UUID.randomUUID() + ".pdf";
		String checksum = HexFormat.of().formatHex(digest(content));
		long fileId = files.save(new ArchivoApp(purchase.accountId(), "documento_compra", filename, "application/pdf",
				(long) content.length, storagePath, checksum)).getId();
		jdbc.update("UPDATE app_archivos SET content_bytes=? WHERE id=?", content, fileId);
		jdbc.update("""
				INSERT INTO app_documentos(tipo,referencia_tipo,referencia_id,archivo_id,estado)
				VALUES (?,'compra',?,?,'disponible')
				""", type, purchaseId, fileId);
	}

	private String purchaseDocumentLabel(String type) {
		return switch (type) {
			case "factura_compra" -> "Factura de compra";
			case "recibo_multa" -> "Recibo de multa";
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

	private Document document(Long purchaseId, DocumentFile file) {
		boolean available = file.sizeBytes() > 0
				&& (file.contentBytes() != null && file.contentBytes().length > 0 || storage.exists(file.storagePath()));
		String url = available ? "/api/compras/" + purchaseId + "/documentos/" + file.id() + "/descargar" : null;
		return new Document(file.id(), file.type(), file.state(), file.fileId(), file.filename(), file.contentType(),
				file.sizeBytes(), file.createdAt(), available, url);
	}

	private byte[] digest(byte[] content) {
		try {
			return MessageDigest.getInstance("SHA-256").digest(content);
		} catch (java.security.NoSuchAlgorithmException exception) {
			throw new IllegalStateException("SHA-256 no disponible", exception);
		}
	}

	private String json(String field, String value) {
		if (value == null) return "\"" + field + "\":null";
		return "\"" + field + "\":\"" + value.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
	}

	private String normalizedState(String state) {
		if (state == null || state.isBlank()) return null;
		String value = state.trim().toLowerCase();
		if (!PURCHASE_STATES.contains(value)) throw unprocessable("Estado de compra invalido", "INVALID_FILTER");
		return value;
	}

	private Long purchaseForItem(Integer itemId) {
		List<Long> values = jdbc.query("SELECT id FROM app_compras WHERE item_catalogo_id=? ORDER BY id DESC LIMIT 1",
				(rs, row) -> rs.getLong(1), itemId);
		return values.isEmpty() ? null : values.get(0);
	}

	private Long latestPurchaseForAuction(Integer auctionId) {
		List<Long> values = jdbc.query("SELECT id FROM app_compras WHERE subasta_id=? ORDER BY id DESC LIMIT 1",
				(rs, row) -> rs.getLong(1), auctionId);
		return values.isEmpty() ? null : values.get(0);
	}

	private WinningBid winningBid(Integer auctionId, Integer itemId) {
		List<WinningBid> values = jdbc.query("""
				SELECT id,pujo_legacy_id,cuenta_id,medio_pago_id,monto FROM app_pujas_live
				WHERE subasta_id=? AND item_catalogo_id=? AND estado='aceptada'
				ORDER BY monto DESC,secuencia DESC LIMIT 1
				""", (rs, row) -> new WinningBid(rs.getLong("id"), rs.getInt("pujo_legacy_id"), rs.getLong("cuenta_id"),
						rs.getLong("medio_pago_id"), rs.getBigDecimal("monto")), auctionId, itemId);
		return values.isEmpty() ? null : values.get(0);
	}

	private CuentaApp account(Long accountId) {
		return accounts.findById(accountId).orElseThrow(() -> notFound("Cuenta inexistente"));
	}

	private long insert(String sql, SqlBinder binder) {
		GeneratedKeyHolder keys = new GeneratedKeyHolder();
		jdbc.update(connection -> {
			PreparedStatement statement = connection.prepareStatement(sql, new String[] { "id" });
			binder.bind(statement);
			return statement;
		}, keys);
		return keys.getKey().longValue();
	}

	private String normalizedKey(String key) {
		return key == null || key.isBlank() ? UUID.randomUUID().toString() : key.trim();
	}

	private void checkPage(int page, int size) {
		if (page < 0 || size < 1 || size > 100) throw new BusinessException(HttpStatus.BAD_REQUEST, "Paginación inválida", "INVALID_PAGE");
	}

	private BusinessException forbidden(String message, String code) { return new BusinessException(HttpStatus.FORBIDDEN, message, code); }
	private BusinessException conflict(String message, String code) { return new BusinessException(HttpStatus.CONFLICT, message, code); }
	private BusinessException unprocessable(String message, String code) { return new BusinessException(HttpStatus.UNPROCESSABLE_ENTITY, message, code); }
	private BusinessException notFound(String message) { return new BusinessException(HttpStatus.NOT_FOUND, message, "RESOURCE_NOT_FOUND"); }

	public enum PaymentOutcome {
		AUTO, SUCCESS, FAILURE
	}

	@FunctionalInterface
	private interface SqlBinder {
		void bind(PreparedStatement statement) throws java.sql.SQLException;
	}

	private record LiveState(Integer itemId, Long version) {
	}

	private record Auction(String currency, String state) {
	}

	private record Item(Integer id, Integer productId, BigDecimal basePrice, BigDecimal commission) {
	}

	private record WinningBid(Long id, Integer legacyBidId, Long accountId, Long paymentId, BigDecimal amount) {
	}

	private record LegacySale(Integer ownerId, Integer clientId) {
	}

	private record Purchase(Long id, Integer auctionId, Integer itemId, Integer productId, Long accountId,
			Boolean company, Long bidId, BigDecimal amount, String currency, String state, Long paymentId,
			BigDecimal buyerCommission, BigDecimal sellerCommission, OffsetDateTime createdAt) {
	}

	private record Commissions(BigDecimal buyer, BigDecimal seller) {
	}

	private record DocumentDelivery(String type, BigDecimal cost, String state) {
	}

	private record FineRow(Long id, Long accountId, BigDecimal amount, String state, OffsetDateTime expiresAt) {
	}

	private record PaymentMethod(Long accountId, String type, String currency, String state, BigDecimal limit,
			BigDecimal consumed, BigDecimal guarantee, OffsetDateTime verifiedUntil, OffsetDateTime deletedAt) {
	}

	private record ExistingPayment(Long id, Long accountId) {
	}

	private record Reservation(Long id, String state) {
	}

	private record PaymentAttempt(boolean success, String errorCode, String errorDetail) {
	}
}
