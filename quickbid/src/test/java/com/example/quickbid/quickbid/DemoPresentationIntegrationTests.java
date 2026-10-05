package com.example.quickbid.quickbid;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.math.BigDecimal;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.jdbc.Sql;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import com.example.quickbid.quickbid.security.AdminInternalAuthenticationFilter;
import com.example.quickbid.quickbid.security.AuthRateLimitService;
import com.example.quickbid.quickbid.service.AuctionTimerService;
import com.jayway.jsonpath.JsonPath;

@SpringBootTest(properties = {
		"app.mail.enabled=false",
		"app.demo.auto-open-auctions-enabled=true",
		"app.demo.first-lot-waits-for-first-bid=true",
		"app.demo.auction-start-delay-seconds=120"
})
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Sql(scripts = "/auth-test-data.sql", executionPhase = Sql.ExecutionPhase.BEFORE_TEST_METHOD)
class DemoPresentationIntegrationTests {
	private static final String ADMIN_KEY = "test-admin-key";
	private static final int AUCTION_ID = 6011;
	private static final long BUYER_1 = 3001L;
	private static final long BUYER_2 = 3005L;
	private static final long SELLER = 3004L;

	@Autowired MockMvc mvc;
	@Autowired JdbcTemplate jdbc;
	@Autowired AuthRateLimitService rateLimit;
	@Autowired AuctionTimerService auctionTimers;

	@BeforeEach
	void setUp() {
		rateLimit.clear();
		seedPresentationScenario();
	}

	@Test
	void demo6011RunsThreeLotsWithTwoBuyersAndClosesCleanly() throws Exception {
		admin(post("/api/admin/reset/demo")).andExpect(status().isOk())
				.andExpect(jsonPath("$.data.estado").value("preparada"));
		admin(post("/api/admin/subastas/{id}/abrir", AUCTION_ID)).andExpect(status().isOk());

		activate(9011);
		authGet("aprobado@quickbid.demo").andExpect(status().isOk())
				.andExpect(jsonPath("$.data.itemActivoId").value(9011))
				.andExpect(jsonPath("$.data.puedePujar").value(true));
		authBid("aprobado@quickbid.demo", bid(9011, "50000", 5001, version(), "demo-9011-buyer1"))
				.andExpect(status().isCreated());
		Long firstBid = latestBid(9011, BUYER_1);
		authBid("comprador2@quickbid.demo", bid(9011, "50500", 5010, version(), "demo-9011-buyer2"))
				.andExpect(status().isCreated());
		assertEquals("superada", bidState(firstBid));
		assertEquals("liberada", reservationState(firstBid));
		closeActiveLot();
		assertSoldToBuyer(9011, 8011, BUYER_2, new BigDecimal("50500.00"));

		activate(9012);
		authBid("aprobado@quickbid.demo", bid(9012, "70000", 5001, version(), "demo-9012-buyer1"))
				.andExpect(status().isCreated());
		authBid("comprador2@quickbid.demo", bid(9012, "70700", 5010, version(), "demo-9012-buyer2"))
				.andExpect(status().isCreated());
		closeActiveLot();
		assertSoldToBuyer(9012, 8012, BUYER_2, new BigDecimal("70700.00"));

		activate(9013);
		authBid("aprobado@quickbid.demo", bid(9013, "90000", 5001, version(), "demo-9013-buyer1"))
				.andExpect(status().isCreated());
		authBid("comprador2@quickbid.demo", bid(9013, "90900", 5010, version(), "demo-9013-buyer2"))
				.andExpect(status().isCreated());
		closeActiveLot();
		admin(post("/api/admin/subastas/{id}/cerrar", AUCTION_ID)).andExpect(status().isOk());

		assertEquals("finalizada", singleString("SELECT estado_operativo FROM app_subasta_ext WHERE subasta_id=?", AUCTION_ID));
		assertEquals(0, count("SELECT COUNT(*) FROM \"itemsCatalogo\" i JOIN catalogos c ON c.identificador=i.catalogo "
				+ "WHERE c.subasta=? AND i.subastado<>'si'", AUCTION_ID));
		assertEquals(3, count("SELECT COUNT(*) FROM app_compras WHERE subasta_id=? AND cuenta_comprador_id=?",
				AUCTION_ID, BUYER_2));
		assertEquals(3, count("SELECT COUNT(*) FROM app_reservas_medio_pago r JOIN app_pujas_live p ON p.id=r.puja_id "
				+ "WHERE p.subasta_id=? AND r.estado='consumida'", AUCTION_ID));
		assertEquals(3, count("SELECT COUNT(*) FROM app_solicitudes_consignacion WHERE subasta_id=? AND estado='vendida'",
				AUCTION_ID));
		assertEquals(3, count("SELECT COUNT(*) FROM app_documentos d JOIN app_archivos a ON a.id=d.archivo_id "
				+ "WHERE d.referencia_tipo='consignacion' AND d.referencia_id BETWEEN 16111 AND 16113 "
				+ "AND a.content_bytes IS NOT NULL AND OCTET_LENGTH(a.content_bytes)>0"));
	}

