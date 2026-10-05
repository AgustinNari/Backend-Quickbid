package com.example.quickbid.quickbid.repository.app;

import java.time.OffsetDateTime;
import java.util.List;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import com.example.quickbid.quickbid.dto.response.PurchaseDtos.Fine;
import com.example.quickbid.quickbid.dto.response.PurchaseDtos.Summary;

@Repository
public class PurchaseReadRepository {
	private final JdbcTemplate jdbc;

	public PurchaseReadRepository(JdbcTemplate jdbc) {
		this.jdbc = jdbc;
	}

	public long countByAccountId(Long accountId, String state) {
		if (state == null) {
			return jdbc.queryForObject("SELECT COUNT(*) FROM app_compras WHERE cuenta_comprador_id=?", Long.class, accountId);
		}
		return jdbc.queryForObject("SELECT COUNT(*) FROM app_compras WHERE cuenta_comprador_id=? AND estado=?",
				Long.class, accountId, state);
	}

	public List<Summary> findByAccountId(Long accountId, String state, int page, int size) {
		if (state != null) {
			return jdbc.query("""
					SELECT c.id,c.subasta_id,c.item_catalogo_id,c.producto_id,c.monto_adjudicacion,c.moneda,c.estado,
					       c.created_at,m.id multa_id,m.monto multa_monto,m.moneda multa_moneda,m.estado multa_estado,
					       m.vence_at multa_vence_at,m.paid_at multa_paid_at
					FROM app_compras c
					LEFT JOIN (
						SELECT id,compra_id,monto,moneda,estado,vence_at,paid_at,
						       ROW_NUMBER() OVER (PARTITION BY compra_id ORDER BY id DESC) rn
						FROM app_multas
					) m ON m.compra_id=c.id AND m.rn=1
					WHERE c.cuenta_comprador_id=? AND c.estado=?
					ORDER BY c.created_at DESC,c.id DESC LIMIT ? OFFSET ?
					""", (rs, row) -> summary(rs), accountId, state, size, page * size);
		}
		return jdbc.query("""
				SELECT c.id,c.subasta_id,c.item_catalogo_id,c.producto_id,c.monto_adjudicacion,c.moneda,c.estado,
				       c.created_at,m.id multa_id,m.monto multa_monto,m.moneda multa_moneda,m.estado multa_estado,
				       m.vence_at multa_vence_at,m.paid_at multa_paid_at
				FROM app_compras c
				LEFT JOIN (
					SELECT id,compra_id,monto,moneda,estado,vence_at,paid_at,
					       ROW_NUMBER() OVER (PARTITION BY compra_id ORDER BY id DESC) rn
					FROM app_multas
				) m ON m.compra_id=c.id AND m.rn=1
				WHERE c.cuenta_comprador_id=? ORDER BY c.created_at DESC,c.id DESC LIMIT ? OFFSET ?
				""", (rs, row) -> summary(rs), accountId, size, page * size);
	}

	public List<DocumentFile> findAvailableDocuments(Long purchaseId) {
		return jdbc.query("""
				SELECT id,tipo,estado,archivo_id,filename_original,content_type,size_bytes,storage_path,content_bytes,created_at
				FROM (
					SELECT d.id,d.tipo,d.estado,d.archivo_id,a.filename_original,a.content_type,a.size_bytes,a.storage_path,a.content_bytes,d.created_at,
					       ROW_NUMBER() OVER (PARTITION BY d.tipo ORDER BY d.created_at DESC,d.id DESC) rn
					FROM app_documentos d JOIN app_archivos a ON a.id=d.archivo_id
					WHERE d.referencia_tipo='compra' AND d.referencia_id=? AND d.estado='disponible'
				) latest
				WHERE rn=1
				ORDER BY created_at,id
				""", (rs, row) -> new DocumentFile(rs.getLong("id"), rs.getString("tipo"), rs.getString("estado"),
						rs.getLong("archivo_id"), rs.getString("filename_original"), rs.getString("content_type"),
						rs.getLong("size_bytes"), rs.getString("storage_path"), rs.getBytes("content_bytes"),
						rs.getObject("created_at", OffsetDateTime.class)), purchaseId);
	}

	public DocumentFile findDocument(Long purchaseId, Long documentId) {
		List<DocumentFile> values = jdbc.query("""
				SELECT d.id,d.tipo,d.estado,d.archivo_id,a.filename_original,a.content_type,a.size_bytes,a.storage_path,a.content_bytes,d.created_at
				FROM app_documentos d JOIN app_archivos a ON a.id=d.archivo_id
				WHERE d.id=? AND d.referencia_tipo='compra' AND d.referencia_id=? AND d.estado='disponible'
				""", (rs, row) -> new DocumentFile(rs.getLong("id"), rs.getString("tipo"), rs.getString("estado"),
					rs.getLong("archivo_id"), rs.getString("filename_original"), rs.getString("content_type"),
				rs.getLong("size_bytes"), rs.getString("storage_path"), rs.getBytes("content_bytes"),
					rs.getObject("created_at", OffsetDateTime.class)), documentId, purchaseId);
		return values.isEmpty() ? null : values.get(0);
	}

	public record DocumentFile(Long id, String type, String state, Long fileId, String filename, String contentType,
			Long sizeBytes, String storagePath, byte[] contentBytes, OffsetDateTime createdAt) {
	}

	private Summary summary(java.sql.ResultSet rs) throws java.sql.SQLException {
		Long fineId = (Long) rs.getObject("multa_id");
		Fine fine = fineId == null ? null : new Fine(fineId, rs.getBigDecimal("multa_monto"),
				rs.getString("multa_moneda"), rs.getString("multa_estado"),
				rs.getObject("multa_vence_at", OffsetDateTime.class),
				rs.getObject("multa_paid_at", OffsetDateTime.class));
		return new Summary(rs.getLong("id"), rs.getInt("subasta_id"), rs.getInt("item_catalogo_id"),
				rs.getInt("producto_id"), rs.getBigDecimal("monto_adjudicacion"), rs.getString("moneda"),
				rs.getString("estado"), rs.getObject("created_at", OffsetDateTime.class), fine);
	}
}
