package com.example.quickbid.quickbid.service;

import java.math.BigDecimal;
import java.sql.PreparedStatement;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Set;

import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.example.quickbid.quickbid.dto.request.BidRequest;
import com.example.quickbid.quickbid.dto.response.SubastaDtos.Bid;
import com.example.quickbid.quickbid.entity.app.CuentaApp;
import com.example.quickbid.quickbid.exception.BusinessException;
import com.example.quickbid.quickbid.repository.app.AuctionQueryRepository;
import com.example.quickbid.quickbid.repository.app.CuentaAppRepository;

@Service
public class BidTransactionService {
	private static final Set<String> UNRESTRICTED_CATEGORIES = Set.of("oro", "platino");
	private static final BigDecimal ONE_PERCENT = new BigDecimal("0.01");
	private static final BigDecimal TWENTY_PERCENT = new BigDecimal("0.20");
	private static final BigDecimal ONE_UNIT = BigDecimal.ONE;
	private static final int LOT_IDLE_SECONDS = 60;

	private final JdbcTemplate jdbc;
	private final AuctionQueryRepository auctionQueries;
	private final CuentaAppRepository accounts;
	private final CategoriaService categories;

	public BidTransactionService(JdbcTemplate jdbc, AuctionQueryRepository auctionQueries,
			CuentaAppRepository accounts, CategoriaService categories) {
		this.jdbc = jdbc;
		this.auctionQueries = auctionQueries;
		this.accounts = accounts;
		this.categories = categories;
	}

	@Transactional
	public AcceptedBid bid(Long accountId, Integer auctionId, BidRequest request) {
		String idempotencyKey = normalizedKey(request.idempotencyKey());
		Bid replay = replay(accountId, auctionId, request, idempotencyKey);
		if (replay != null) return new AcceptedBid(replay, null, true);

		Account account = account(accountId);
		if (!account.state().equals("activa")) {
			throw forbidden("La cuenta no puede pujar", "ACCOUNT_RESTRICTED_BY_FINE");
		}

		LiveState live = lockLiveState(auctionId);
		replay = replay(accountId, auctionId, request, idempotencyKey);
		if (replay != null) return new AcceptedBid(replay, null, true);

		Auction auction = auction(auctionId);
		if (!auction.state().equals("en_vivo")) throw conflict("La subasta no está en vivo", "AUCTION_NOT_LIVE");
		if (!live.version().equals(request.clientStateVersion())) throw conflict("El estado de la subasta cambió", "BID_OUTDATED_STATE");
		Item item = item(accountId, auctionId, request.itemCatalogoId());
		if (live.itemId() == null || !live.itemId().equals(request.itemCatalogoId())) throw conflict("El lote ya no está activo", "ITEM_NOT_ACTIVE");
		if (categoryOrder(account.category()) == 0 || categoryOrder(auction.category()) == 0) throw unprocessable("Categoría inválida", "INVALID_CATEGORY");
		if (categoryOrder(account.category()) < categoryOrder(auction.category())) throw forbidden("Categoría insuficiente", "AUCTION_CATEGORY_FORBIDDEN");

		PriorBid prior = bestBid(auctionId, request.itemCatalogoId());
		Payment payment = payment(accountId, request.medioPagoId());
		Long reservationToReplace = prior != null && prior.accountId().equals(accountId)
				&& prior.paymentId().equals(request.medioPagoId()) ? prior.id() : null;
		validatePayment(payment, auction.currency(), request.monto(), reservationToReplace);
		if (participatesInOtherAuction(accountId, auctionId)) throw conflict("El usuario participa en otra subasta", "OTHER_AUCTION_PARTICIPATION");
		validateAmount(auction.category(), request.monto(), item.basePrice(), prior == null ? null : prior.amount());

		int assistantId = assistant(account.clientId(), auctionId);
		int bidderNumber = bidderNumber(assistantId);
		int legacyBidId = insertLegacyBid(assistantId, request.itemCatalogoId(), request.monto());
		long sequence = nextSequence(auctionId, request.itemCatalogoId());
		long nextVersion = live.version() + 1;
		OffsetDateTime retentionUntil = OffsetDateTime.now().plusSeconds(LOT_IDLE_SECONDS);
		if (prior != null) {
			jdbc.update("UPDATE app_pujas_live SET estado='superada' WHERE id=? AND estado='aceptada'", prior.id());
			releaseReservation(prior.id());
		}
		long id = insertBid(legacyBidId, auctionId, request, accountId, auction.currency(), sequence, nextVersion,
				idempotencyKey);
		reservePayment(request.medioPagoId(), id, request.monto());
		jdbc.update("""
				UPDATE app_subasta_estado_vivo SET version=?,retencion_hasta=?,lote_finaliza_estimado_at=?,
					updated_at=CURRENT_TIMESTAMP
				WHERE subasta_id=?
				""", nextVersion, retentionUntil, retentionUntil, auctionId);
		categories.addPoints(accountEntity(accountId), 1, "puja_aceptada", "puja", id);
		insertNotification(accountId, "puja_aceptada", "Puja aceptada", "Tu puja es la mejor oferta.", id);
		if (prior != null && !prior.accountId().equals(accountId)) {
			insertNotification(prior.accountId(), "puja_superada", "Puja superada",
					"Otra oferta supero tu puja.", prior.id());
		}
		jdbc.update("""
				INSERT INTO app_auditoria(actor_tipo,actor_id,accion,entidad_tipo,entidad_id,metadata_json)
				VALUES ('usuario',?,'subasta.puja_aceptada','puja',?,?)
				""", accountId, id, "{\"subastaId\":" + auctionId + ",\"version\":" + nextVersion + "}");
		Bid accepted = new Bid(id, auctionId, request.itemCatalogoId(), "aceptada", request.monto(), auction.currency(),
				sequence, nextVersion, request.monto(), bidderNumber, false, retentionUntil);
		return new AcceptedBid(accepted, prior == null || prior.accountId().equals(accountId) ? null : prior.accountId(),
				false);
	}