	@Test
	void resetDemoAfterHappyPathClearsDynamicStateAndRestoresMainBuyers() throws Exception {
		admin(post("/api/admin/reset/demo")).andExpect(status().isOk());
		admin(post("/api/admin/subastas/{id}/abrir", AUCTION_ID)).andExpect(status().isOk());
		activate(9011);
		authBid("aprobado@quickbid.demo", bid(9011, "50000", 5001, version(), "demo-reset-happy-buyer1"))
				.andExpect(status().isCreated());
		authBid("comprador2@quickbid.demo", bid(9011, "50500", 5010, version(), "demo-reset-happy-buyer2"))
				.andExpect(status().isCreated());
		closeActiveLot();
		assertTrue(count("SELECT COUNT(*) FROM app_compras WHERE subasta_id=?", AUCTION_ID) > 0);
		assertTrue(count("SELECT COUNT(*) FROM app_reservas_medio_pago r JOIN app_pujas_live p ON p.id=r.puja_id "
				+ "WHERE p.subasta_id=?", AUCTION_ID) > 0);
		jdbc.update("""
				INSERT INTO app_notificaciones(cuenta_id,tipo,titulo,descripcion,referencia_tipo,referencia_id)
				VALUES (3005,'lote_ganado','Ganaste','Notificacion dinamica','subasta',?)
				""", AUCTION_ID);

		admin(post("/api/admin/reset/demo")).andExpect(status().isOk());

		assertResetDemoClean();
		assertEquals(0, count("SELECT COUNT(*) FROM app_notificaciones WHERE referencia_tipo='subasta' "
				+ "AND referencia_id=? AND tipo<>'demo_lista'", AUCTION_ID));
	}

	@Test
	void resetDemoAfterPaymentFailureRestoresBuyerAndLeavesNegativeUsersIntact() throws Exception {
		admin(post("/api/admin/reset/demo")).andExpect(status().isOk());
		admin(post("/api/admin/subastas/{id}/abrir", AUCTION_ID)).andExpect(status().isOk());
		activate(9011);
		authBid("comprador2@quickbid.demo", bid(9011, "50000", 5010, version(), "demo-reset-failure-buyer2"))
				.andExpect(status().isCreated());
		admin(post("/api/admin/subastas/{id}/cerrar-lote?resultado=FAILURE", AUCTION_ID)).andExpect(status().isOk());
		assertEquals("restriccion_multa", singleString("SELECT estado FROM app_cuentas WHERE id=?", BUYER_2));
		assertTrue(count("SELECT COUNT(*) FROM app_multas m JOIN app_compras c ON c.id=m.compra_id "
				+ "WHERE c.subasta_id=? AND c.item_catalogo_id=9011 AND m.estado='pendiente'", AUCTION_ID) > 0);

		admin(post("/api/admin/reset/demo")).andExpect(status().isOk());

		assertResetDemoClean();
		assertEquals("restriccion_multa", singleString("SELECT estado FROM app_cuentas WHERE id=3002"));
		assertEquals("bloqueada_permanente", singleString("SELECT estado FROM app_cuentas WHERE id=3003"));
		assertEquals("comun", singleString("SELECT categoria_calculada FROM app_cuentas WHERE id=3006"));
	}

