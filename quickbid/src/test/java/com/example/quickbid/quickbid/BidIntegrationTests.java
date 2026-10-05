package com.example.quickbid.quickbid;

import static org.hamcrest.Matchers.nullValue;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.math.BigDecimal;
import java.sql.Time;
import java.time.LocalDateTime;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

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

import com.example.quickbid.quickbid.dto.request.BidRequest;
import com.example.quickbid.quickbid.exception.BusinessException;
import com.example.quickbid.quickbid.security.AuthRateLimitService;
import com.example.quickbid.quickbid.service.BidTransactionService;
import com.jayway.jsonpath.JsonPath;

@SpringBootTest(properties = "app.mail.enabled=false")
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Sql(scripts = "/auth-test-data.sql", executionPhase = Sql.ExecutionPhase.BEFORE_TEST_METHOD)
class BidIntegrationTests {
	@Autowired MockMvc mvc;
	@Autowired JdbcTemplate jdbc;
	@Autowired AuthRateLimitService limits;
	@Autowired BidTransactionService transactions;

	@BeforeEach void clearLimits() {
		limits.clear();
	}

	@Test void pujarSinTokenDevuelve401Uniforme() throws Exception {
		mvc.perform(post("/api/subastas/6001/pujar").contentType(MediaType.APPLICATION_JSON)
				.content(bid(9001, "25300", 5001, 1, "no-token")))
				.andExpect(status().isUnauthorized())
				.andExpect(jsonPath("$.data").value(nullValue()))
				.andExpect(jsonPath("$.errors[0].code").value("UNAUTHORIZED"));
	}

	@Test void tokenInvalidoDevuelve401Uniforme() throws Exception {
		mvc.perform(post("/api/subastas/6001/pujar")
				.header("Authorization", "Bearer token-invalido")
				.contentType(MediaType.APPLICATION_JSON)
				.content(bid(9001, "25300", 5001, 1, "invalid-token")))
				.andExpect(status().isUnauthorized())
				.andExpect(jsonPath("$.data").value(nullValue()))
				.andExpect(jsonPath("$.message").exists())
				.andExpect(jsonPath("$.errors").isArray());
	}

	@Test void requestInvalidoDevuelve400Uniforme() throws Exception {
		authBid("aprobado@quickbid.demo", 6001, """
				{"itemCatalogoId":9001,"monto":0,"medioPagoId":5001,"clientStateVersion":1}
				""")
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.data").value(nullValue()))
				.andExpect(jsonPath("$.errors").isArray())
				.andExpect(jsonPath("$.errors[0].field").exists());
	}

	@Test void idempotencyKeyEsObligatoria() throws Exception {
		authBid("aprobado@quickbid.demo", 6001, """
				{"itemCatalogoId":9001,"monto":25300,"medioPagoId":5001,"clientStateVersion":1}
				""")
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.data").value(nullValue()))
				.andExpect(jsonPath("$.errors").isArray());
	}

	@Test void subastaInexistenteDevuelve404Uniforme() throws Exception {
		authBid("aprobado@quickbid.demo", 9999, bid(9001, "25300", 5001, 1, "missing-auction"))
				.andExpect(status().isNotFound())
				.andExpect(jsonPath("$.data").value(nullValue()))
				.andExpect(jsonPath("$.errors[0].code").value("RESOURCE_NOT_FOUND"));
	}

	@Test void itemInexistenteDevuelve404AntesDeValidarLoteActivo() throws Exception {
		authBid("aprobado@quickbid.demo", 6001, bid(9999, "25300", 5001, 1, "missing-item"))
				.andExpect(status().isNotFound())
				.andExpect(jsonPath("$.errors[0].code").value("RESOURCE_NOT_FOUND"));
	}

	@Test void itemDeOtraSubastaDevuelve404() throws Exception {
		authBid("aprobado@quickbid.demo", 6001, bid(9003, "25300", 5001, 1, "foreign-item"))
				.andExpect(status().isNotFound())
				.andExpect(jsonPath("$.errors[0].code").value("RESOURCE_NOT_FOUND"));
	}

