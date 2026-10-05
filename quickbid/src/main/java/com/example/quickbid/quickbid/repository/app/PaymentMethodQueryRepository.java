package com.example.quickbid.quickbid.repository.app;

import java.math.BigDecimal;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class PaymentMethodQueryRepository {
	private final JdbcTemplate jdbc;

	public PaymentMethodQueryRepository(JdbcTemplate jdbc) {
		this.jdbc = jdbc;
	}

	public boolean existsPendingOperationForPaymentMethod(Long paymentMethodId) {
		return count("""
				SELECT COUNT(*) FROM (
				  SELECT medio_pago_id FROM app_inscripciones_subasta
				    WHERE medio_pago_id=? AND estado IN ('pendiente_validacion','aprobada')
				  UNION ALL
				  SELECT medio_pago_id FROM app_pujas_live
				    WHERE medio_pago_id=? AND estado IN ('pendiente','aceptada','ganadora')
				  UNION ALL
				  SELECT medio_pago_id FROM app_compras
				    WHERE medio_pago_id=? AND estado NOT IN
				      ('completada','abandonada_por_incumplimiento_pago','abandonada_por_incumplimiento_retiro')
				  UNION ALL
				  SELECT c.medio_pago_id FROM app_multas m JOIN app_compras c ON c.id=m.compra_id
				    WHERE c.medio_pago_id=? AND m.estado IN ('pendiente','vencida')
				  UNION ALL
				  SELECT medio_pago_id FROM app_pagos WHERE medio_pago_id=? AND estado='pendiente'
				) pending_operations
				""", paymentMethodId, paymentMethodId, paymentMethodId, paymentMethodId, paymentMethodId) > 0;
	}

	public BigDecimal activeReservationsForPaymentMethod(Long paymentMethodId) {
		BigDecimal value = jdbc.queryForObject("""
				SELECT COALESCE(SUM(monto),0)
				FROM app_reservas_medio_pago
				WHERE medio_pago_id=? AND estado='activa'
				""", BigDecimal.class, paymentMethodId);
		return value == null ? BigDecimal.ZERO : value.max(BigDecimal.ZERO);
	}

	public ConsignmentRequirements findConsignmentRequirements(Long accountId) {
		return jdbc.queryForObject("""
				SELECT
				  (SELECT COUNT(*) FROM app_medios_pago WHERE cuenta_id=?
				    AND estado NOT IN ('rechazado','eliminado') AND deleted_at IS NULL) registered_payment,
				  (SELECT COUNT(*) FROM app_medios_pago WHERE cuenta_id=? AND tipo='cuenta_bancaria'
				    AND estado NOT IN ('rechazado','eliminado') AND deleted_at IS NULL) collection_account,
				  (SELECT COUNT(*) FROM app_medios_pago WHERE cuenta_id=? AND estado='verificado'
				    AND deleted_at IS NULL AND verificado_hasta>CURRENT_TIMESTAMP) usable_return_payment
				""", (rs, row) -> new ConsignmentRequirements(rs.getInt("registered_payment") > 0,
						rs.getInt("collection_account") > 0, rs.getInt("usable_return_payment") > 0),
				accountId, accountId, accountId);
	}

	private int count(String sql, Object... args) {
		Integer value = jdbc.queryForObject(sql, Integer.class, args);
		return value == null ? 0 : value;
	}

	public record ConsignmentRequirements(boolean registeredPayment, boolean collectionAccount,
			boolean usableReturnPayment) {
	}
}