	@Test
	void demo6011RejectsImportantInvalidBidBranches() throws Exception {
		admin(post("/api/admin/reset/demo")).andExpect(status().isOk());
		admin(post("/api/admin/subastas/{id}/abrir", AUCTION_ID)).andExpect(status().isOk());
		activate(9011);

		authBid("multa@quickbid.demo", bid(9011, "50000", 5004, version(), "demo-negative-fine"))
				.andExpect(status().isForbidden())
				.andExpect(jsonPath("$.errors[0].code").value("ACCOUNT_RESTRICTED_BY_FINE"));
		authBid("categoria@quickbid.demo", bid(9011, "50000", 5014, version(), "demo-negative-category"))
				.andExpect(status().isForbidden())
				.andExpect(jsonPath("$.errors[0].code").value("AUCTION_CATEGORY_FORBIDDEN"));
		authBid("comprador2@quickbid.demo", bid(9011, "50000", 5011, version(), "demo-negative-pending"))
				.andExpect(status().isForbidden())
				.andExpect(jsonPath("$.errors[0].code").value("PAYMENT_METHOD_VERIFICATION_EXPIRED"));
		authBid("comprador2@quickbid.demo", bid(9011, "50000", 5012, version(), "demo-negative-expired"))
				.andExpect(status().isForbidden())
				.andExpect(jsonPath("$.errors[0].code").value("PAYMENT_METHOD_NOT_VERIFIED"));
		authBid("comprador2@quickbid.demo", bid(9011, "50000", 5015, version(), "demo-negative-limit"))
				.andExpect(status().isUnprocessableEntity())
				.andExpect(jsonPath("$.errors[0].code").value("PAYMENT_METHOD_INSUFFICIENT_FUNDS"));
		authBid("oro@quickbid.demo", bid(9011, "50000", 5006, version(), "demo-negative-own-item"))
				.andExpect(status().isForbidden())
				.andExpect(jsonPath("$.errors[0].code").value("OWN_ITEM_BID_FORBIDDEN"));
		authBid("aprobado@quickbid.demo", bid(9011, "50000", 5001, version() + 1, "demo-negative-stale"))
				.andExpect(status().isConflict())
				.andExpect(jsonPath("$.errors[0].code").value("BID_OUTDATED_STATE"));
		authBid("aprobado@quickbid.demo", bid(9012, "70000", 5001, version(), "demo-negative-inactive-item"))
				.andExpect(status().isConflict())
				.andExpect(jsonPath("$.errors[0].code").value("ITEM_NOT_ACTIVE"));

		assertEquals(0, count("SELECT COUNT(*) FROM app_pujas_live WHERE subasta_id=?", AUCTION_ID));
		assertEquals(0, count("SELECT COUNT(*) FROM app_reservas_medio_pago r JOIN app_pujas_live p ON p.id=r.puja_id "
				+ "WHERE p.subasta_id=?", AUCTION_ID));
	}