	@Test void pujaValidaActualizaSnapshotAppLegacyAuditoriaYNotificacion() throws Exception {
		String response = authBid("aprobado@quickbid.demo", 6001, bid(9001, "25300", 5001, 1, "valid-normal"))
				.andExpect(status().isCreated())
				.andExpect(jsonPath("$.data.estado").value("aceptada"))
				.andExpect(jsonPath("$.data.versionEstado").value(2))
				.andExpect(jsonPath("$.data.secuencia").value(2))
				.andExpect(jsonPath("$.data.numeroPostor").value(1))
				.andReturn().getResponse().getContentAsString();
		Long bidId = Long.valueOf(JsonPath.read(response, "$.data.id").toString());
		assertEquals(2L, jdbc.queryForObject("SELECT version FROM app_subasta_estado_vivo WHERE subasta_id=6001", Long.class));
		assertEquals("superada", jdbc.queryForObject("SELECT estado FROM app_pujas_live WHERE id=12501", String.class));
		assertEquals("liberada", jdbc.queryForObject("SELECT estado FROM app_reservas_medio_pago WHERE puja_id=12501", String.class));
		assertEquals("activa", jdbc.queryForObject("SELECT estado FROM app_reservas_medio_pago WHERE puja_id=?", String.class, bidId));
		assertEquals(2, jdbc.queryForObject("SELECT COUNT(*) FROM pujos WHERE item=9001", Integer.class));
		assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM app_auditoria WHERE accion='subasta.puja_aceptada'", Integer.class));
		assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM app_notificaciones WHERE tipo='puja_aceptada' AND referencia_id<>12501", Integer.class));
		assertEquals(901, jdbc.queryForObject("SELECT puntos FROM app_cuentas WHERE id=3001", Integer.class));
		assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM app_movimientos_puntos WHERE motivo='puja_aceptada' AND referencia_id=?", Integer.class, bidId));
		assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM app_subasta_estado_vivo WHERE subasta_id=6001 AND lote_finaliza_estimado_at IS NOT NULL", Integer.class));
		assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM app_subasta_estado_vivo WHERE subasta_id=6001 AND retencion_hasta>CURRENT_TIMESTAMP", Integer.class));
		authGet("aprobado@quickbid.demo", 6001)
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.data.retencionHasta").exists())
				.andExpect(jsonPath("$.data.segundosRestantes").isNumber())
				.andExpect(jsonPath("$.data.miPujaGanadora").value(true))
				.andExpect(jsonPath("$.data.serverNow").exists());
		authGet("multa@quickbid.demo", 6001)
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.data.miPujaGanadora").value(false))
				.andExpect(jsonPath("$.data.mejorPostorId").doesNotExist());
	}

	@Test void versionObsoletaSeRechazaYAudita() throws Exception {
		authBid("aprobado@quickbid.demo", 6001, bid(9001, "25300", 5001, 0, "stale"))
				.andExpect(status().isConflict())
				.andExpect(jsonPath("$.errors[0].code").value("BID_OUTDATED_STATE"));
		assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM app_auditoria WHERE accion='subasta.puja_rechazada'", Integer.class));
	}

	@Test void itemDistintoDelActivoSeRechaza() throws Exception {
		authBid("aprobado@quickbid.demo", 6001, bid(9002, "25300", 5001, 1, "wrong-item"))
				.andExpect(status().isConflict())
				.andExpect(jsonPath("$.errors[0].code").value("ITEM_NOT_ACTIVE"));
	}

	@Test void cuentaRestringidaNoPuedePujar() throws Exception {
		authBid("multa@quickbid.demo", 6001, bid(9001, "25300", 5004, 1, "restricted"))
				.andExpect(status().isForbidden())
				.andExpect(jsonPath("$.errors[0].code").value("ACCOUNT_RESTRICTED_BY_FINE"));
	}

	@Test void medioPendienteYMonedaDistintaSeRechazan() throws Exception {
		authBid("aprobado@quickbid.demo", 6001, bid(9001, "25300", 5003, 1, "pending-payment"))
				.andExpect(status().isForbidden())
				.andExpect(jsonPath("$.errors[0].code").value("PAYMENT_METHOD_NOT_VERIFIED"));
		authBid("aprobado@quickbid.demo", 6001, bid(9001, "25300", 5002, 1, "wrong-currency"))
				.andExpect(status().isUnprocessableEntity())
				.andExpect(jsonPath("$.errors[0].code").value("PAYMENT_METHOD_CURRENCY_MISMATCH"));
	}

