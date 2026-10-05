package com.example.quickbid.quickbid.service;

import java.time.OffsetDateTime;
import java.util.List;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.example.quickbid.quickbid.audit.AuditEvent;
import com.example.quickbid.quickbid.audit.AuditService;
import com.example.quickbid.quickbid.service.PurchaseService.PaymentOutcome;
import com.example.quickbid.quickbid.dto.response.SubastaDtos.AuctionLifecycleEvent;
import com.example.quickbid.quickbid.websocket.PurchaseRealtimePublisher;

@Service
public class AuctionTimerService {
	private static final int LOT_IDLE_SECONDS = 60;

	private final JdbcTemplate jdbc;
	private final PurchaseService purchases;
	private final PurchaseRealtimePublisher realtime;
	private final AuditService audit;
	private final MailNotificationService mail;
	private final boolean demoAutoOpenAuctionsEnabled;
	private final boolean demoFirstLotWaitsForFirstBid;

	public AuctionTimerService(JdbcTemplate jdbc, PurchaseService purchases, PurchaseRealtimePublisher realtime,
			AuditService audit, MailNotificationService mail,
			@Value("${app.demo.auto-open-auctions-enabled:false}") boolean demoAutoOpenAuctionsEnabled,
			@Value("${app.demo.first-lot-waits-for-first-bid:false}") boolean demoFirstLotWaitsForFirstBid) {
		this.jdbc = jdbc;
		this.purchases = purchases;
		this.realtime = realtime;
		this.audit = audit;
		this.mail = mail;
		this.demoAutoOpenAuctionsEnabled = demoAutoOpenAuctionsEnabled;
		this.demoFirstLotWaitsForFirstBid = demoFirstLotWaitsForFirstBid;
	}

	@Transactional
	public TimerResult processDueTimers() {
		int openedAuctions = 0;
		if (demoAutoOpenAuctionsEnabled) {
			for (Integer auctionId : dueDemoAuctionsToOpen()) {
				openDemoAuction(auctionId);
				openedAuctions++;
			}
		}
		int closedLots = 0;
		for (Integer auctionId : dueLots()) {
			purchases.closeLot(auctionId, PaymentOutcome.AUTO);
			closedLots++;
		}
		int activatedLots = 0;
		for (Integer auctionId : dueNextLots()) {
			Integer nextItem = nextPendingItem(auctionId);
			if (nextItem != null) {
				activateItem(auctionId, nextItem);
				activatedLots++;
			}
		}
		int finalizedAuctions = 0;
		for (Integer auctionId : dueAuctions()) {
			purchases.closeAuction(auctionId);
			finalizedAuctions++;
		}
		return new TimerResult(closedLots, activatedLots, finalizedAuctions, openedAuctions);
	}

	private List<Integer> dueDemoAuctionsToOpen() {
		return jdbc.query("""
				SELECT s.identificador FROM subastas s
				JOIN app_subasta_ext e ON e.subasta_id=s.identificador
				WHERE s.identificador IN (6011)
				  AND e.estado_operativo IN ('programada','abierta')
				  AND (s.fecha < CURRENT_DATE OR (s.fecha = CURRENT_DATE AND s.hora <= CURRENT_TIME))
				ORDER BY s.fecha,s.hora,s.identificador
				""", (rs, row) -> rs.getInt(1));
	}

	private void openDemoAuction(Integer auctionId) {
		int changed = jdbc.update("""
				UPDATE app_subasta_ext SET estado_operativo='en_vivo',updated_at=CURRENT_TIMESTAMP
				WHERE subasta_id=? AND estado_operativo IN ('programada','abierta')
				""", auctionId);
		if (changed == 0) return;
		Long version = startLiveLifecycle(auctionId);
		jdbc.query("""
				SELECT DISTINCT cuenta_id FROM app_inscripciones_subasta
				WHERE subasta_id=? AND estado NOT IN ('rechazada','expirada')
				""", rs -> {
			Long accountId = rs.getLong(1);
			jdbc.update("""
					INSERT INTO app_notificaciones(cuenta_id,tipo,titulo,descripcion,referencia_tipo,referencia_id)
					VALUES (?,'subasta_inscripta_proxima_inicio','Subasta disponible en vivo',
						'Una subasta en la que manifestaste interés ya está disponible en vivo.','subasta',?)
					""", accountId, auctionId);
			mail.critical(accountId, "subasta_inscripta_proxima_inicio");
		}, auctionId);
		audit.record(new AuditEvent("sistema", null, "subasta.demo_auto_abierta", "subasta",
				auctionId.longValue(), "{}"));
		realtime.afterCommit(new AuctionLifecycleEvent("SUBASTA_INICIADA", auctionId, null, version));
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
		return jdbc.queryForObject("SELECT version FROM app_subasta_estado_vivo WHERE subasta_id=?",
				Long.class, auctionId);
	}