	@Test
	void demo6011AutoOpensFirstLotWaitsForBidAndTimersFinishAuction() throws Exception {
		admin(post("/api/admin/reset/demo")).andExpect(status().isOk())
				.andExpect(jsonPath("$.data.estado").value("preparada"))
				.andExpect(jsonPath("$.data.autoOpenEnabled").value(true))
				.andExpect(jsonPath("$.data.firstLotWaitsForFirstBid").value(true))
				.andExpect(jsonPath("$.data.itemInicial").value(9011));
		assertEquals("programada", singleString("SELECT estado_operativo FROM app_subasta_ext WHERE subasta_id=?", AUCTION_ID));
		assertEquals(0, count("SELECT COUNT(*) FROM app_pujas_live WHERE subasta_id=?", AUCTION_ID));
		assertEquals(0, count("SELECT COUNT(*) FROM app_compras WHERE subasta_id=?", AUCTION_ID));

		jdbc.update("UPDATE subastas SET fecha=DATEADD('DAY',-1,CURRENT_DATE), hora='20:00:00' WHERE identificador=?",
				AUCTION_ID);
		AuctionTimerService.TimerResult opened = auctionTimers.processDueTimers();
		assertEquals(1, opened.subastasDemoAbiertas());
		assertEquals(1, opened.lotesActivados());
		assertEquals("en_vivo", singleString("SELECT estado_operativo FROM app_subasta_ext WHERE subasta_id=?", AUCTION_ID));
		assertActiveLot(9011);
		assertNull(jdbc.queryForObject("SELECT lote_finaliza_estimado_at FROM app_subasta_estado_vivo WHERE subasta_id=?",
				Object.class, AUCTION_ID));
		authGet("aprobado@quickbid.demo").andExpect(status().isOk())
				.andExpect(jsonPath("$.data.itemActivoId").value(9011))
				.andExpect(jsonPath("$.data.esperandoPrimeraPuja").value(true))
				.andExpect(jsonPath("$.data.timerActivo").value(false));

		AuctionTimerService.TimerResult idle = auctionTimers.processDueTimers();
		assertEquals(0, idle.lotesCerrados());
		assertActiveLot(9011);
		assertEquals(0, count("SELECT COUNT(*) FROM app_compras WHERE subasta_id=?", AUCTION_ID));

		authBid("aprobado@quickbid.demo", bid(9011, "50000", 5001, version(), "demo-auto-first-bid"))
				.andExpect(status().isCreated());
		assertEquals(1, count("""
				SELECT COUNT(*) FROM app_subasta_estado_vivo
				WHERE subasta_id=? AND retencion_hasta IS NOT NULL AND lote_finaliza_estimado_at IS NOT NULL
				""", AUCTION_ID));
		authGet("aprobado@quickbid.demo").andExpect(status().isOk())
				.andExpect(jsonPath("$.data.esperandoPrimeraPuja").value(false))
				.andExpect(jsonPath("$.data.timerActivo").value(true));

		forceActiveLotDeadlineDue();
		AuctionTimerService.TimerResult closedFirst = auctionTimers.processDueTimers();
		assertEquals(1, closedFirst.lotesCerrados());
		assertPurchase(9011, BUYER_1, false, new BigDecimal("50000.00"));

		forceNextLotDue();
		AuctionTimerService.TimerResult activatedSecond = auctionTimers.processDueTimers();
		assertEquals(1, activatedSecond.lotesActivados());
		assertActiveLot(9012);
		assertEquals(1, count("SELECT COUNT(*) FROM app_subasta_estado_vivo WHERE subasta_id=? AND lote_finaliza_estimado_at IS NOT NULL",
				AUCTION_ID));

		forceActiveLotDeadlineDue();
		AuctionTimerService.TimerResult closedSecond = auctionTimers.processDueTimers();
		assertEquals(1, closedSecond.lotesCerrados());
		assertPurchase(9012, null, true, new BigDecimal("70000.00"));

		forceNextLotDue();
		AuctionTimerService.TimerResult activatedThird = auctionTimers.processDueTimers();
		assertEquals(1, activatedThird.lotesActivados());
		assertActiveLot(9013);
		forceActiveLotDeadlineDue();
		AuctionTimerService.TimerResult closedThird = auctionTimers.processDueTimers();
		assertEquals(1, closedThird.lotesCerrados());
		assertPurchase(9013, null, true, new BigDecimal("90000.00"));

		forceAuctionCloseDue();
		AuctionTimerService.TimerResult finalized = auctionTimers.processDueTimers();
		assertEquals(1, finalized.subastasFinalizadas());
		assertEquals("finalizada", singleString("SELECT estado_operativo FROM app_subasta_ext WHERE subasta_id=?", AUCTION_ID));
		authGet("aprobado@quickbid.demo").andExpect(status().isOk())
				.andExpect(jsonPath("$.data.estadoLote").value("finalizada"))
				.andExpect(jsonPath("$.data.timerActivo").value(false));
	}