	@Test void medioInexistenteYMedioAjenoSeRechazan() throws Exception {
		authBid("aprobado@quickbid.demo", 6001, bid(9001, "25300", 9999, 1, "missing-payment"))
				.andExpect(status().isNotFound())
				.andExpect(jsonPath("$.errors[0].code").value("RESOURCE_NOT_FOUND"));
		authBid("aprobado@quickbid.demo", 6001, bid(9001, "25300", 5006, 1, "other-payment"))
				.andExpect(status().isForbidden())
				.andExpect(jsonPath("$.errors[0].code").value("RESOURCE_NOT_OWNED"));
	}

	@Test void medioVencidoSeRechaza() throws Exception {
		jdbc.update("UPDATE app_medios_pago SET verificado_hasta=DATEADD('DAY',-1,CURRENT_TIMESTAMP) WHERE id=5001");
		authBid("aprobado@quickbid.demo", 6001, bid(9001, "25300", 5001, 1, "expired-payment"))
				.andExpect(status().isForbidden())
				.andExpect(jsonPath("$.errors[0].code").value("PAYMENT_METHOD_VERIFICATION_EXPIRED"));
	}

	@Test void categoriaInsuficienteSeRechazaAunqueLaInscripcionExista() throws Exception {
		liveAuction(6105, 9105, "oro", 1000);
		enroll(6105, 3001, 5001);
		authBid("aprobado@quickbid.demo", 6105, bid(9105, "1000", 5001, 0, "category-forbidden"))
				.andExpect(status().isForbidden())
				.andExpect(jsonPath("$.errors[0].code").value("AUCTION_CATEGORY_FORBIDDEN"));
	}

	@Test void subastaNoLiveSeRechaza() throws Exception {
		authBid("aprobado@quickbid.demo", 6002, bid(9003, "1500", 5002, 0, "not-live"))
				.andExpect(status().isConflict())
				.andExpect(jsonPath("$.errors[0].code").value("AUCTION_NOT_LIVE"));
	}

	@Test void subastaCerradaSeRechaza() throws Exception {
		authBid("aprobado@quickbid.demo", 6003, bid(9004, "120000", 5001, 3, "closed-auction"))
				.andExpect(status().isConflict())
				.andExpect(jsonPath("$.errors[0].code").value("AUCTION_NOT_LIVE"));
	}

	@Test void participacionEfectivaEnOtraSubastaSeRechaza() throws Exception {
		liveAuction(6106, 9106, "especial", 1000);
		enroll(6106, 3001, 5001);
		authBid("aprobado@quickbid.demo", 6106, bid(9106, "1000", 5001, 0, "other-auction"))
				.andExpect(status().isConflict())
				.andExpect(jsonPath("$.errors[0].code").value("OTHER_AUCTION_PARTICIPATION"));
	}

	@Test void limitesDeMontoYFondosSeValidan() throws Exception {
		authBid("aprobado@quickbid.demo", 6001, bid(9001, "25299", 5001, 1, "below-min"))
				.andExpect(status().isUnprocessableEntity())
				.andExpect(jsonPath("$.errors[0].code").value("BID_AMOUNT_BELOW_MINIMUM"));
		authBid("aprobado@quickbid.demo", 6001, bid(9001, "29101", 5001, 1, "above-max"))
				.andExpect(status().isUnprocessableEntity())
				.andExpect(jsonPath("$.errors[0].code").value("BID_AMOUNT_ABOVE_MAXIMUM"));
		jdbc.update("UPDATE app_medios_pago SET limite_monto=25200 WHERE id=5001");
		authBid("aprobado@quickbid.demo", 6001, bid(9001, "25300", 5001, 1, "insufficient"))
				.andExpect(status().isUnprocessableEntity())
				.andExpect(jsonPath("$.errors[0].code").value("PAYMENT_METHOD_INSUFFICIENT_FUNDS"));
	}

	@Test void pujaIgualALaActualSeRechazaComoMontoInsuficiente() throws Exception {
		authBid("aprobado@quickbid.demo", 6001, bid(9001, "25100", 5001, 1, "same-current"))
				.andExpect(status().isUnprocessableEntity())
				.andExpect(jsonPath("$.errors[0].code").value("BID_AMOUNT_BELOW_MINIMUM"));
	}

	@Test void pujaActualSeCalculaPorItemYNoPorTodaLaSubasta() throws Exception {
		liveAuction(6108, 9108, "especial", 1000);
		addItem(6108, 9109, 1000);
		jdbc.update("""
				INSERT INTO app_pujas_live(id,subasta_id,item_catalogo_id,cuenta_id,medio_pago_id,monto,moneda,estado,secuencia,version_estado,idempotency_key)
				VALUES (12650,6108,9109,3004,5006,5000,'ARS','aceptada',1,0,'other-item-high')
				""");

		authBid("oro@quickbid.demo", 6108, bid(9108, "1000", 5006, 0, "same-auction-other-item"))
				.andExpect(status().isCreated())
				.andExpect(jsonPath("$.data.itemCatalogoId").value(9108))
				.andExpect(jsonPath("$.data.mejorOfertaActual").value(1000));
	}

