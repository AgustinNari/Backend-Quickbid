package com.example.quickbid.quickbid.repository.app;

import java.util.List;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class AuctionQueryRepository {
	private final JdbcTemplate jdbc;

	public AuctionQueryRepository(JdbcTemplate jdbc) {
		this.jdbc = jdbc;
	}

	public boolean existsAuction(Integer auctionId) {
		return count("""
				SELECT COUNT(*) FROM subastas s
				JOIN app_subasta_ext e ON e.subasta_id=s.identificador
				WHERE s.identificador=?
				""", auctionId) > 0;
	}

	public boolean existsAuctionItem(Integer auctionId, Integer itemId) {
		return count("""
				SELECT COUNT(*) FROM "itemsCatalogo" i
				JOIN catalogos c ON c.identificador=i.catalogo
				WHERE i.identificador=? AND c.subasta=?
				""", itemId, auctionId) > 0;
	}

	public boolean existsCatalogForAuction(Integer auctionId, Integer catalogId) {
		return count("SELECT COUNT(*) FROM catalogos WHERE identificador=? AND subasta=?", catalogId, auctionId) > 0;
	}

	public boolean existsProduct(Integer productId) {
		return count("SELECT COUNT(*) FROM productos WHERE identificador=?", productId) > 0;
	}

	public boolean isNotLive(Integer auctionId) {
		return count("SELECT COUNT(*) FROM app_subasta_ext WHERE subasta_id=? AND estado_operativo<>'en_vivo'",
				auctionId) > 0;
	}

	public Integer findActiveItem(Integer auctionId) {
		List<Integer> values = jdbc.query("SELECT item_catalogo_activo_id FROM app_subasta_estado_vivo WHERE subasta_id=?",
				(rs, row) -> (Integer) rs.getObject(1), auctionId);
		return values.isEmpty() ? null : values.get(0);
	}

	public boolean existsParticipationInOtherLiveAuction(Long accountId, Integer auctionId) {
		return count("""
				SELECT COUNT(*) FROM app_pujas_live p
				JOIN app_subasta_ext e ON e.subasta_id=p.subasta_id
				WHERE p.cuenta_id=? AND p.subasta_id<>? AND p.estado IN ('pendiente','aceptada')
				  AND e.estado_operativo='en_vivo'
				""", accountId, auctionId) > 0;
	}

	public List<Long> findNotificationRecipientsForAuctionStart(Integer auctionId) {
		return jdbc.query("""
				SELECT DISTINCT cuenta_id FROM app_inscripciones_subasta
				WHERE subasta_id=? AND estado NOT IN ('rechazada','expirada')
				""", (rs, row) -> rs.getLong(1), auctionId);
	}

	private int count(String sql, Object... args) {
		Integer value = jdbc.queryForObject(sql, Integer.class, args);
		return value == null ? 0 : value;
	}

	public int getMinPrice (Integer auctionId) {
		Integer value = jdbc.queryForObject("SELECT monto_minimo FROM app_subasta_ext WHERE subasta_id=?", Integer.class, auctionId);
		return value == null ? 0 : value;
	}

	public int getMaxPrice(Integer auctionId) {
		Integer value = jdbc.queryForObject("SELECT monto_maximo FROM app_subasta_ext WHERE subasta_id=?", Integer.class, auctionId);
		return value == null ? 0 : value;
	}
}