	private Bid replay(Long accountId, Integer auctionId, BidRequest request, String key) {
		List<ExistingBid> values = jdbc.query("""
				SELECT p.id,p.subasta_id,p.item_catalogo_id,p.cuenta_id,p.medio_pago_id,p.monto,p.moneda,p.estado,
				       p.secuencia,p.version_estado,a."numeroPostor"
				FROM app_pujas_live p
				LEFT JOIN asistentes a ON a.cliente=(SELECT c.cliente_id FROM app_cuentas c WHERE c.id=p.cuenta_id)
				 AND a.subasta=p.subasta_id
				WHERE p.idempotency_key=?
				""", (rs, row) -> new ExistingBid(rs.getLong("id"), rs.getInt("subasta_id"),
						rs.getInt("item_catalogo_id"), rs.getLong("cuenta_id"), rs.getLong("medio_pago_id"),
						rs.getBigDecimal("monto"), rs.getString("moneda"), rs.getString("estado"),
						rs.getLong("secuencia"), rs.getLong("version_estado"), (Integer) rs.getObject("numeroPostor")),
				key);
		if (values.isEmpty()) return null;
		ExistingBid value = values.get(0);
		if (!value.accountId().equals(accountId) || !value.auctionId().equals(auctionId)
				|| !value.itemId().equals(request.itemCatalogoId()) || !value.paymentId().equals(request.medioPagoId())
				|| value.amount().compareTo(request.monto()) != 0) {
			throw conflict("La clave de idempotencia ya fue utilizada", "IDEMPOTENCY_CONFLICT");
		}
		OffsetDateTime retentionUntil = jdbc.queryForObject(
				"SELECT retencion_hasta FROM app_subasta_estado_vivo WHERE subasta_id=?",
				OffsetDateTime.class, auctionId);
		return new Bid(value.id(), value.auctionId(), value.itemId(), value.state(), value.amount(), value.currency(),
				value.sequence(), value.version(), value.amount(), value.bidderNumber(), true, retentionUntil);
	}

	private LiveState lockLiveState(Integer auctionId) {
		List<LiveState> values = jdbc.query("""
				SELECT item_catalogo_activo_id,version FROM app_subasta_estado_vivo
				WHERE subasta_id=? FOR UPDATE
				""", (rs, row) -> new LiveState((Integer) rs.getObject("item_catalogo_activo_id"),
						rs.getLong("version")), auctionId);
		if (values.isEmpty()) throw notFound("Estado vivo inexistente");
		return values.get(0);
	}