	@Test void usuarioNoPuedePujarSobreBienPropioConsignado() throws Exception {
		liveAuction(6109, 9109, "oro", 1000);
		jdbc.update("""
				INSERT INTO app_solicitudes_consignacion(id,cuenta_id,cliente_id,producto_id,item_catalogo_id,subasta_id,titulo,descripcion,
					categoria_sugerida,declaracion_propiedad,acepta_devolucion_con_cargo,estado)
				VALUES (16109,3004,2004,10109,9109,6109,'Bien propio','Demo','oro',true,true,'publicada')
				""");

		authBid("oro@quickbid.demo", 6109, bid(9109, "1000", 5006, 0, "own-item"))
				.andExpect(status().isForbidden())
				.andExpect(jsonPath("$.errors[0].code").value("OWN_ITEM_BID_FORBIDDEN"));
	}

	@Test void categoriaInconsistenteDevuelve422() throws Exception {
		liveAuction(6110, 9110, "diamante", 1000);
		authBid("oro@quickbid.demo", 6110, bid(9110, "1000", 5006, 0, "invalid-category"))
				.andExpect(status().isUnprocessableEntity())
				.andExpect(jsonPath("$.errors[0].code").value("INVALID_CATEGORY"));
	}

	@Test void idempotenciaRepiteRespuestaSinDuplicarYDetectaConflicto() throws Exception {
		String request = bid(9001, "25300", 5001, 1, "repeatable-key");
		String first = authBid("aprobado@quickbid.demo", 6001, request).andExpect(status().isCreated())
				.andReturn().getResponse().getContentAsString();
		Long id = Long.valueOf(JsonPath.read(first, "$.data.id").toString());
		authBid("aprobado@quickbid.demo", 6001, request).andExpect(status().isCreated())
				.andExpect(jsonPath("$.data.id").value(id))
				.andExpect(jsonPath("$.data.idempotentReplay").value(true));
		assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM app_pujas_live WHERE idempotency_key='repeatable-key'", Integer.class));
		assertEquals(1, jdbc.queryForObject("""
				SELECT COUNT(*) FROM app_reservas_medio_pago r
				JOIN app_pujas_live p ON p.id=r.puja_id
				WHERE p.idempotency_key='repeatable-key'
				""", Integer.class));
		authBid("aprobado@quickbid.demo", 6001, bid(9001, "25400", 5001, 2, "repeatable-key"))
				.andExpect(status().isConflict())
				.andExpect(jsonPath("$.errors[0].code").value("IDEMPOTENCY_CONFLICT"));
	}

	@Test void primeraPujaPuedeIgualarBaseYCreaAsistenteUnaSolaVez() throws Exception {
		liveAuction(6101, 9101, "especial", 1000);
		enroll(6101, 3004, 5006);
		authBid("oro@quickbid.demo", 6101, bid(9101, "1000", 5006, 0, "first-base"))
				.andExpect(status().isCreated());
		authBid("oro@quickbid.demo", 6101, bid(9101, "1010", 5006, 1, "second-same-bidder"))
				.andExpect(status().isCreated());
		assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM asistentes WHERE cliente=2004 AND subasta=6101", Integer.class));
		assertEquals(2, jdbc.queryForObject("SELECT COUNT(*) FROM pujos WHERE item=9101", Integer.class));
	}

	@Test void usuarioNoInscriptoPuedePujarSiCumpleLasReglasReales() throws Exception {
		liveAuction(6107, 9107, "oro", 1000);

		authBid("oro@quickbid.demo", 6107, bid(9107, "1000", 5006, 0, "without-enrollment"))
				.andExpect(status().isCreated());

		assertEquals(0, jdbc.queryForObject(
				"SELECT COUNT(*) FROM app_inscripciones_subasta WHERE subasta_id=6107 AND cuenta_id=3004",
				Integer.class));
	}

