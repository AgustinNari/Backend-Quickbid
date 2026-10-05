package com.example.quickbid.quickbid;

import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.nullValue;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.sql.Time;
import java.time.LocalDateTime;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import javax.sql.DataSource;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.core.io.ClassPathResource;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.init.ScriptUtils;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.jdbc.Sql;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

import com.example.quickbid.quickbid.exception.BusinessException;
import com.example.quickbid.quickbid.security.AuthRateLimitService;
import com.example.quickbid.quickbid.service.AuctionTimerService;
import com.example.quickbid.quickbid.service.PurchaseService;
import com.example.quickbid.quickbid.service.PurchaseService.PaymentOutcome;
import com.example.quickbid.quickbid.service.SimulatedMailService;
import com.example.quickbid.quickbid.storage.StorageService;
import com.jayway.jsonpath.JsonPath;

@SpringBootTest(properties = "app.mail.enabled=false")
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Sql(scripts = "/auth-test-data.sql", executionPhase = Sql.ExecutionPhase.BEFORE_TEST_METHOD)
class PurchaseIntegrationTests {
	@Autowired MockMvc mvc;
	@Autowired JdbcTemplate jdbc;
	@Autowired AuthRateLimitService limits;
	@Autowired PurchaseService purchases;
	@Autowired AuctionTimerService auctionTimers;
	@Autowired SimulatedMailService mail;
	@Autowired DataSource dataSource;
	@Autowired StorageService storage;

	@BeforeEach void clearLimits() {
		limits.clear();
		mail.clear();
	}