	private Account account(Long id) {
		List<Account> values = jdbc.query("SELECT cliente_id,estado,categoria_calculada FROM app_cuentas WHERE id=?",
				(rs, row) -> new Account(rs.getInt("cliente_id"), rs.getString("estado"),
						rs.getString("categoria_calculada")), id);
		if (values.isEmpty()) throw notFound("Cuenta inexistente");
		return values.get(0);
	}

	private CuentaApp accountEntity(Long id) {
		return accounts.findById(id).orElseThrow(() -> notFound("Cuenta inexistente"));
	}

	private Auction auction(Integer id) {
		List<Auction> values = jdbc.query("""
				SELECT s.categoria,e.moneda,e.estado_operativo FROM subastas s
				JOIN app_subasta_ext e ON e.subasta_id=s.identificador WHERE s.identificador=?
				""", (rs, row) -> new Auction(rs.getString("categoria"), rs.getString("moneda"),
						rs.getString("estado_operativo")), id);
		if (values.isEmpty()) throw notFound("Subasta inexistente");
		return values.get(0);
	}

	private Payment payment(Long accountId, Long paymentId) {
		List<Payment> values = jdbc.query("""
				SELECT id,cuenta_id,tipo,moneda,estado,limite_monto,consumo_actual,saldo_garantia,verificado_hasta,deleted_at
				FROM app_medios_pago WHERE id=?
				""", (rs, row) -> new Payment(rs.getLong("id"), rs.getLong("cuenta_id"), rs.getString("tipo"), rs.getString("moneda"),
						rs.getString("estado"), rs.getBigDecimal("limite_monto"), rs.getBigDecimal("consumo_actual"),
						rs.getBigDecimal("saldo_garantia"), rs.getObject("verificado_hasta", OffsetDateTime.class),
						rs.getObject("deleted_at", OffsetDateTime.class)), paymentId);
		if (values.isEmpty()) throw notFound("Medio de pago inexistente");
		Payment payment = values.get(0);
		if (!payment.accountId().equals(accountId)) throw forbidden("El medio no pertenece al usuario", "RESOURCE_NOT_OWNED");
		return payment;
	}

	private void validatePayment(Payment payment, String currency, BigDecimal amount, Long ignoredReservationBidId) {
		if (payment.deletedAt() != null) throw forbidden("El medio de pago fue eliminado", "PAYMENT_METHOD_NOT_VERIFIED");
		if (!payment.currency().equals(currency)) throw unprocessable("Moneda incompatible", "PAYMENT_METHOD_CURRENCY_MISMATCH");
		if (!payment.state().equals("verificado")) throw forbidden("El medio de pago no está verificado", "PAYMENT_METHOD_NOT_VERIFIED");
		if (payment.verifiedUntil() == null || !payment.verifiedUntil().isAfter(OffsetDateTime.now())) {
			throw forbidden("La verificación del medio de pago venció", "PAYMENT_METHOD_VERIFICATION_EXPIRED");
		}
		BigDecimal reserved = activeReservations(payment.id(), ignoredReservationBidId);
		if (payment.limit() == null || amount.compareTo(payment.limit().subtract(payment.consumed()).subtract(reserved)) > 0) {
			throw unprocessable("Fondos insuficientes", "PAYMENT_METHOD_INSUFFICIENT_FUNDS");
		}
		if (payment.type().equals("cheque_certificado")
				&& (payment.guarantee() == null || amount.compareTo(payment.guarantee()) > 0)) {
			throw unprocessable("Garantia insuficiente", "PAYMENT_METHOD_INSUFFICIENT_FUNDS");
		}
	}

	private boolean participatesInOtherAuction(Long accountId, Integer auctionId) {
		return auctionQueries.existsParticipationInOtherLiveAuction(accountId, auctionId);
	}

	private Item item(Long accountId, Integer auctionId, Integer itemId) {
		List<Item> values = jdbc.query("""
				SELECT i."precioBase",c.subasta,
				       (SELECT s.cuenta_id FROM app_solicitudes_consignacion s
				        WHERE s.item_catalogo_id=i.identificador
				        ORDER BY s.id LIMIT 1) cuenta_consignadora_id
				FROM "itemsCatalogo" i JOIN catalogos c ON c.identificador=i.catalogo
				WHERE i.identificador=?
				""", (rs, row) -> new Item(rs.getBigDecimal("precioBase"), rs.getInt("subasta"),
						(Long) rs.getObject("cuenta_consignadora_id")), itemId);
		if (values.isEmpty()) throw notFound("Item inexistente");
		Item item = values.get(0);
		if (!item.auctionId().equals(auctionId)) throw notFound("Item inexistente");
		if (item.ownerAccountId() != null && item.ownerAccountId().equals(accountId)) {
			throw forbidden("No puede pujar por un bien propio", "OWN_ITEM_BID_FORBIDDEN");
		}
		return item;
	}