	@Test void usuarioNoInscriptoEnSubastaSeedCreaAsistenteLegacyUnaSolaVez() throws Exception {
		String request = bid(9001, "25300", 5006, 1, "seed-without-enrollment");

		authBid("oro@quickbid.demo", 6001, request)
				.andExpect(status().isCreated())
				.andExpect(jsonPath("$.data.estado").value("aceptada"))
				.andExpect(jsonPath("$.data.numeroPostor").value(2));
		authBid("oro@quickbid.demo", 6001, request)
				.andExpect(status().isCreated())
				.andExpect(jsonPath("$.data.idempotentReplay").value(true))
				.andExpect(jsonPath("$.data.numeroPostor").value(2));

		assertEquals(0, jdbc.queryForObject(
				"SELECT COUNT(*) FROM app_inscripciones_subasta WHERE subasta_id=6001 AND cuenta_id=3004",
				Integer.class));
		assertEquals(1, jdbc.queryForObject(
				"SELECT COUNT(*) FROM asistentes WHERE cliente=2004 AND subasta=6001",
				Integer.class));
		assertEquals(1, jdbc.queryForObject(
				"SELECT COUNT(*) FROM app_pujas_live WHERE idempotency_key='seed-without-enrollment'",
				Integer.class));
		assertEquals(2, jdbc.queryForObject("SELECT COUNT(*) FROM pujos WHERE item=9001", Integer.class));
	}

	@Test void categoriaOroExigeSoloUnaUnidadSobreLaMejorPuja() throws Exception {
		liveAuction(6102, 9102, "oro", 250000);
		enroll(6102, 3004, 5006);
		authBid("oro@quickbid.demo", 6102, bid(9102, "250000", 5006, 0, "gold-first"))
				.andExpect(status().isCreated());
		authBid("oro@quickbid.demo", 6102, bid(9102, "250000.99", 5006, 1, "gold-too-low"))
				.andExpect(status().isUnprocessableEntity())
				.andExpect(jsonPath("$.errors[0].code").value("BID_AMOUNT_BELOW_MINIMUM"));
		authBid("oro@quickbid.demo", 6102, bid(9102, "250001", 5006, 1, "gold-plus-one"))
				.andExpect(status().isCreated());
	}

	@Test void dosPujasConcurrentesConMismaVersionNoGeneranDosGanadoras() throws Exception {
		jdbc.update("UPDATE app_subasta_ext SET estado_operativo='finalizada' WHERE subasta_id=6001");
		liveAuction(6103, 9103, "especial", 1000);
		enroll(6103, 3001, 5001);
		enroll(6103, 3004, 5006);
		CountDownLatch ready = new CountDownLatch(2);
		CountDownLatch start = new CountDownLatch(1);
		var executor = Executors.newFixedThreadPool(2);
		try {
			Future<?> first = executor.submit(() -> concurrentBid(ready, start, 6103, 3001L, 5001L, "race-a"));
			Future<?> second = executor.submit(() -> concurrentBid(ready, start, 6103, 3004L, 5006L, "race-b"));
			ready.await();
			start.countDown();
			List<Future<?>> results = List.of(first, second);
			int rejected = 0;
			for (Future<?> result : results) {
				try {
					result.get();
				} catch (ExecutionException exception) {
					assertInstanceOf(BusinessException.class, exception.getCause());
					rejected++;
				}
			}
			assertEquals(1, rejected);
		} finally {
			executor.shutdownNow();
		}
		assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM app_pujas_live WHERE subasta_id=6103 AND estado='aceptada'", Integer.class));
		assertEquals(1L, jdbc.queryForObject("SELECT version FROM app_subasta_estado_vivo WHERE subasta_id=6103", Long.class));
		assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM pujos WHERE item=9103", Integer.class));
	}

	@Test void mismoUsuarioNoConfirmaDosPujasSimultaneasSobreLaMismaVersion() throws Exception {
		liveAuction(6104, 9104, "oro", 1000);
		enroll(6104, 3004, 5006);
		CountDownLatch ready = new CountDownLatch(2);
		CountDownLatch start = new CountDownLatch(1);
		var executor = Executors.newFixedThreadPool(2);
		try {
			Future<?> first = executor.submit(() -> concurrentBid(ready, start, 6104, 3004L, 5006L, "same-user-a"));
			Future<?> second = executor.submit(() -> concurrentBid(ready, start, 6104, 3004L, 5006L, "same-user-b"));
			ready.await();
			start.countDown();
			int rejected = 0;
			for (Future<?> result : List.of(first, second)) {
				try {
					result.get();
				} catch (ExecutionException exception) {
					assertInstanceOf(BusinessException.class, exception.getCause());
					rejected++;
				}
			}
			assertEquals(1, rejected);
		} finally {
			executor.shutdownNow();
		}
		assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM app_pujas_live WHERE subasta_id=6104 AND estado='aceptada'", Integer.class));
		assertEquals(1L, jdbc.queryForObject("SELECT version FROM app_subasta_estado_vivo WHERE subasta_id=6104", Long.class));
	}