	private List<Integer> dueLots() {
		return jdbc.query("""
				SELECT v.subasta_id FROM app_subasta_estado_vivo v
				JOIN app_subasta_ext e ON e.subasta_id=v.subasta_id
				WHERE e.estado_operativo='en_vivo'
				  AND v.item_catalogo_activo_id IS NOT NULL
				  AND v.lote_finaliza_estimado_at IS NOT NULL
				  AND v.lote_finaliza_estimado_at<=CURRENT_TIMESTAMP
				ORDER BY v.lote_finaliza_estimado_at,v.subasta_id
				""", (rs, row) -> rs.getInt(1));
	}

	private List<Integer> dueNextLots() {
		return jdbc.query("""
				SELECT v.subasta_id FROM app_subasta_estado_vivo v
				JOIN app_subasta_ext e ON e.subasta_id=v.subasta_id
				WHERE e.estado_operativo='en_vivo'
				  AND v.item_catalogo_activo_id IS NULL
				  AND v.proximo_lote_programado_at IS NOT NULL
				  AND v.proximo_lote_programado_at<=CURRENT_TIMESTAMP
				ORDER BY v.proximo_lote_programado_at,v.subasta_id
				""", (rs, row) -> rs.getInt(1));
	}

	private List<Integer> dueAuctions() {
		return jdbc.query("""
				SELECT v.subasta_id FROM app_subasta_estado_vivo v
				JOIN app_subasta_ext e ON e.subasta_id=v.subasta_id
				WHERE e.estado_operativo='en_vivo'
				  AND v.item_catalogo_activo_id IS NULL
				  AND v.subasta_finaliza_programado_at IS NOT NULL
				  AND v.subasta_finaliza_programado_at<=CURRENT_TIMESTAMP
				ORDER BY v.subasta_finaliza_programado_at,v.subasta_id
				""", (rs, row) -> rs.getInt(1));
	}

	private Integer nextPendingItem(Integer auctionId) {
		List<Integer> values = jdbc.query("""
				SELECT i.identificador FROM "itemsCatalogo" i
				JOIN catalogos c ON c.identificador=i.catalogo
				WHERE c.subasta=? AND i.subastado='no'
				ORDER BY i.identificador
				LIMIT 1
				""", (rs, row) -> rs.getInt(1), auctionId);
		return values.isEmpty() ? null : values.get(0);
	}

	private void activateItem(Integer auctionId, Integer itemId) {
		OffsetDateTime lotDeadline = shouldWaitForFirstDemoBid(auctionId, itemId)
				? null
				: OffsetDateTime.now().plusSeconds(LOT_IDLE_SECONDS);
		jdbc.update("""
				UPDATE app_subasta_estado_vivo
				SET item_catalogo_activo_id=?,version=version+1,lote_iniciado_at=CURRENT_TIMESTAMP,
					retencion_hasta=NULL,lote_finaliza_estimado_at=?,proximo_lote_programado_at=NULL,
					subasta_finaliza_programado_at=NULL,updated_at=CURRENT_TIMESTAMP
				WHERE subasta_id=?
				""", itemId, lotDeadline, auctionId);
		Long version = jdbc.queryForObject("SELECT version FROM app_subasta_estado_vivo WHERE subasta_id=?",
				Long.class, auctionId);
		realtime.afterCommit(new AuctionLifecycleEvent("LOTE_ACTIVADO", auctionId, itemId, version, lotDeadline));
	}

	private boolean shouldWaitForFirstDemoBid(Integer auctionId, Integer itemId) {
		return demoFirstLotWaitsForFirstBid && auctionId == 6011 && itemId != null
				&& itemId.equals(firstPendingDemoItem(auctionId))
				&& count("SELECT COUNT(*) FROM app_pujas_live WHERE subasta_id=?", auctionId) == 0;
	}

	private Integer firstPendingDemoItem(Integer auctionId) {
		List<Integer> values = jdbc.query("""
				SELECT i.identificador FROM "itemsCatalogo" i
				JOIN catalogos c ON c.identificador=i.catalogo
				WHERE c.subasta=? AND i.identificador IN (9011,9012,9013)
				ORDER BY i.identificador LIMIT 1
				""", (rs, row) -> rs.getInt(1), auctionId);
		return values.isEmpty() ? null : values.get(0);
	}

	private int count(String sql, Object... args) {
		Integer value = jdbc.queryForObject(sql, Integer.class, args);
		return value == null ? 0 : value;
	}

	public record TimerResult(int lotesCerrados, int lotesActivados, int subastasFinalizadas,
			int subastasDemoAbiertas) {
	}
}