	private void seedPresentationScenario() {
		jdbc.update("UPDATE app_subasta_ext SET estado_operativo='finalizada'");
		jdbc.update("INSERT INTO personas (identificador,documento,nombre,direccion,estado) VALUES (?,?,?,?,?)",
				2005, "DNI-DEMO-COMPRADOR2", "Belen Compradora", "Av. Demo 500", "activo");
		jdbc.update("INSERT INTO personas (identificador,documento,nombre,direccion,estado) VALUES (?,?,?,?,?)",
				2006, "DNI-DEMO-CATEGORIA", "Ciro Categoria", "Av. Demo 600", "activo");
		jdbc.update("INSERT INTO clientes (identificador,\"numeroPais\",admitido,categoria,verificador) VALUES (?,?,?,?,?)",
				2005, 32, "si", "plata", 1001);
		jdbc.update("INSERT INTO clientes (identificador,\"numeroPais\",admitido,categoria,verificador) VALUES (?,?,?,?,?)",
				2006, 32, "si", "comun", 1001);
		String hash = "$2a$10$V0pWYIdkIsrqbaS46NN5e.b88ECxrQ2of.z/qUHHT7rG.QHhRfJUm";
		jdbc.update("INSERT INTO app_cuentas(id,persona_id,cliente_id,email,password_hash,estado,puntos,categoria_calculada,intentos_login) "
				+ "VALUES (?,?,?,?,?,?,?,?,?)", BUYER_2, 2005, 2005, "comprador2@quickbid.demo", hash, "activa", 900, "plata", 0);
		jdbc.update("INSERT INTO app_cuentas(id,persona_id,cliente_id,email,password_hash,estado,puntos,categoria_calculada,intentos_login) "
				+ "VALUES (?,?,?,?,?,?,?,?,?)", 3006, 2006, 2006, "categoria@quickbid.demo", hash, "activa", 0, "comun", 0);
		insertCard(5010, BUYER_2, "verificado", "Belen Compradora", "Demo Visa 5010", "5010", "700000.00", 0, 7);
		insertCard(5011, BUYER_2, "verificado", "Belen Compradora", "Demo vencida 5011", "5011", "700000.00", 0, -1);
		insertCard(5012, BUYER_2, "pendiente_verificacion", "Belen Compradora", "Demo pendiente 5012", "5012", "700000.00", 0, 7);
		insertCard(5014, 3006, "verificado", "Ciro Categoria", "Demo limite bajo 5014", "5014", "1000.00", 0, 7);
		// Fixture interno: V17 no usa 5015; permite probar limite bajo sin cortar por categoria.
		insertCard(5015, BUYER_2, "verificado", "Belen Compradora", "Demo limite bajo 5015", "5015", "1000.00", 0, 7);

		jdbc.update("INSERT INTO subastas(identificador,fecha,hora,estado,ubicacion,categoria) VALUES (?,CURRENT_DATE,'20:00:00','abierta',?,?)",
				AUCTION_ID, "Salon Demo Presentacion", "plata");
		jdbc.update("INSERT INTO app_subasta_ext(subasta_id,titulo,descripcion,moneda,segmento,estado_operativo) VALUES (?,?,?,?,?,?)",
				AUCTION_ID, "Demo presentacion multilote", "Tres lotes con dos compradores para demo.", "ARS", "demo", "abierta");
		jdbc.update("INSERT INTO catalogos(identificador,descripcion,subasta,responsable) VALUES (?,?,?,?)",
				7011, "Catalogo demo presentacion", AUCTION_ID, 1004);
		jdbc.update("INSERT INTO app_subasta_estado_vivo(subasta_id,item_catalogo_activo_id,version,usuarios_conectados) VALUES (?,?,?,?)",
				AUCTION_ID, null, 0, 0);
		jdbc.update("INSERT INTO seguros(\"nroPoliza\",compania,\"polizaCombinada\",importe) VALUES (?,?,?,?)",
				"POL-DEMO-8011", "Aseguradora Demo", "si", new BigDecimal("12000.00"));
		jdbc.update("INSERT INTO seguros(\"nroPoliza\",compania,\"polizaCombinada\",importe) VALUES (?,?,?,?)",
				"POL-DEMO-8012", "Aseguradora Demo", "si", new BigDecimal("15000.00"));
		jdbc.update("INSERT INTO seguros(\"nroPoliza\",compania,\"polizaCombinada\",importe) VALUES (?,?,?,?)",
				"POL-DEMO-8013", "Aseguradora Demo", "si", new BigDecimal("20000.00"));
		insertLot(8011, 9011, "Lote demo 1 - sillon escandinavo", "POL-DEMO-8011", "50000.00", "5000.00", 16111);
		insertLot(8012, 9012, "Lote demo 2 - vajilla art deco", "POL-DEMO-8012", "70000.00", "7000.00", 16112);
		insertLot(8013, 9013, "Lote demo 3 - reloj de mesa", "POL-DEMO-8013", "90000.00", "9000.00", 16113);
		enroll(10012, AUCTION_ID, BUYER_1, 5001, 2, 2001);
		enroll(10013, AUCTION_ID, BUYER_2, 5010, 3, 2005);
		enroll(10014, AUCTION_ID, SELLER, 5006, 4, 2004);
		enroll(10015, AUCTION_ID, 3006L, 5014, 5, 2006);
	}