	private void concurrentBid(CountDownLatch ready, CountDownLatch start, int auctionId, long accountId, long paymentId,
			String key) {
		try {
			ready.countDown();
			start.await();
			transactions.bid(accountId, auctionId, new BidRequest(auctionId + 3000, new BigDecimal("1000"), paymentId, 0L, key));
		} catch (InterruptedException exception) {
			Thread.currentThread().interrupt();
			throw new IllegalStateException(exception);
		}
	}

	private void liveAuction(int auctionId, int itemId, String category, int basePrice) {
		LocalDateTime start = LocalDateTime.now().minusMinutes(5);
		int productId = itemId + 1000;
		int catalogId = itemId + 2000;
		jdbc.update("INSERT INTO subastas(identificador,fecha,hora,estado,ubicacion,categoria) VALUES (?,?,?,?,?,?)",
				auctionId, start.toLocalDate(), Time.valueOf(start.toLocalTime()), "abierta", "Demo", category);
		jdbc.update("INSERT INTO app_subasta_ext(subasta_id,titulo,descripcion,moneda,segmento,estado_operativo) VALUES (?,?,?,?,?,?)",
				auctionId, "Subasta " + auctionId, "Demo", "ARS", "demo", "en_vivo");
		jdbc.update("INSERT INTO productos(identificador,\"descripcionCatalogo\") VALUES (?,?)", productId, "Item demo");
		jdbc.update("INSERT INTO catalogos(identificador,descripcion,subasta) VALUES (?,?,?)", catalogId, "Catalogo demo", auctionId);
		jdbc.update("INSERT INTO \"itemsCatalogo\"(identificador,catalogo,producto,\"precioBase\",comision,subastado) VALUES (?,?,?,?,?,?)",
				itemId, catalogId, productId, basePrice, 100, "no");
		jdbc.update("INSERT INTO app_subasta_estado_vivo(subasta_id,item_catalogo_activo_id,version,usuarios_conectados) VALUES (?,?,0,0)",
				auctionId, itemId);
	}

	private void addItem(int auctionId, int itemId, int basePrice) {
		int productId = itemId + 1000;
		int catalogId = itemId + 2000;
		jdbc.update("INSERT INTO productos(identificador,\"descripcionCatalogo\") VALUES (?,?)", productId, "Item demo " + itemId);
		jdbc.update("INSERT INTO catalogos(identificador,descripcion,subasta) VALUES (?,?,?)", catalogId, "Catalogo extra", auctionId);
		jdbc.update("INSERT INTO \"itemsCatalogo\"(identificador,catalogo,producto,\"precioBase\",comision,subastado) VALUES (?,?,?,?,?,?)",
				itemId, catalogId, productId, basePrice, 100, "no");
	}

	private void enroll(int auctionId, long accountId, long paymentId) {
		jdbc.update("INSERT INTO app_inscripciones_subasta(subasta_id,cuenta_id,medio_pago_id,estado) VALUES (?,?,?,'aprobada')",
				auctionId, accountId, paymentId);
	}

	private ResultActions authBid(String email, int auctionId, String body) throws Exception {
		return mvc.perform(post("/api/subastas/{id}/pujar", auctionId)
				.header("Authorization", "Bearer " + token(email))
				.contentType(MediaType.APPLICATION_JSON).content(body));
	}

	private ResultActions authGet(String email, int auctionId) throws Exception {
		return mvc.perform(get("/api/subastas/{id}/puja-actual", auctionId)
				.header("Authorization", "Bearer " + token(email)));
	}

	private String bid(int itemId, String amount, long paymentId, long version, String key) {
		return """
				{"itemCatalogoId":%d,"monto":%s,"medioPagoId":%d,"clientStateVersion":%d,"idempotencyKey":"%s"}
				""".formatted(itemId, amount, paymentId, version, key);
	}

	private String token(String email) throws Exception {
		String json = mvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
				.content("{\"email\":\"" + email + "\",\"clave\":\"Demo123!\"}"))
				.andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
		return JsonPath.read(json, "$.data.accessToken");
	}
}
