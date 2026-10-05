package com.example.quickbid.quickbid.dto.response;

import java.math.BigDecimal;
import java.time.OffsetDateTime;

public record MedioPagoResponse(
		Long id,
		String tipo,
		String moneda,
		String estado,
		Boolean principal,
		String aliasVisible,
		String ultimos4,
		String banco,
		BigDecimal saldoGarantia,
		BigDecimal limiteMonto,
		BigDecimal limiteUsado,
		BigDecimal limiteDisponible,
		OffsetDateTime verificadoHasta,
		OffsetDateTime createdAt) {
}