	private void insertCard(long id, long accountId, String state, String holder, String alias, String last4,
			String limit, int consumed, int verifiedDays) {
		jdbc.update("""
				INSERT INTO app_medios_pago(id,cuenta_id,tipo,moneda,estado,principal,nacional,alias_visible,
					ultimos_4,titular,hash_identificador,limite_monto,consumo_actual,saldo_garantia,verificado_hasta)
				VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,DATEADD('DAY',?,CURRENT_TIMESTAMP))
				""", id, accountId, "tarjeta", "ARS", state, false, true, alias, last4, holder,
				"demo-token-" + id, new BigDecimal(limit), new BigDecimal(consumed), null, verifiedDays);
		jdbc.update("INSERT INTO app_tarjetas(medio_pago_id,marca,vencimiento_mes,vencimiento_anio) VALUES (?,?,?,?)",
				id, "Visa", 12, 2030);
	}

	private void insertLot(int productId, int itemId, String description, String policy, String basePrice,
			String commission, long consignmentId) {
		jdbc.update("""
				INSERT INTO productos(identificador,fecha,disponible,"descripcionCatalogo","descripcionCompleta",revisor,duenio,seguro)
				VALUES (?,CURRENT_DATE,'si',?,?,?,?,?)
				""", productId, description, description + " con documentacion validada.", 1002, 2004, policy);
		jdbc.update("INSERT INTO fotos(identificador,producto,foto) VALUES (?,?,?)",
				productId + 100, productId, new byte[] {(byte) 137, 80, 78, 71, 13, 10, 26, 10});
		jdbc.update("INSERT INTO \"itemsCatalogo\"(identificador,catalogo,producto,\"precioBase\",comision,subastado) VALUES (?,?,?,?,?,'no')",
				itemId, 7011, productId, new BigDecimal(basePrice), new BigDecimal(commission));
		jdbc.update("""
				INSERT INTO app_solicitudes_consignacion(id,cuenta_id,cliente_id,producto_id,item_catalogo_id,subasta_id,
					titulo,descripcion,segmento,categoria_sugerida,declaracion_propiedad,acepta_devolucion_con_cargo,
					estado,revisor_empleado_id,valor_base_propuesto,moneda_propuesta,comision_comprador_pct,comision_vendedor_pct)
				VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)
				""", consignmentId, SELLER, 2004, productId, itemId, AUCTION_ID, description,
				description + " para subasta demo.", "demo", "plata", true, true, "en_subasta", 1002,
				new BigDecimal(basePrice), "ARS", new BigDecimal("10.00"), new BigDecimal("10.00"));
		long fileId = 17400 + (consignmentId - 16110);
		jdbc.update("""
				INSERT INTO app_archivos(id,owner_cuenta_id,tipo_contexto,filename_original,content_type,size_bytes,
					storage_path,checksum,content_bytes)
				VALUES (?,?,?,?,?,?,?,?,?)
				""", fileId, SELLER, "consignacion", "poliza-" + itemId + ".pdf", "application/pdf", 14,
				"db://demo/poliza-" + itemId + ".pdf", "demo-checksum-" + itemId,
				"%PDF-1.4 demo".getBytes(java.nio.charset.StandardCharsets.US_ASCII));
		jdbc.update("INSERT INTO app_documentos(id,tipo,referencia_tipo,referencia_id,archivo_id,estado) VALUES (?,?,?,?,?,'disponible')",
				fileId, "poliza_seguro", "consignacion", consignmentId, fileId);
	}