	private PriorBid bestBid(Integer auctionId, Integer itemId) {
		List<PriorBid> values = jdbc.query("""
				SELECT id,cuenta_id,medio_pago_id,monto FROM app_pujas_live
				WHERE subasta_id=? AND item_catalogo_id=? AND estado IN ('aceptada','ganadora')
				ORDER BY monto DESC,secuencia DESC LIMIT 1
				""", (rs, row) -> new PriorBid(rs.getLong("id"), rs.getLong("cuenta_id"),
						rs.getLong("medio_pago_id"), rs.getBigDecimal("monto")),
				auctionId, itemId);
		return values.isEmpty() ? null : values.get(0);
	}

	private BigDecimal activeReservations(Long paymentId, Long ignoredBidId) {
		String ignoredClause = ignoredBidId == null ? "" : " AND puja_id<>?";
		Object[] args = ignoredBidId == null ? new Object[] { paymentId } : new Object[] { paymentId, ignoredBidId };
		BigDecimal value = jdbc.queryForObject("""
				SELECT COALESCE(SUM(monto),0) FROM app_reservas_medio_pago
				WHERE medio_pago_id=? AND estado='activa'
				""" + ignoredClause, BigDecimal.class, args);
		return value == null ? BigDecimal.ZERO : value;
	}

	private void releaseReservation(Long bidId) {
		jdbc.update("""
				UPDATE app_reservas_medio_pago
				SET estado='liberada',released_at=CURRENT_TIMESTAMP
				WHERE puja_id=? AND estado='activa'
				""", bidId);
	}

	private void reservePayment(Long paymentId, Long bidId, BigDecimal amount) {
		jdbc.update("""
				INSERT INTO app_reservas_medio_pago(medio_pago_id,puja_id,monto,estado)
				VALUES (?, ?, ?, 'activa')
				""", paymentId, bidId, amount);
	}

	private void validateAmount(String category, BigDecimal amount, BigDecimal base, BigDecimal previous) {
		BigDecimal minimum = previous == null ? base
				: previous.add(UNRESTRICTED_CATEGORIES.contains(category) ? ONE_UNIT : base.multiply(ONE_PERCENT));
		if (amount.compareTo(minimum) < 0) throw unprocessable("Monto menor al minimo permitido", "BID_AMOUNT_BELOW_MINIMUM");
		if (!UNRESTRICTED_CATEGORIES.contains(category)) {
			BigDecimal maximum = (previous == null ? base : previous).add(base.multiply(TWENTY_PERCENT));
			if (amount.compareTo(maximum) > 0) throw unprocessable("Monto mayor al máximo permitido", "BID_AMOUNT_ABOVE_MAXIMUM");
		}
	}

	private int assistant(Integer clientId, Integer auctionId) {
		List<Integer> values = jdbc.query("SELECT identificador FROM asistentes WHERE cliente=? AND subasta=? ORDER BY identificador LIMIT 1",
				(rs, row) -> rs.getInt(1), clientId, auctionId);
		if (!values.isEmpty()) return values.get(0);
		Integer next = jdbc.queryForObject("SELECT COALESCE(MAX(\"numeroPostor\"),0)+1 FROM asistentes WHERE subasta=?",
				Integer.class, auctionId);
		KeyHolder keys = new GeneratedKeyHolder();
		jdbc.update(connection -> {
			PreparedStatement statement = connection.prepareStatement(
					"INSERT INTO asistentes(\"numeroPostor\",cliente,subasta) VALUES (?,?,?)",
					new String[] { "identificador" });
			statement.setInt(1, next == null ? 1 : next);
			statement.setInt(2, clientId);
			statement.setInt(3, auctionId);
			return statement;
		}, keys);
		return keys.getKey().intValue();
	}

	private int bidderNumber(int assistantId) {
		return jdbc.queryForObject("SELECT \"numeroPostor\" FROM asistentes WHERE identificador=?", Integer.class,
				assistantId);
	}