	@Test void cierreConGanadorCreaCompraPagoYMarcaPujaLegacy() {
		jdbc.update("UPDATE productos SET duenio=7001 WHERE identificador=8001");
		var purchase = purchases.closeLot(6001, PaymentOutcome.AUTO);

		assertEquals("pagos_extra_pendientes", purchase.estado());
		assertEquals(3001L, jdbc.queryForObject("SELECT cuenta_comprador_id FROM app_compras WHERE id=?", Long.class, purchase.id()));
		assertEquals("ganadora", jdbc.queryForObject("SELECT estado FROM app_pujas_live WHERE id=12501", String.class));
		assertEquals("si", jdbc.queryForObject("SELECT ganador FROM pujos WHERE identificador=12001", String.class));
		assertEquals("si", jdbc.queryForObject("SELECT subastado FROM \"itemsCatalogo\" WHERE identificador=9001", String.class));
		assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM app_pagos WHERE compra_id=? AND estado='aprobado'", Integer.class, purchase.id()));
		assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM app_movimientos_puntos WHERE referencia_tipo='compra' AND referencia_id=?", Integer.class, purchase.id()));
		assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM app_movimientos_puntos WHERE motivo='puja_ganada' AND referencia_id=12501", Integer.class));
		assertEquals("consumida", jdbc.queryForObject("SELECT estado FROM app_reservas_medio_pago WHERE puja_id=12501", String.class));
		assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM \"registroDeSubasta\" WHERE subasta=6001 AND producto=8001 AND cliente=2001", Integer.class));
		assertEquals("2510.00", purchase.comisionComprador().toPlainString());
		assertEquals("2510.00", purchase.comisionVendedor().toPlainString());
		assertEquals("2510.00", jdbc.queryForObject("SELECT comision FROM \"registroDeSubasta\" WHERE subasta=6001 AND producto=8001", java.math.BigDecimal.class).toPlainString());
		assertTrue(delivered("lote_ganado"));
		assertTrue(delivered("pago_adjudicacion_exitoso"));
	}

	@Test void cierreSinPujasCreaCompraInternaEmpresa() {
		liveAuction(6201, 9201);
		jdbc.update("""
				UPDATE app_solicitudes_consignacion
				SET producto_id=10201,item_catalogo_id=9201,subasta_id=6201,estado='publicada',
					valor_base_propuesto=1000,comision_comprador_pct=10,comision_vendedor_pct=15
				WHERE id=16007
				""");

		var purchase = purchases.closeLot(6201, PaymentOutcome.AUTO);

		assertEquals(true, jdbc.queryForObject("SELECT comprador_empresa FROM app_compras WHERE id=?", Boolean.class, purchase.id()));
		assertEquals(null, jdbc.queryForObject("SELECT cuenta_comprador_id FROM app_compras WHERE id=?", Long.class, purchase.id()));
		assertEquals("pagos_extra_pendientes", purchase.estado());
		assertEquals("1000.00", purchase.montoAdjudicacion().toPlainString());
		assertEquals("0.00", purchase.comisionComprador().toPlainString());
		assertEquals("150.00", purchase.comisionVendedor().toPlainString());
		assertEquals("comprada_por_empresa", jdbc.queryForObject(
				"SELECT estado FROM app_solicitudes_consignacion WHERE id=16007", String.class));
		assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM app_pagos WHERE compra_id=?", Integer.class, purchase.id()));
	}

	@Test void cierreConGanadorUsaComisionesDistintasDeConsignacion() {
		jdbc.update("UPDATE productos SET duenio=7001 WHERE identificador=8001");
		jdbc.update("""
				UPDATE app_solicitudes_consignacion
				SET producto_id=8001,item_catalogo_id=9001,subasta_id=6001,estado='publicada',
					valor_base_propuesto=25000,comision_comprador_pct=10,comision_vendedor_pct=15
				WHERE id=16007
				""");

		var purchase = purchases.closeLot(6001, PaymentOutcome.AUTO);

		assertEquals("25100.00", purchase.montoAdjudicacion().toPlainString());
		assertEquals("2510.00", purchase.comisionComprador().toPlainString());
		assertEquals("3765.00", purchase.comisionVendedor().toPlainString());
		assertEquals("3765.00", jdbc.queryForObject("""
				SELECT comision FROM "registroDeSubasta" WHERE subasta=6001 AND producto=8001
				""", java.math.BigDecimal.class).toPlainString());
	}

	@Test void cierreLegacySinConsignacionMantieneComisionCompatible() {
		liveAuction(6206, 9207);

		var purchase = purchases.closeLot(6206, PaymentOutcome.AUTO);

		assertEquals("0.00", purchase.comisionComprador().toPlainString());
		assertEquals("100.00", purchase.comisionVendedor().toPlainString());
	}

	@Test void migracionV12CorrigeSoloComisionVendedorDeConsignacionesYEsIdempotente() throws Exception {
		jdbc.update("UPDATE app_solicitudes_consignacion SET comision_vendedor_pct=15 WHERE id=16007");
		jdbc.update("""
				INSERT INTO app_solicitudes_consignacion(
					id,cuenta_id,cliente_id,producto_id,item_catalogo_id,subasta_id,titulo,descripcion,segmento,
					categoria_sugerida,declaracion_propiedad,acepta_devolucion_con_cargo,estado,
					comision_comprador_pct,comision_vendedor_pct)
				VALUES (16009,3004,2004,8007,9006,6004,'Compra empresa','Backfill empresa','arte','oro',
					true,true,'comprada_por_empresa',10,20)
				""");
		jdbc.update("""
				INSERT INTO app_compras(
					id,subasta_id,item_catalogo_id,producto_id,comprador_empresa,monto_adjudicacion,moneda,estado,
					comision_comprador,comision_vendedor)
				VALUES (13009,6004,9006,8007,true,250000,'ARS','pagos_extra_pendientes',0,25000)
				""");

		runMigration("db/migration/V12__fix_seller_commission_backfill.sql");
		runMigration("db/migration/V12__fix_seller_commission_backfill.sql");

		assertEquals("9500.00", commission(13002, "comision_comprador"));
		assertEquals("14250.00", commission(13002, "comision_vendedor"));
		assertEquals("0.00", commission(13009, "comision_comprador"));
		assertEquals("50000.00", commission(13009, "comision_vendedor"));
		assertEquals("15000.00", commission(13001, "comision_comprador"));
		assertEquals("15000.00", commission(13001, "comision_vendedor"));
	}

	@Test void cierreDeLoteReintentadoEsIdempotenteYNoDuplicaConsumo() {
		var first = purchases.closeLot(6001, PaymentOutcome.AUTO);
		var consumed = jdbc.queryForObject("SELECT consumo_actual FROM app_medios_pago WHERE id=5001", java.math.BigDecimal.class);
		var second = purchases.closeLot(6001, PaymentOutcome.AUTO);
		assertEquals(first.id(), second.id());
		assertEquals(0, consumed.compareTo(jdbc.queryForObject("SELECT consumo_actual FROM app_medios_pago WHERE id=5001", java.math.BigDecimal.class)));
		assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM app_compras WHERE item_catalogo_id=9001", Integer.class));
		assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM app_pagos WHERE compra_id=? AND estado='aprobado'", Integer.class, first.id()));
		assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM app_movimientos_puntos WHERE motivo='puja_ganada' AND referencia_id=12501", Integer.class));
	}

	@Test void fallaAutomaticaGeneraMultaDelDiezPorCientoYRestriccion() {
		var purchase = purchases.closeLot(6001, PaymentOutcome.FAILURE);

		assertEquals("multa_activa", purchase.estado());
		assertEquals("liberada", jdbc.queryForObject("SELECT estado FROM app_reservas_medio_pago WHERE puja_id=12501", String.class));
		assertEquals("restriccion_multa", jdbc.queryForObject("SELECT estado FROM app_cuentas WHERE id=3001", String.class));
		assertEquals("2510.00", jdbc.queryForObject("SELECT monto FROM app_multas WHERE compra_id=?", java.math.BigDecimal.class, purchase.id()).toPlainString());
		assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM app_pagos WHERE compra_id=? AND estado='rechazado'", Integer.class, purchase.id()));
		assertTrue(delivered("multa_generada"));
	}

	@Test void pagarConMultaExitosoReactivaCuentaSiNoQuedanOtras() {
		var payment = purchases.simulateSuccessfulFine(3002L, 13001L, 5004L);

		assertEquals("aprobado", payment.estado());
		assertEquals("pagos_extra_pendientes", payment.compraEstado());
		assertEquals("pagada", jdbc.queryForObject("SELECT estado FROM app_multas WHERE id=14001", String.class));
		assertEquals("activa", jdbc.queryForObject("SELECT estado FROM app_cuentas WHERE id=3002", String.class));
		assertTrue(delivered("multa_pagada"));
	}

	@Test void pagarConMultaFallidoNoDuplicaMulta() {
		var payment = purchases.simulateFailedFine(3002L, 13001L, 5004L);

		assertEquals("rechazado", payment.estado());
		assertEquals("multa_activa", payment.compraEstado());
		assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM app_multas WHERE compra_id=13001", Integer.class));
	}

	@Test void multaVencidaBloqueaPermanentemente() {
		purchases.expireFine(14001L, true);

		assertEquals("vencida", jdbc.queryForObject("SELECT estado FROM app_multas WHERE id=14001", String.class));
		assertEquals("bloqueada_permanente", jdbc.queryForObject("SELECT estado FROM app_cuentas WHERE id=3002", String.class));
		assertTrue(delivered("multa_vencida"));
	}

	@Test void listadoSoloIncluyeComprasPropiasYDetalleAjenoEs403() throws Exception {
		auth(get("/api/compras"), "aprobado@quickbid.demo").andExpect(status().isOk())
				.andExpect(jsonPath("$.data.content", hasSize(1)))
				.andExpect(jsonPath("$.data.content[0].id").value(13002));
		auth(get("/api/compras?estado=pagos_extra_pendientes"), "aprobado@quickbid.demo").andExpect(status().isOk())
				.andExpect(jsonPath("$.data.content", hasSize(1)))
				.andExpect(jsonPath("$.data.content[0].id").value(13002));
		auth(get("/api/compras?estado=multa_activa"), "aprobado@quickbid.demo").andExpect(status().isOk())
				.andExpect(jsonPath("$.data.content", hasSize(0)));
		auth(get("/api/compras?estado=inexistente"), "aprobado@quickbid.demo")
				.andExpect(status().isUnprocessableEntity())
				.andExpect(jsonPath("$.errors[0].code").value("INVALID_FILTER"));
		auth(get("/api/compras/13001"), "aprobado@quickbid.demo").andExpect(status().isForbidden())
				.andExpect(jsonPath("$.errors[0].code").value("RESOURCE_NOT_OWNED"));
	}

	@Test void compraInexistenteDevuelve404Uniforme() throws Exception {
		auth(get("/api/compras/99999"), "aprobado@quickbid.demo")
				.andExpect(status().isNotFound())
				.andExpect(jsonPath("$.data").value(nullValue()))
				.andExpect(jsonPath("$.errors[0].code").value("RESOURCE_NOT_FOUND"));
	}

	@Test void previewEntregaCotizaEnvioYRetirosSinMutar() throws Exception {
		auth(post("/api/compras/13002/entrega/preview").contentType(MediaType.APPLICATION_JSON)
				.content("{\"tipo\":\"envio\",\"direccionEnvioId\":5101}"), "aprobado@quickbid.demo")
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.data.tipo").value("envio"))
				.andExpect(jsonPath("$.data.costoEnvio").value(5000))
				.andExpect(jsonPath("$.data.moneda").value("ARS"))
				.andExpect(jsonPath("$.data.comisionComprador").value(9500))
				.andExpect(jsonPath("$.data.totalEstimado").value(14500));
		assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM app_entregas WHERE compra_id=13002", Integer.class));

		auth(post("/api/compras/13002/entrega/preview").contentType(MediaType.APPLICATION_JSON)
				.content("{\"tipo\":\"retiro\"}"), "aprobado@quickbid.demo")
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.data.tipo").value("retiro"))
				.andExpect(jsonPath("$.data.costoEnvio").value(0))
				.andExpect(jsonPath("$.data.totalEstimado").value(9500));
		assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM app_entregas WHERE compra_id=13002", Integer.class));
	}

	@Test void previewEntregaInexistenteOAjenaDevuelveErroresUniformes() throws Exception {
		auth(post("/api/compras/99999/entrega/preview").contentType(MediaType.APPLICATION_JSON)
				.content("{\"tipo\":\"retiro\"}"), "aprobado@quickbid.demo")
				.andExpect(status().isNotFound())
				.andExpect(jsonPath("$.errors[0].code").value("RESOURCE_NOT_FOUND"));
		auth(post("/api/compras/13001/entrega/preview").contentType(MediaType.APPLICATION_JSON)
				.content("{\"tipo\":\"retiro\"}"), "aprobado@quickbid.demo")
				.andExpect(status().isForbidden())
				.andExpect(jsonPath("$.errors[0].code").value("RESOURCE_NOT_OWNED"));
	}

	@Test void entregaPorEnvioYPagoExtrasDejanEntregaPendiente() throws Exception {
		auth(put("/api/compras/13002/entrega").contentType(MediaType.APPLICATION_JSON)
				.content("{\"tipo\":\"envio\",\"direccionEnvioId\":5101}"), "aprobado@quickbid.demo")
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.data.costoEnvio").value(5000))
				.andExpect(jsonPath("$.data.perdioCoberturaSeguro").value(false));
		auth(post("/api/compras/13002/pagar").contentType(MediaType.APPLICATION_JSON)
				.content("{\"medioPagoId\":5001,\"idempotencyKey\":\"extras-envio\"}"), "aprobado@quickbid.demo")
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.data.estado").value("aprobado"))
				.andExpect(jsonPath("$.data.monto").value(14500))
				.andExpect(jsonPath("$.data.compraEstado").value("entrega_pendiente"))
				.andExpect(jsonPath("$.data.numeroTarjeta").doesNotExist())
				.andExpect(jsonPath("$.data.cvv").doesNotExist())
				.andExpect(jsonPath("$.data.hashIdentificador").doesNotExist());
		auth(get("/api/compras/13002"), "aprobado@quickbid.demo")
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.data.entrega.direccionSnapshotJson", containsString("Av. Demo")))
				.andExpect(jsonPath("$.data.entrega.direccionSnapshotAt").exists());
		auth(put("/api/compras/13002/entrega").contentType(MediaType.APPLICATION_JSON)
				.content("{\"tipo\":\"retiro\"}"), "aprobado@quickbid.demo")
				.andExpect(status().isConflict())
				.andExpect(jsonPath("$.errors[0].code").value("INVALID_STATE_TRANSITION"));
		auth(get("/api/compras/13002/documentos"), "aprobado@quickbid.demo")
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.data[0].tipo").value("factura_compra"));
	}

	@Test void entregaEnEstadoInvalidoDevuelve409Uniforme() throws Exception {
		auth(put("/api/compras/13001/entrega").contentType(MediaType.APPLICATION_JSON)
				.content("{\"tipo\":\"retiro\"}"), "multa@quickbid.demo")
				.andExpect(status().isConflict())
				.andExpect(jsonPath("$.data").value(nullValue()))
				.andExpect(jsonPath("$.errors[0].code").value("INVALID_STATE_TRANSITION"));
	}

	@Test void retiroPierdeCoberturaYPuedeCompletarseInternamente() throws Exception {
		auth(put("/api/compras/13002/entrega").contentType(MediaType.APPLICATION_JSON)
				.content("{\"tipo\":\"retiro\"}"), "aprobado@quickbid.demo")
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.data.costoEnvio").value(0))
				.andExpect(jsonPath("$.data.perdioCoberturaSeguro").value(true));
		auth(post("/api/compras/13002/pagar").contentType(MediaType.APPLICATION_JSON)
				.content("{\"medioPagoId\":5001,\"idempotencyKey\":\"extras-retiro\"}"), "aprobado@quickbid.demo")
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.data.compraEstado").value("retiro_pendiente"));

		purchases.completePickup(13002L);
		assertEquals("completada", jdbc.queryForObject("SELECT estado FROM app_compras WHERE id=13002", String.class));
	}

	@Test void pagoExtrasEsIdempotenteYDetectaCambioDeMedio() throws Exception {
		jdbc.update("""
				INSERT INTO app_medios_pago(id,cuenta_id,tipo,moneda,estado,principal,nacional,alias_visible,titular,
					hash_identificador,limite_monto,consumo_actual,verificado_hasta)
				VALUES (5097,3001,'tarjeta','ARS','verificado',false,true,'Alternativa','Ana','hash-alternative',
					500000,0,DATEADD('DAY',7,CURRENT_TIMESTAMP))
				""");
		auth(put("/api/compras/13002/entrega").contentType(MediaType.APPLICATION_JSON)
				.content("{\"tipo\":\"retiro\"}"), "aprobado@quickbid.demo").andExpect(status().isOk());
		String payment = "{\"medioPagoId\":5001,\"idempotencyKey\":\"extras-repeatable\"}";
		auth(post("/api/compras/13002/pagar").contentType(MediaType.APPLICATION_JSON).content(payment),
				"aprobado@quickbid.demo").andExpect(status().isOk())
				.andExpect(jsonPath("$.data.idempotentReplay").value(false));
		auth(post("/api/compras/13002/pagar").contentType(MediaType.APPLICATION_JSON).content(payment),
				"aprobado@quickbid.demo").andExpect(status().isOk())
				.andExpect(jsonPath("$.data.idempotentReplay").value(true));
		assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM app_pagos WHERE idempotency_key='extras-repeatable'", Integer.class));
		auth(post("/api/compras/13002/pagar").contentType(MediaType.APPLICATION_JSON)
				.content("{\"medioPagoId\":5097,\"idempotencyKey\":\"extras-repeatable\"}"), "aprobado@quickbid.demo")
				.andExpect(status().isConflict())
				.andExpect(jsonPath("$.errors[0].code").value("IDEMPOTENCY_CONFLICT"));
	}

	@Test void pagoConMedioInexistenteOAjenoDevuelveErroresUniformes() throws Exception {
		auth(put("/api/compras/13002/entrega").contentType(MediaType.APPLICATION_JSON)
				.content("{\"tipo\":\"retiro\"}"), "aprobado@quickbid.demo").andExpect(status().isOk());
		auth(post("/api/compras/13002/pagar").contentType(MediaType.APPLICATION_JSON)
				.content("{\"medioPagoId\":9999,\"idempotencyKey\":\"missing-payment\"}"), "aprobado@quickbid.demo")
				.andExpect(status().isNotFound())
				.andExpect(jsonPath("$.data").value(nullValue()))
				.andExpect(jsonPath("$.errors[0].code").value("RESOURCE_NOT_FOUND"));
		auth(post("/api/compras/13002/pagar").contentType(MediaType.APPLICATION_JSON)
				.content("{\"medioPagoId\":5006,\"idempotencyKey\":\"foreign-payment\"}"), "aprobado@quickbid.demo")
				.andExpect(status().isForbidden())
				.andExpect(jsonPath("$.data").value(nullValue()))
				.andExpect(jsonPath("$.errors[0].code").value("RESOURCE_NOT_OWNED"));
	}

	@Test void pagoDuplicadoConNuevaClaveSeRechazaPorEstado() throws Exception {
		auth(put("/api/compras/13002/entrega").contentType(MediaType.APPLICATION_JSON)
				.content("{\"tipo\":\"retiro\"}"), "aprobado@quickbid.demo").andExpect(status().isOk());
		auth(post("/api/compras/13002/pagar").contentType(MediaType.APPLICATION_JSON)
				.content("{\"medioPagoId\":5001,\"idempotencyKey\":\"extras-first\"}"), "aprobado@quickbid.demo")
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.data.estado").value("aprobado"));
		auth(post("/api/compras/13002/pagar").contentType(MediaType.APPLICATION_JSON)
				.content("{\"medioPagoId\":5001,\"idempotencyKey\":\"extras-second\"}"), "aprobado@quickbid.demo")
				.andExpect(status().isConflict())
				.andExpect(jsonPath("$.errors[0].code").value("INVALID_STATE_TRANSITION"));
	}

	@Test void documentosDevuelveSoloComprobantesDisponiblesDeCompraPropia() throws Exception {
		jdbc.update("""
				INSERT INTO app_archivos(id,tipo_contexto,filename_original,content_type,size_bytes,storage_path,checksum)
				VALUES (5201,'documento_compra','factura-demo.pdf','application/pdf',1234,'demo/factura.pdf','checksum')
				""");
		jdbc.update("""
				INSERT INTO app_archivos(id,tipo_contexto,filename_original,content_type,size_bytes,storage_path,checksum)
				VALUES (5202,'documento_compra','pendiente-demo.pdf','application/pdf',1234,'demo/pendiente.pdf','checksum-pending')
				""");
		jdbc.update("""
				INSERT INTO app_archivos(id,tipo_contexto,filename_original,content_type,size_bytes,storage_path,checksum)
				VALUES (5203,'documento_compra','rechazado-demo.pdf','application/pdf',1234,'demo/rechazado.pdf','checksum-rejected')
				""");
		jdbc.update("""
				INSERT INTO app_archivos(id,tipo_contexto,filename_original,content_type,size_bytes,storage_path,checksum)
				VALUES (5204,'documento_compra','factura-latest.pdf','application/pdf',1234,'demo/factura-latest.pdf','checksum-latest')
				""");
		jdbc.update("INSERT INTO app_documentos(id,tipo,referencia_tipo,referencia_id,archivo_id,estado) VALUES (5301,'factura_compra','compra',13002,5201,'disponible')");
		jdbc.update("INSERT INTO app_documentos(id,tipo,referencia_tipo,referencia_id,archivo_id,estado) VALUES (5302,'comprobante_interno','compra',13002,5202,'pendiente')");
		jdbc.update("INSERT INTO app_documentos(id,tipo,referencia_tipo,referencia_id,archivo_id,estado) VALUES (5303,'recibo_rechazado','compra',13002,5203,'rechazado')");
		jdbc.update("INSERT INTO app_documentos(id,tipo,referencia_tipo,referencia_id,archivo_id,estado) VALUES (5304,'factura_compra','compra',13002,5204,'disponible')");

		mvc.perform(get("/api/compras/13002/documentos")).andExpect(status().isUnauthorized())
				.andExpect(jsonPath("$.data").value(nullValue()))
				.andExpect(jsonPath("$.errors[0].code").value("UNAUTHORIZED"));
		mvc.perform(get("/api/compras/13002/documentos").header("Authorization", "Bearer token-invalido"))
				.andExpect(status().isUnauthorized())
				.andExpect(jsonPath("$.data").value(nullValue()))
				.andExpect(jsonPath("$.errors[0].code").value("UNAUTHORIZED"));

		String response = auth(get("/api/compras/13002/documentos"), "aprobado@quickbid.demo").andExpect(status().isOk())
				.andExpect(jsonPath("$.data", hasSize(1)))
				.andExpect(jsonPath("$.data[0].filename").value("factura-latest.pdf"))
				.andReturn().getResponse().getContentAsString();
		assertFalse(response.contains("pendiente-demo.pdf"));
		assertFalse(response.contains("rechazado-demo.pdf"));
		assertFalse(response.contains("demo/factura.pdf"));
		assertFalse(response.contains("storage_path"));
		assertFalse(response.contains("checksum"));
		assertFalse(response.contains("password"));
		assertFalse(response.contains("token"));
		auth(get("/api/compras/13002/documentos"), "multa@quickbid.demo").andExpect(status().isForbidden())
				.andExpect(jsonPath("$.data").value(nullValue()))
				.andExpect(jsonPath("$.errors[0].code").value("RESOURCE_NOT_OWNED"));
		auth(get("/api/compras/99999/documentos"), "aprobado@quickbid.demo").andExpect(status().isNotFound())
				.andExpect(jsonPath("$.data").value(nullValue()))
				.andExpect(jsonPath("$.errors[0].code").value("RESOURCE_NOT_FOUND"));
	}

	@Test void descargaDocumentoRealValidaOwnershipHeadersYContenido() throws Exception {
		byte[] pdf = "%PDF-1.4\ndocumento real\n%%EOF\n".getBytes(StandardCharsets.ISO_8859_1);
		var stored = storage.store("factura-real.pdf", "application/pdf", new ByteArrayInputStream(pdf));
		jdbc.update("""
				INSERT INTO app_archivos(id,owner_cuenta_id,tipo_contexto,filename_original,content_type,size_bytes,storage_path,checksum)
				VALUES (5291,3001,'documento_compra','factura-real.pdf','application/pdf',?,?,?)
				""", stored.sizeBytes(), stored.storagePath(), stored.checksum());
		jdbc.update("INSERT INTO app_documentos(id,tipo,referencia_tipo,referencia_id,archivo_id,estado) VALUES (5391,'factura_compra','compra',13002,5291,'disponible')");

		auth(get("/api/compras/13002/documentos"), "aprobado@quickbid.demo")
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.data[0].downloadAvailable").value(true))
				.andExpect(jsonPath("$.data[0].downloadUrl")
						.value("/api/compras/13002/documentos/5391/descargar"));
		auth(get("/api/compras/13002/documentos/5391/descargar"), "aprobado@quickbid.demo")
				.andExpect(status().isOk())
				.andExpect(content().contentType("application/pdf"))
				.andExpect(header().string("Content-Disposition", containsString("factura-real.pdf")))
				.andExpect(content().bytes(pdf));
		auth(get("/api/compras/13002/documentos/5391/descargar"), "multa@quickbid.demo")
				.andExpect(status().isForbidden())
				.andExpect(jsonPath("$.errors[0].code").value("RESOURCE_NOT_OWNED"));
		auth(get("/api/compras/13002/documentos/99999/descargar"), "aprobado@quickbid.demo")
				.andExpect(status().isNotFound());
	}

	@Test void descargaDocumentoFaltanteOConTraversalSeTrataComoNoDisponible() throws Exception {
		jdbc.update("""
				INSERT INTO app_archivos(id,owner_cuenta_id,tipo_contexto,filename_original,content_type,size_bytes,storage_path,checksum)
				VALUES (5292,3001,'documento_compra','faltante.pdf','application/pdf',20,'missing/faltante.pdf','missing')
				""");
		jdbc.update("INSERT INTO app_documentos(id,tipo,referencia_tipo,referencia_id,archivo_id,estado) VALUES (5392,'faltante','compra',13002,5292,'disponible')");
		jdbc.update("""
				INSERT INTO app_archivos(id,owner_cuenta_id,tipo_contexto,filename_original,content_type,size_bytes,storage_path,checksum)
				VALUES (5293,3001,'documento_compra','traversal.pdf','application/pdf',20,'../pom.xml','traversal')
				""");
		jdbc.update("INSERT INTO app_documentos(id,tipo,referencia_tipo,referencia_id,archivo_id,estado) VALUES (5393,'traversal','compra',13002,5293,'disponible')");

		auth(get("/api/compras/13002/documentos"), "aprobado@quickbid.demo")
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.data[0].downloadAvailable").value(false));
		auth(get("/api/compras/13002/documentos/5392/descargar"), "aprobado@quickbid.demo")
				.andExpect(status().isNotFound());
		auth(get("/api/compras/13002/documentos/5393/descargar"), "aprobado@quickbid.demo")
				.andExpect(status().isNotFound());
	}

	@Test void pagarConMultaPublicoFuncionaParaUsuarioRestringido() throws Exception {
		auth(post("/api/compras/13001/pagar-con-multa").contentType(MediaType.APPLICATION_JSON)
				.content("{\"medioPagoId\":5004,\"idempotencyKey\":\"fine-public-failed\"}"), "multa@quickbid.demo")
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.data.estado").value("rechazado"));
		assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM app_multas WHERE compra_id=13001", Integer.class));
	}

	@Test void pagarConMultaPublicoExitosoReactivaUsuarioRestringido() throws Exception {
		jdbc.update("UPDATE app_medios_pago SET limite_monto=400000 WHERE id=5004");
		auth(post("/api/compras/13001/pagar-con-multa").contentType(MediaType.APPLICATION_JSON)
				.content("{\"medioPagoId\":5004,\"idempotencyKey\":\"fine-public-ok\"}"), "multa@quickbid.demo")
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.data.estado").value("aprobado"))
				.andExpect(jsonPath("$.data.monto").value(165000))
				.andExpect(jsonPath("$.data.compraEstado").value("pagos_extra_pendientes"));
		assertEquals("activa", jdbc.queryForObject("SELECT estado FROM app_cuentas WHERE id=3002", String.class));
		auth(get("/api/compras/13001/documentos"), "multa@quickbid.demo")
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.data[0].tipo").value("recibo_multa"))
				.andExpect(jsonPath("$.data[0].downloadAvailable").value(true));
		Long documentId = jdbc.queryForObject("""
				SELECT d.id FROM app_documentos d JOIN app_archivos a ON a.id=d.archivo_id
				WHERE d.referencia_tipo='compra' AND d.referencia_id=13001 AND d.tipo='recibo_multa'
				""", Long.class);
		byte[] persisted = jdbc.queryForObject("""
				SELECT a.content_bytes FROM app_documentos d JOIN app_archivos a ON a.id=d.archivo_id
				WHERE d.id=?
				""", byte[].class, documentId);
		assertTrue(persisted != null);
		String pdfText = new String(persisted, StandardCharsets.ISO_8859_1);
		assertTrue(pdfText.startsWith("%PDF"));
		assertTrue(pdfText.contains("Referencia: COMPRA-13001"));
		assertTrue(pdfText.contains("Artículo:"));
		assertTrue(pdfText.contains("Multa:"));
		assertTrue(pdfText.contains("Total documentado:"));
		assertTrue(pdfText.contains("ARS"));
		auth(get("/api/compras/13001/documentos/" + documentId + "/descargar"), "multa@quickbid.demo")
				.andExpect(status().isOk())
				.andExpect(result -> assertTrue(new String(result.getResponse().getContentAsByteArray(),
						StandardCharsets.ISO_8859_1).startsWith("%PDF")));
	}

	@Test void pagoDeExtrasConsideraReservasActivasDelMedio() throws Exception {
		jdbc.update("UPDATE app_medios_pago SET limite_monto=30000,consumo_actual=0 WHERE id=5001");
		auth(put("/api/compras/13002/entrega").contentType(MediaType.APPLICATION_JSON)
				.content("{\"tipo\":\"retiro\"}"), "aprobado@quickbid.demo").andExpect(status().isOk());
		auth(post("/api/compras/13002/pagar").contentType(MediaType.APPLICATION_JSON)
				.content("{\"medioPagoId\":5001,\"idempotencyKey\":\"extras-with-reservation\"}"),
				"aprobado@quickbid.demo")
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.data.estado").value("rechazado"))
				.andExpect(jsonPath("$.data.errorCodigo").value("INSUFFICIENT_FUNDS_OR_LIMIT"));
	}

	@Test void cierreDeSubastaYAbandonoQuedanDisponiblesComoServiciosInternos() {
		liveAuction(6203, 9203);
		var purchase = purchases.closeLot(6203, PaymentOutcome.AUTO);
		purchases.closeAuction(6203);
		assertEquals("finalizada", jdbc.queryForObject("SELECT estado_operativo FROM app_subasta_ext WHERE subasta_id=6203", String.class));
		assertEquals("abierta", jdbc.queryForObject("SELECT estado FROM subastas WHERE identificador=6203", String.class));

		purchases.abandon(purchase.id(), false);
		assertEquals("abandonada_por_incumplimiento_pago",
				jdbc.queryForObject("SELECT estado FROM app_compras WHERE id=?", String.class, purchase.id()));
	}

	@Test void comprasSinTokenDevuelve401Uniforme() throws Exception {
		mvc.perform(get("/api/compras")).andExpect(status().isUnauthorized())
				.andExpect(jsonPath("$.data").value(nullValue()))
				.andExpect(jsonPath("$.errors[0].code").value("UNAUTHORIZED"));
	}

	@Test void comprasConTokenInvalidoDevuelve401Uniforme() throws Exception {
		mvc.perform(get("/api/compras").header("Authorization", "Bearer token-invalido"))
				.andExpect(status().isUnauthorized())
				.andExpect(jsonPath("$.data").value(nullValue()))
				.andExpect(jsonPath("$.message").exists())
				.andExpect(jsonPath("$.errors").isArray());
	}

	@Test void dosCierresConcurrentesNoDuplicanCompra() throws Exception {
		liveAuction(6202, 9202);
		CountDownLatch ready = new CountDownLatch(2);
		CountDownLatch start = new CountDownLatch(1);
		var executor = Executors.newFixedThreadPool(2);
		try {
			Future<?> first = executor.submit(() -> close(ready, start, 6202));
			Future<?> second = executor.submit(() -> close(ready, start, 6202));
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
			assertEquals(0, rejected);
		} finally {
			executor.shutdownNow();
		}
		assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM app_compras WHERE item_catalogo_id=9202", Integer.class));
	}

	@Test void timerCierraLoteTrasSesentaSegundosYNoDuplicaCierre() {
		liveAuction(6204, 9204);
		jdbc.update("UPDATE app_subasta_estado_vivo SET lote_finaliza_estimado_at=DATEADD('SECOND',-1,CURRENT_TIMESTAMP) WHERE subasta_id=6204");

		var first = auctionTimers.processDueTimers();
		var second = auctionTimers.processDueTimers();

		assertEquals(1, first.lotesCerrados());
		assertEquals(0, second.lotesCerrados());
		assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM app_compras WHERE item_catalogo_id=9204", Integer.class));
		assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM app_subasta_estado_vivo WHERE subasta_id=6204 AND subasta_finaliza_programado_at IS NOT NULL", Integer.class));
	}

	@Test void timerProgramaYActivaSiguienteLoteAntesDeFinalizarSubasta() {
		liveAuction(6205, 9205);
		addItem(6205, 9206);
		jdbc.update("UPDATE app_subasta_estado_vivo SET lote_finaliza_estimado_at=DATEADD('SECOND',-1,CURRENT_TIMESTAMP) WHERE subasta_id=6205");

		var close = auctionTimers.processDueTimers();
		assertEquals(1, close.lotesCerrados());
		assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM app_subasta_estado_vivo WHERE subasta_id=6205 AND proximo_lote_programado_at IS NOT NULL", Integer.class));

		jdbc.update("UPDATE app_subasta_estado_vivo SET proximo_lote_programado_at=DATEADD('SECOND',-1,CURRENT_TIMESTAMP) WHERE subasta_id=6205");
		var next = auctionTimers.processDueTimers();
		assertEquals(1, next.lotesActivados());
		assertEquals(9206, jdbc.queryForObject("SELECT item_catalogo_activo_id FROM app_subasta_estado_vivo WHERE subasta_id=6205", Integer.class));

		jdbc.update("UPDATE \"itemsCatalogo\" SET subastado='si' WHERE identificador=9206");
		jdbc.update("UPDATE app_subasta_estado_vivo SET item_catalogo_activo_id=NULL,subasta_finaliza_programado_at=DATEADD('SECOND',-1,CURRENT_TIMESTAMP) WHERE subasta_id=6205");
		var finish = auctionTimers.processDueTimers();
		var repeated = auctionTimers.processDueTimers();
		assertEquals(1, finish.subastasFinalizadas());
		assertEquals(0, repeated.subastasFinalizadas());
		assertEquals("finalizada", jdbc.queryForObject("SELECT estado_operativo FROM app_subasta_ext WHERE subasta_id=6205", String.class));
		assertEquals("abierta", jdbc.queryForObject("SELECT estado FROM subastas WHERE identificador=6205", String.class));
		assertEquals(1, jdbc.queryForObject("""
				SELECT COUNT(*) FROM app_subasta_estado_vivo WHERE subasta_id=6205
				  AND retencion_hasta IS NULL AND lote_finaliza_estimado_at IS NULL
				  AND proximo_lote_programado_at IS NULL AND subasta_finaliza_programado_at IS NULL
				""", Integer.class));
	}

	private void close(CountDownLatch ready, CountDownLatch start, int auctionId) {
		try {
			ready.countDown();
			start.await();
			purchases.closeLot(auctionId, PaymentOutcome.AUTO);
		} catch (InterruptedException exception) {
			Thread.currentThread().interrupt();
			throw new IllegalStateException(exception);
		}
	}

	private boolean delivered(String purpose) {
		return mail.deliveries().stream().anyMatch(delivery ->
				delivery.kind().equals("notification") && delivery.purpose().equals(purpose));
	}

	private void liveAuction(int auctionId, int itemId) {
		LocalDateTime start = LocalDateTime.now().minusMinutes(5);
		int productId = itemId + 1000;
		int catalogId = itemId + 2000;
		jdbc.update("INSERT INTO subastas(identificador,fecha,hora,estado,ubicacion,categoria) VALUES (?,?,?,?,?,?)",
				auctionId, start.toLocalDate(), Time.valueOf(start.toLocalTime()), "abierta", "Demo", "especial");
		jdbc.update("INSERT INTO app_subasta_ext(subasta_id,titulo,descripcion,moneda,segmento,estado_operativo) VALUES (?,?,?,?,?,?)",
				auctionId, "Subasta " + auctionId, "Demo", "ARS", "demo", "en_vivo");
		jdbc.update("INSERT INTO productos(identificador,\"descripcionCatalogo\") VALUES (?,?)", productId, "Item demo");
		jdbc.update("INSERT INTO catalogos(identificador,descripcion,subasta) VALUES (?,?,?)", catalogId, "Catalogo demo", auctionId);
		jdbc.update("INSERT INTO \"itemsCatalogo\"(identificador,catalogo,producto,\"precioBase\",comision,subastado) VALUES (?,?,?,?,?,?)",
				itemId, catalogId, productId, 1000, 100, "no");
		jdbc.update("INSERT INTO app_subasta_estado_vivo(subasta_id,item_catalogo_activo_id,version,usuarios_conectados) VALUES (?,?,0,0)",
				auctionId, itemId);
	}

	private void addItem(int auctionId, int itemId) {
		int productId = itemId + 1000;
		int catalogId = itemId + 2000;
		jdbc.update("INSERT INTO productos(identificador,\"descripcionCatalogo\") VALUES (?,?)", productId, "Item demo " + itemId);
		jdbc.update("INSERT INTO catalogos(identificador,descripcion,subasta) VALUES (?,?,?)", catalogId, "Catalogo extra", auctionId);
		jdbc.update("INSERT INTO \"itemsCatalogo\"(identificador,catalogo,producto,\"precioBase\",comision,subastado) VALUES (?,?,?,?,?,?)",
				itemId, catalogId, productId, 1000, 100, "no");
	}

	private void runMigration(String path) throws Exception {
		try (var connection = dataSource.getConnection()) {
			ScriptUtils.executeSqlScript(connection, new ClassPathResource(path));
		}
	}

	private String commission(long purchaseId, String column) {
		return jdbc.queryForObject("SELECT " + column + " FROM app_compras WHERE id=?",
				java.math.BigDecimal.class, purchaseId).toPlainString();
	}

	private ResultActions auth(org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder builder,
			String email) throws Exception {
		return mvc.perform(builder.header("Authorization", "Bearer " + token(email)));
	}

	private String token(String email) throws Exception {
		String json = mvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
				.content("{\"email\":\"" + email + "\",\"clave\":\"Demo123!\"}"))
				.andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
		return JsonPath.read(json, "$.data.accessToken");
	}
}