	private void enroll(long enrollmentId, int auctionId, long accountId, long paymentId, int bidderNumber, int clientId) {
		jdbc.update("INSERT INTO app_inscripciones_subasta(id,subasta_id,cuenta_id,medio_pago_id,estado) VALUES (?,?,?,?,?)",
				enrollmentId, auctionId, accountId, paymentId, "aprobada");
		jdbc.update("INSERT INTO asistentes(identificador,\"numeroPostor\",cliente,subasta) VALUES (?,?,?,?)",
				enrollmentId + 1000, bidderNumber, clientId, auctionId);
	}

	private void activate(int itemId) throws Exception {
		admin(post("/api/admin/subastas/{id}/item-activo", AUCTION_ID)
				.contentType(MediaType.APPLICATION_JSON)
				.content("{\"itemCatalogoId\":" + itemId + "}"))
				.andExpect(status().isOk());
	}

	private void closeActiveLot() throws Exception {
		admin(post("/api/admin/subastas/{id}/cerrar-lote?resultado=SUCCESS", AUCTION_ID))
				.andExpect(status().isOk());
	}

	private void forceActiveLotDeadlineDue() {
		jdbc.update("""
				UPDATE app_subasta_estado_vivo
				SET lote_finaliza_estimado_at=DATEADD('SECOND',-1,CURRENT_TIMESTAMP)
				WHERE subasta_id=?
				""", AUCTION_ID);
	}

	private void forceNextLotDue() {
		jdbc.update("""
				UPDATE app_subasta_estado_vivo
				SET proximo_lote_programado_at=DATEADD('SECOND',-1,CURRENT_TIMESTAMP)
				WHERE subasta_id=?
				""", AUCTION_ID);
	}

	private void forceAuctionCloseDue() {
		jdbc.update("""
				UPDATE app_subasta_estado_vivo
				SET subasta_finaliza_programado_at=DATEADD('SECOND',-1,CURRENT_TIMESTAMP)
				WHERE subasta_id=?
				""", AUCTION_ID);
	}

	private void assertActiveLot(int itemId) {
		assertEquals(itemId, jdbc.queryForObject(
				"SELECT item_catalogo_activo_id FROM app_subasta_estado_vivo WHERE subasta_id=?",
				Integer.class, AUCTION_ID));
	}

	private void assertPurchase(int itemId, Long accountId, boolean companyBuyer, BigDecimal amount) {
		assertEquals(1, count("""
				SELECT COUNT(*) FROM app_compras
				WHERE subasta_id=? AND item_catalogo_id=? AND monto_adjudicacion=? AND comprador_empresa=?
				  AND ((? IS NULL AND cuenta_comprador_id IS NULL) OR cuenta_comprador_id=?)
				""", AUCTION_ID, itemId, amount, companyBuyer, accountId, accountId));
		assertEquals("si", singleString("SELECT subastado FROM \"itemsCatalogo\" WHERE identificador=?", itemId));
	}

	private void assertSoldToBuyer(int itemId, int productId, long accountId, BigDecimal amount) {
		Long purchaseId = jdbc.queryForObject("SELECT id FROM app_compras WHERE item_catalogo_id=?",
				Long.class, itemId);
		assertNotNull(purchaseId);
		assertEquals(1, count("SELECT COUNT(*) FROM app_compras WHERE id=? AND producto_id=? "
				+ "AND cuenta_comprador_id=? AND monto_adjudicacion=? AND estado='pagos_extra_pendientes'",
				purchaseId, productId, accountId, amount));
		assertEquals("si", singleString("SELECT subastado FROM \"itemsCatalogo\" WHERE identificador=?", itemId));
		assertEquals("vendida", singleString("SELECT estado FROM app_solicitudes_consignacion WHERE item_catalogo_id=?", itemId));
		assertEquals("ganadora", singleString("SELECT estado FROM app_pujas_live WHERE item_catalogo_id=? AND cuenta_id=?",
				itemId, accountId));
		assertTrue(count("SELECT COUNT(*) FROM \"registroDeSubasta\" WHERE producto=? AND cliente=?",
				productId, 2005) > 0);
	}