	private int insertLegacyBid(int assistantId, Integer itemId, BigDecimal amount) {
		KeyHolder keys = new GeneratedKeyHolder();
		jdbc.update(connection -> {
			PreparedStatement statement = connection.prepareStatement(
					"INSERT INTO pujos(asistente,item,importe,ganador) VALUES (?,?,?,'no')",
					new String[] { "identificador" });
			statement.setInt(1, assistantId);
			statement.setInt(2, itemId);
			statement.setBigDecimal(3, amount);
			return statement;
		}, keys);
		return keys.getKey().intValue();
	}

	private long nextSequence(Integer auctionId, Integer itemId) {
		Long next = jdbc.queryForObject("""
				SELECT COALESCE(MAX(secuencia),0)+1 FROM app_pujas_live WHERE subasta_id=? AND item_catalogo_id=?
				""", Long.class, auctionId, itemId);
		return next == null ? 1 : next;
	}

	private long insertBid(int legacyBidId, Integer auctionId, BidRequest request, Long accountId, String currency,
			long sequence, long version, String idempotencyKey) {
		KeyHolder keys = new GeneratedKeyHolder();
		jdbc.update(connection -> {
			PreparedStatement statement = connection.prepareStatement("""
					INSERT INTO app_pujas_live(pujo_legacy_id,subasta_id,item_catalogo_id,cuenta_id,medio_pago_id,monto,
						moneda,estado,secuencia,version_estado,idempotency_key,confirmed_at)
					VALUES (?,?,?,?,?,?,?,'aceptada',?,?,?,CURRENT_TIMESTAMP)
					""",
					new String[] { "id" });
			statement.setInt(1, legacyBidId);
			statement.setInt(2, auctionId);
			statement.setInt(3, request.itemCatalogoId());
			statement.setLong(4, accountId);
			statement.setLong(5, request.medioPagoId());
			statement.setBigDecimal(6, request.monto());
			statement.setString(7, currency);
			statement.setLong(8, sequence);
			statement.setLong(9, version);
			statement.setString(10, idempotencyKey);
			return statement;
		}, keys);
		return keys.getKey().longValue();
	}

	private void insertNotification(Long accountId, String type, String title, String description, Long bidId) {
		jdbc.update("""
				INSERT INTO app_notificaciones(cuenta_id,tipo,titulo,descripcion,referencia_tipo,referencia_id)
				VALUES (?,?,?,?,'puja',?)
				""", accountId, type, title, description, bidId);
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

	private String normalizedKey(String value) {
		if (value == null || value.isBlank()) throw bad("idempotencyKey es obligatorio", "IDEMPOTENCY_KEY_REQUIRED");
		return value.trim();
	}

	private BusinessException bad(String message, String code) { return new BusinessException(HttpStatus.BAD_REQUEST, message, code); }
	private BusinessException forbidden(String message, String code) { return new BusinessException(HttpStatus.FORBIDDEN, message, code); }
	private BusinessException conflict(String message, String code) { return new BusinessException(HttpStatus.CONFLICT, message, code); }
	private BusinessException unprocessable(String message, String code) { return new BusinessException(HttpStatus.UNPROCESSABLE_ENTITY, message, code); }
	private BusinessException notFound(String message) { return new BusinessException(HttpStatus.NOT_FOUND, message, "RESOURCE_NOT_FOUND"); }

	public record AcceptedBid(Bid response, Long surpassedAccountId, boolean replay) {
	}

	private record LiveState(Integer itemId, Long version) {
	}

	private record Account(Integer clientId, String state, String category) {
	}

	private record Auction(String category, String currency, String state) {
	}

	private record Payment(Long id, Long accountId, String type, String currency, String state, BigDecimal limit,
			BigDecimal consumed, BigDecimal guarantee, OffsetDateTime verifiedUntil, OffsetDateTime deletedAt) {
	}

	private record Item(BigDecimal basePrice, Integer auctionId, Long ownerAccountId) {
	}

	private record PriorBid(Long id, Long accountId, Long paymentId, BigDecimal amount) {
	}

	private record ExistingBid(Long id, Integer auctionId, Integer itemId, Long accountId, Long paymentId,
			BigDecimal amount, String currency, String state, Long sequence, Long version, Integer bidderNumber) {
	}
}
