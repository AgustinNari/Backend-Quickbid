package com.example.quickbid.quickbid;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.jdbc.Sql;

import com.example.quickbid.quickbid.security.AuthRateLimitService;
import com.example.quickbid.quickbid.service.PurchaseService;
import com.example.quickbid.quickbid.service.PurchaseService.PaymentOutcome;
import com.example.quickbid.quickbid.service.SimulatedMailService;

@SpringBootTest(properties = {
		"app.mail.enabled=false",
		"app.payment.adjudication-external-failure-enabled=true",
		"app.payment.adjudication-external-failure-probability-percent=100"
})
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Sql(scripts = "/auth-test-data.sql", executionPhase = Sql.ExecutionPhase.BEFORE_TEST_METHOD)
class PurchaseExternalFailureSimulationIntegrationTests {
	@Autowired PurchaseService purchases;
	@Autowired JdbcTemplate jdbc;
	@Autowired AuthRateLimitService limits;
	@Autowired SimulatedMailService mail;

	@BeforeEach
	void setUp() {
		limits.clear();
		mail.clear();
	}

	@Test
	void autoAdjudicationExternalFailureCreatesRejectedPaymentFineAndRestriction() {
		var purchase = purchases.closeLot(6001, PaymentOutcome.AUTO);

		assertEquals("multa_activa", purchase.estado());
		assertEquals("liberada", singleString("SELECT estado FROM app_reservas_medio_pago WHERE puja_id=12501"));
		assertEquals("restriccion_multa", singleString("SELECT estado FROM app_cuentas WHERE id=3001"));
		assertEquals("2510.00", jdbc.queryForObject("SELECT monto FROM app_multas WHERE compra_id=?",
				java.math.BigDecimal.class, purchase.id()).toPlainString());
		assertEquals("rechazado", singleString("SELECT estado FROM app_pagos WHERE compra_id=" + purchase.id()));
		assertEquals("ADJUDICATION_EXTERNAL_PAYMENT_FAILURE",
				singleString("SELECT error_codigo FROM app_pagos WHERE compra_id=" + purchase.id()));
		assertEquals("Falla externa simulada durante el cobro automatico de adjudicacion.",
				singleString("SELECT error_detalle FROM app_pagos WHERE compra_id=" + purchase.id()));
		assertTrue(mail.deliveries().stream().anyMatch(message -> message.purpose().equals("multa_generada")));
	}

	@Test
	void successOutcomeIgnoresExternalFailureProbability() {
		var purchase = purchases.closeLot(6001, PaymentOutcome.SUCCESS);

		assertEquals("pagos_extra_pendientes", purchase.estado());
		assertEquals("consumida", singleString("SELECT estado FROM app_reservas_medio_pago WHERE puja_id=12501"));
		assertEquals("activa", singleString("SELECT estado FROM app_cuentas WHERE id=3001"));
		assertEquals(1, count("SELECT COUNT(*) FROM app_pagos WHERE compra_id=? AND estado='aprobado'",
				purchase.id()));
		assertEquals(0, count("SELECT COUNT(*) FROM app_multas WHERE compra_id=?", purchase.id()));
	}

	private String singleString(String sql, Object... args) {
		return jdbc.queryForObject(sql, String.class, args);
	}

	private int count(String sql, Object... args) {
		return jdbc.queryForObject(sql, Integer.class, args);
	}
}