	private void assertResetDemoClean() {
		assertEquals(0, count("SELECT COUNT(*) FROM app_compras WHERE subasta_id=? AND item_catalogo_id IN (9011,9012,9013)",
				AUCTION_ID));
		assertEquals(0, count("SELECT COUNT(*) FROM app_multas m JOIN app_compras c ON c.id=m.compra_id "
				+ "WHERE c.subasta_id=?", AUCTION_ID));
		assertEquals(0, count("SELECT COUNT(*) FROM app_reservas_medio_pago r JOIN app_pujas_live p ON p.id=r.puja_id "
				+ "WHERE p.subasta_id=?", AUCTION_ID));
		assertEquals(0, count("SELECT COUNT(*) FROM app_pujas_live WHERE subasta_id=?", AUCTION_ID));
		assertEquals(0, count("SELECT COUNT(*) FROM \"registroDeSubasta\" WHERE subasta=? AND producto IN (8011,8012,8013)",
				AUCTION_ID));
		assertEquals(0, count("SELECT COUNT(*) FROM \"itemsCatalogo\" WHERE identificador IN (9011,9012,9013) "
				+ "AND subastado<>'no'"));
		assertEquals(3, count("SELECT COUNT(*) FROM app_solicitudes_consignacion WHERE id IN (16111,16112,16113) "
				+ "AND estado='en_subasta'"));
		assertEquals(0, count("SELECT COUNT(*) FROM app_medios_pago WHERE id IN (5001,5010) AND consumo_actual<>0"));
		assertEquals(2, count("SELECT COUNT(*) FROM app_medios_pago WHERE id IN (5001,5010) AND estado='verificado'"));
		assertEquals(2, count("SELECT COUNT(*) FROM app_cuentas WHERE id IN (3001,3005) AND estado='activa'"));
		assertEquals("programada", singleString("SELECT estado_operativo FROM app_subasta_ext WHERE subasta_id=?", AUCTION_ID));
		assertEquals(1, count("""
				SELECT COUNT(*) FROM app_subasta_estado_vivo
				WHERE subasta_id=? AND item_catalogo_activo_id IS NULL AND retencion_hasta IS NULL
				  AND lote_finaliza_estimado_at IS NULL AND proximo_lote_programado_at IS NULL
				  AND subasta_finaliza_programado_at IS NULL
				""", AUCTION_ID));
	}

	private ResultActions authBid(String email, String body) throws Exception {
		return mvc.perform(post("/api/subastas/{id}/pujar", AUCTION_ID)
				.header("Authorization", "Bearer " + token(email))
				.contentType(MediaType.APPLICATION_JSON)
				.content(body));
	}

	private ResultActions authGet(String email) throws Exception {
		return mvc.perform(get("/api/subastas/{id}/puja-actual", AUCTION_ID)
				.header("Authorization", "Bearer " + token(email)));
	}

	private ResultActions admin(MockHttpServletRequestBuilder builder) throws Exception {
		return mvc.perform(builder.header(AdminInternalAuthenticationFilter.HEADER, ADMIN_KEY));
	}

	private String bid(int itemId, String amount, long paymentId, long version, String key) {
		return """
				{"itemCatalogoId":%d,"monto":%s,"medioPagoId":%d,"clientStateVersion":%d,"idempotencyKey":"%s"}
				""".formatted(itemId, amount, paymentId, version, key);
	}

	private long version() {
		return jdbc.queryForObject("SELECT version FROM app_subasta_estado_vivo WHERE subasta_id=?",
				Long.class, AUCTION_ID);
	}

	private Long latestBid(int itemId, long accountId) {
		return jdbc.queryForObject("SELECT MAX(id) FROM app_pujas_live WHERE subasta_id=? AND item_catalogo_id=? AND cuenta_id=?",
				Long.class, AUCTION_ID, itemId, accountId);
	}

	private String bidState(long bidId) {
		return singleString("SELECT estado FROM app_pujas_live WHERE id=?", bidId);
	}

	private String reservationState(long bidId) {
		return singleString("SELECT estado FROM app_reservas_medio_pago WHERE puja_id=?", bidId);
	}

	private String singleString(String sql, Object... args) {
		return jdbc.queryForObject(sql, String.class, args);
	}

	private int count(String sql, Object... args) {
		return jdbc.queryForObject(sql, Integer.class, args);
	}

	private String token(String email) throws Exception {
		String json = mvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
				.content("{\"email\":\"" + email + "\",\"clave\":\"Demo123!\"}"))
				.andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
		return JsonPath.read(json, "$.data.accessToken");
	}
}
