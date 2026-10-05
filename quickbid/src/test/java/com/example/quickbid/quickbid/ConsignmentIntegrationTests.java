package com.example.quickbid.quickbid;

import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.nullValue;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.jdbc.Sql;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.web.multipart.MultipartFile;

import com.example.quickbid.quickbid.dto.request.ConsignmentAgreementAcceptanceRequest;
import com.example.quickbid.quickbid.dto.request.ConsignmentReturnPaymentRequest;
import com.example.quickbid.quickbid.dto.request.ConsignmentReturnRequest;
import com.example.quickbid.quickbid.exception.BusinessException;
import com.example.quickbid.quickbid.security.AuthRateLimitService;
import com.example.quickbid.quickbid.service.ConsignmentService;
import com.example.quickbid.quickbid.storage.StorageService;
import com.jayway.jsonpath.JsonPath;

@SpringBootTest(properties = "app.mail.enabled=false")
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Sql(scripts = "/auth-test-data.sql", executionPhase = Sql.ExecutionPhase.BEFORE_TEST_METHOD)
class ConsignmentIntegrationTests {
	@Autowired MockMvc mvc;
	@Autowired JdbcTemplate jdbc;
	@Autowired AuthRateLimitService limits;
	@Autowired ConsignmentService consignments;
	@Autowired StorageService storage;

	@BeforeEach void clearLimits() {
		limits.clear();
	}

	@Test void requisitosPermitenCuentaActivaYCuentaConRestriccionDeMulta() throws Exception {
		request(get("/api/consignaciones/requisitos"), "oro@quickbid.demo").andExpect(status().isOk())
				.andExpect(jsonPath("$.data.puedeContinuar").value(true));
		request(get("/api/consignaciones/requisitos"), "multa@quickbid.demo").andExpect(status().isOk())
				.andExpect(jsonPath("$.data.puedeContinuar").value(true));
	}

	@Test void requisitosSinTokenOTokenInvalidoDevuelve401Uniforme() throws Exception {
		mvc.perform(get("/api/consignaciones/requisitos")).andExpect(status().isUnauthorized())
				.andExpect(jsonPath("$.data").value(nullValue()))
				.andExpect(jsonPath("$.errors[0].code").value("UNAUTHORIZED"));
		mvc.perform(get("/api/consignaciones/requisitos").header("Authorization", "Bearer token-invalido"))
				.andExpect(status().isUnauthorized())
				.andExpect(jsonPath("$.data").value(nullValue()))
				.andExpect(jsonPath("$.message").exists())
				.andExpect(jsonPath("$.errors").isArray());
	}

	@Test void cuentaBancariaPendientePermiteCrearConsignacionSinMedioVerificadoVigente() throws Exception {
		jdbc.update("UPDATE app_medios_pago SET estado='pendiente_verificacion',verificado_hasta=NULL WHERE id=5006");
		request(get("/api/consignaciones/requisitos"), "oro@quickbid.demo").andExpect(status().isOk())
				.andExpect(jsonPath("$.data.puedeContinuar").value(true))
				.andExpect(jsonPath("$.data.requisitos[0].codigo").value("MEDIO_PAGO_REGISTRADO"))
				.andExpect(jsonPath("$.data.requisitos[0].cumplido").value(true))
				.andExpect(jsonPath("$.data.requisitos[1].codigo").value("CUENTA_COBRO_REGISTRADA"))
				.andExpect(jsonPath("$.data.requisitos[1].cumplido").value(true))
				.andExpect(jsonPath("$.data.requisitos[2].codigo").value("MEDIO_PAGO_APTO_PARA_ENVIO_DEVOLUCION"))
				.andExpect(jsonPath("$.data.requisitos[2].cumplido").value(false));
		assertNotNull(create(3004L));
	}

	@Test void cuentaBancariaConVerificacionManualExpiradaPermiteCrearConsignacion() {
		jdbc.update("UPDATE app_medios_pago SET verificado_hasta=DATEADD('DAY',-1,CURRENT_TIMESTAMP) WHERE id=5006");
		assertNotNull(create(3004L));
	}

	@Test void sinCuentaBancariaRegistradaNoPuedeCrearConsignacionAunqueTengaTarjeta() throws Exception {
		jdbc.update("UPDATE app_medios_pago SET estado='eliminado',deleted_at=CURRENT_TIMESTAMP WHERE id=5006");
		jdbc.update("""
				INSERT INTO app_medios_pago(id,cuenta_id,tipo,moneda,estado,principal,nacional,alias_visible,ultimos_4,
					titular,hash_identificador,consumo_actual) VALUES
					(5091,3004,'tarjeta','ARS','pendiente_verificacion',false,true,'Tarjeta pendiente','1111',
					'Diego Oro','demo-card-pending-consignment',0)
				""");
		request(get("/api/consignaciones/requisitos"), "oro@quickbid.demo").andExpect(status().isOk())
				.andExpect(jsonPath("$.data.puedeContinuar").value(false))
				.andExpect(jsonPath("$.data.requisitos[0].cumplido").value(true))
				.andExpect(jsonPath("$.data.requisitos[1].cumplido").value(false));
		assertCode("CONSIGNMENT_REQUIREMENTS_NOT_MET", () -> create(3004L));
	}

	@Test void mediosSoloRechazadosOEliminadosNoPermitenCrearConsignacion() throws Exception {
		jdbc.update("UPDATE app_medios_pago SET estado='rechazado' WHERE id=5006");
		request(get("/api/consignaciones/requisitos"), "oro@quickbid.demo").andExpect(status().isOk())
				.andExpect(jsonPath("$.data.puedeContinuar").value(false))
				.andExpect(jsonPath("$.data.requisitos[0].cumplido").value(false))
				.andExpect(jsonPath("$.data.requisitos[1].cumplido").value(false));
		assertCode("CONSIGNMENT_REQUIREMENTS_NOT_MET", () -> create(3004L));
	}

	@Test void cuentaConRestriccionDeMultaPuedeCrearConCuentaBancariaPendiente() {
		jdbc.update("UPDATE app_medios_pago SET estado='pendiente_verificacion',verificado_hasta=NULL WHERE id=5005");
		assertNotNull(create(3002L));
	}

	@Test void altaExigeSeisFotosYNoCreaLegacyAntesDelAcuerdo() throws Exception {
		var insufficient = multipart("/api/consignaciones");
		params(insufficient);
		for (int i = 0; i < 5; i++) insufficient.file(photo("fotos", i));
		request(insufficient, "oro@quickbid.demo").andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.errors[0].code").value("MINIMUM_PHOTOS_REQUIRED"));

		long beforeProducts = count("SELECT COUNT(*) FROM productos");
		long beforeOwners = count("SELECT COUNT(*) FROM duenios");
		var valid = multipart("/api/consignaciones");
		params(valid);
		for (int i = 0; i < 6; i++) valid.file(photo("fotos", i));
		String json = request(valid, "multa@quickbid.demo").andExpect(status().isCreated())
				.andExpect(jsonPath("$.data.estado").value("pendiente_revision"))
				.andExpect(jsonPath("$.data.productoId").value(nullValue()))
				.andReturn().getResponse().getContentAsString();
		Long id = ((Number) JsonPath.read(json, "$.data.id")).longValue();
		assertNull(jdbc.queryForObject("SELECT producto_id FROM app_solicitudes_consignacion WHERE id=?", Integer.class, id));
		assertEquals(beforeProducts, count("SELECT COUNT(*) FROM productos"));
		assertEquals(beforeOwners, count("SELECT COUNT(*) FROM duenios"));
	}

	@Test void altaRechazaMasDeQuinceFotos() throws Exception {
		var request = multipart("/api/consignaciones");
		params(request);
		for (int i = 0; i < 16; i++) request.file(photo("fotos", i));

		request(request, "oro@quickbid.demo").andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.errors[0].code").value("MAXIMUM_PHOTOS_EXCEEDED"));
	}

	@Test void altaIdempotenteNoDuplicaYLaClaveSeAislaPorCuenta() throws Exception {
		String key = "consignment-create-offline-test-001";
		Long firstId = createWithIdempotencyKey("oro@quickbid.demo", key);
		Long replayId = createWithIdempotencyKey("oro@quickbid.demo", key);

		assertEquals(firstId, replayId);
		assertEquals(1, count("SELECT COUNT(*) FROM app_solicitudes_consignacion WHERE cuenta_id=3004 AND idempotency_key=?", key));
		assertEquals(6, count("SELECT COUNT(*) FROM app_consignacion_fotos WHERE solicitud_id=?", firstId));

		Long otherAccountId = createWithIdempotencyKey("multa@quickbid.demo", key);
		assertFalse(firstId.equals(otherAccountId));
		assertEquals(2, count("SELECT COUNT(*) FROM app_solicitudes_consignacion WHERE idempotency_key=?", key));
	}

	@Test void altaIgnoraCategoriaSubastaEnviadaPorUsuario() throws Exception {
		var request = multipart("/api/consignaciones")
				.param("segmento", "relojeria")
				.param("categoriaSubasta", "oro")
				.param("aceptaTyC", "true")
				.param("declaracionPropiedadYOrigenLicito", "true")
				.param("titulo", "Reloj demo")
				.param("descripcion", "Descripcion demo")
				.param("historia", "Procedencia familiar documentada")
				.param("historiaExtendida", "Detalle extendido de conservacion");
		for (int i = 0; i < 6; i++) request.file(photo("fotos", i));

		String json = request(request, "oro@quickbid.demo").andExpect(status().isCreated())
				.andExpect(jsonPath("$.data.segmento").value("relojeria"))
				.andExpect(jsonPath("$.data.categoriaSubasta").value("comun"))
				.andReturn().getResponse().getContentAsString();
		Long id = ((Number) JsonPath.read(json, "$.data.id")).longValue();
		assertEquals("relojeria",
				jdbc.queryForObject("SELECT segmento FROM app_solicitudes_consignacion WHERE id=?", String.class, id));
		assertEquals("comun",
				jdbc.queryForObject("SELECT categoria_sugerida FROM app_solicitudes_consignacion WHERE id=?", String.class, id));
		assertEquals("Procedencia familiar documentada",
				jdbc.queryForObject("SELECT historia FROM app_solicitudes_consignacion WHERE id=?", String.class, id));
		assertEquals("Detalle extendido de conservacion",
				jdbc.queryForObject("SELECT historia_extendida FROM app_solicitudes_consignacion WHERE id=?", String.class, id));
		consignments.assignAuctionCategory(id, 1002, "oro");
		assertEquals("oro",
				jdbc.queryForObject("SELECT categoria_sugerida FROM app_solicitudes_consignacion WHERE id=?", String.class, id));
	}

	@Test void altaSinDeclaracionJuradaDevuelve400Uniforme() throws Exception {
		var request = multipart("/api/consignaciones")
				.param("segmento", "comun")
				.param("aceptaTyC", "true")
				.param("declaracionPropiedadYOrigenLicito", "false")
				.param("titulo", "Articulo demo")
				.param("descripcion", "Descripcion demo");
		for (int i = 0; i < 6; i++) request.file(photo("fotos", i));

		request(request, "oro@quickbid.demo").andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.data").value(nullValue()))
				.andExpect(jsonPath("$.errors[0].code").value("CONSIGNMENT_DECLARATION_REQUIRED"));
	}

	@Test void documentacionSolicitadaQuedaPendienteDeRevisionManual() throws Exception {
		consignments.requestOriginDocuments(16001L, 1002);
		String response = request(multipart("/api/consignaciones/16001/documentacion-origen")
				.file(pdf("facturaCompra", "..\\secret\\origen.pdf"))
				.param("observaciones", "Factura demo"), "oro@quickbid.demo")
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.data.estado").value("pendiente_revision"))
				.andExpect(jsonPath("$.data.documentosOrigen[0].estado").value("pendiente"))
				.andExpect(jsonPath("$.data.documentosOrigen[0].filename").value("origen.pdf"))
				.andReturn().getResponse().getContentAsString();
		assertFalse(response.contains(".."));
		assertFalse(response.contains("secret"));
		assertFalse(response.contains("storage_path"));
		assertFalse(response.contains("checksum"));
		assertFalse(response.contains("password"));
		assertFalse(response.contains("token"));
		assertEquals("pendiente_revision",
				jdbc.queryForObject("SELECT estado FROM app_solicitudes_consignacion WHERE id=16001", String.class));

		consignments.reviewOriginDocuments(16001L, 1002, true, null);
		assertEquals("recepcion_pendiente",
				jdbc.queryForObject("SELECT estado FROM app_solicitudes_consignacion WHERE id=16001", String.class));
	}

	@Test void documentacionNoConfiaSoloEnMimeDeclarado() throws Exception {
		consignments.requestOriginDocuments(16001L, 1002);
		request(multipart("/api/consignaciones/16001/documentacion-origen")
				.file(new MockMultipartFile("facturaCompra", "origen.pdf", "application/pdf", "contenido falso".getBytes())),
				"oro@quickbid.demo").andExpect(status().isUnprocessableEntity())
				.andExpect(jsonPath("$.errors[0].code").value("FILE_TYPE_NOT_SUPPORTED"));
	}

	@Test void documentacionProtegidaRechazaTokenAusenteOInvalido() throws Exception {
		mvc.perform(multipart("/api/consignaciones/16001/documentacion-origen").file(pdf("facturaCompra")))
				.andExpect(status().isUnauthorized())
				.andExpect(jsonPath("$.data").value(nullValue()))
				.andExpect(jsonPath("$.errors[0].code").value("UNAUTHORIZED"));
		mvc.perform(multipart("/api/consignaciones/16001/documentacion-origen").file(pdf("facturaCompra"))
				.header("Authorization", "Bearer token-invalido"))
				.andExpect(status().isUnauthorized())
				.andExpect(jsonPath("$.data").value(nullValue()))
				.andExpect(jsonPath("$.errors[0].code").value("UNAUTHORIZED"));
	}

	@Test void documentacionInexistenteAjenaOEstadoInvalidoDevuelveErroresUniformes() throws Exception {
		request(multipart("/api/consignaciones/99999/documentacion-origen").file(pdf("facturaCompra")),
				"oro@quickbid.demo").andExpect(status().isNotFound())
				.andExpect(jsonPath("$.data").value(nullValue()))
				.andExpect(jsonPath("$.errors[0].code").value("RESOURCE_NOT_FOUND"));
		request(multipart("/api/consignaciones/16001/documentacion-origen").file(pdf("facturaCompra")),
				"aprobado@quickbid.demo").andExpect(status().isForbidden())
				.andExpect(jsonPath("$.errors[0].code").value("RESOURCE_NOT_OWNED"));
		request(multipart("/api/consignaciones/16007/documentacion-origen").file(pdf("facturaCompra")),
				"oro@quickbid.demo").andExpect(status().isConflict())
				.andExpect(jsonPath("$.errors[0].code").value("INVALID_STATE_TRANSITION"));
	}

	@Test void aceptarAcuerdoCreaProductoYFotosLegacyPublicarCreaItemYPoliza() {
		Long id = create(3004L);
		String fullAgreement = "Acuerdo demo. " + "Clausula contractual extensa y verificable. ".repeat(100)
				+ "FIN DEL ACUERDO COMPLETO";
		toAgreement(id, fullAgreement);
		var accepted = consignments.acceptAgreement(3004L, id,
				new ConsignmentAgreementAcceptanceRequest(true, true));
		assertNotNull(accepted.productoId());
		assertNotNull(accepted.acuerdoEnviadoAt());
		assertNotNull(accepted.acuerdoAceptadoAt());
		assertTrue(accepted.documentosGenerados().stream()
				.anyMatch(file -> file.filename().startsWith("acuerdo_consignacion")
						&& file.downloadAvailable()
						&& "acuerdo_consignacion".equals(file.tipo())));
		String agreementPdf = generatedPdfText(id, "acuerdo_consignacion");
		assertTrue(agreementPdf.contains("Texto completo del acuerdo aceptado:"));
		assertTrue(agreementPdf.contains("Acuerdo demo"));
		assertTrue(agreementPdf.contains("FIN DEL ACUERDO COMPLETO"));
		assertTrue(agreementPdf.contains("/Count 2") || agreementPdf.contains("/Count 3"));
		assertEquals(6, count("SELECT COUNT(*) FROM fotos WHERE producto=?", accepted.productoId()));
		assertNull(jdbc.queryForObject("SELECT seguro FROM productos WHERE identificador=?", String.class,
				accepted.productoId()));

		Integer itemId = consignments.assignAuctionAndInsurance(id, 1002, 6004, 7004, null);
		assertNotNull(itemId);
		assertNotNull(jdbc.queryForObject("SELECT seguro FROM productos WHERE identificador=?", String.class,
				accepted.productoId()));
		assertEquals("publicada", jdbc.queryForObject("SELECT estado FROM app_solicitudes_consignacion WHERE id=?",
				String.class, id));
		var published = consignments.detail(3004L, id);
		assertNotNull(published.poliza());
		assertEquals("Depósito de custodia QuickBid - Salon Oro QuickBid", published.ubicacionFisica());
		assertEquals(published.ubicacionFisica(), published.poliza().ubicacionFisica());
		assertNotNull(published.subastaFechaHora());
		assertTrue(published.documentosGenerados().stream()
				.anyMatch(file -> file.filename().startsWith("poliza_consignacion")
						&& file.downloadAvailable()
						&& "poliza_consignacion".equals(file.tipo())));
		String policyPdf = generatedPdfText(id, "poliza_consignacion");
		assertTrue(policyPdf.contains("Número de póliza: QB-" + id));
		assertTrue(policyPdf.contains("Id de producto: " + accepted.productoId()));
		assertTrue(policyPdf.contains("Id de subasta: 6004"));
		assertTrue(policyPdf.contains("Id de item de catalogo: " + itemId));
		assertEquals(1, count("SELECT COUNT(*) FROM app_movimientos_puntos WHERE motivo='consignacion_publicada' AND referencia_id=?",
				id));
	}

	@Test void aceptarAcuerdoExigeCheckboxesYOwnership() throws Exception {
		Long id = create(3004L);
		toAgreement(id);

		request(post("/api/consignaciones/" + id + "/acuerdo/aceptar")
				.contentType(MediaType.APPLICATION_JSON).content("{}"), "oro@quickbid.demo")
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.data").value(nullValue()))
				.andExpect(jsonPath("$.errors").isArray());
		request(post("/api/consignaciones/" + id + "/acuerdo/aceptar")
				.contentType(MediaType.APPLICATION_JSON)
				.content("{\"leyoContrato\":false,\"aceptaClausulasPlazos\":true}"), "oro@quickbid.demo")
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.errors").isArray());
		request(post("/api/consignaciones/" + id + "/acuerdo/aceptar")
				.contentType(MediaType.APPLICATION_JSON)
				.content("{\"leyoContrato\":true,\"aceptaClausulasPlazos\":false}"), "oro@quickbid.demo")
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.errors").isArray());
		request(post("/api/consignaciones/" + id + "/acuerdo/aceptar")
				.contentType(MediaType.APPLICATION_JSON)
				.content("{\"leyoContrato\":true,\"aceptaClausulasPlazos\":true}"), "aprobado@quickbid.demo")
				.andExpect(status().isForbidden())
				.andExpect(jsonPath("$.errors[0].code").value("RESOURCE_NOT_OWNED"));
	}

	@Test void acuerdoAceptadoCreaProductoYNoPermiteRechazoPosterior() throws Exception {
		Long id = accepted();
		assertNotNull(jdbc.queryForObject("SELECT producto_id FROM app_solicitudes_consignacion WHERE id=?",
				Integer.class, id));
		request(post("/api/consignaciones/" + id + "/acuerdo/rechazar"), "oro@quickbid.demo")
				.andExpect(status().isConflict())
				.andExpect(jsonPath("$.errors[0].code").value("INVALID_STATE_TRANSITION"));
	}

	@Test void rechazarAcuerdoProcesadoDevuelve409() throws Exception {
		Long id = create(3004L);
		toAgreement(id);
		request(post("/api/consignaciones/" + id + "/acuerdo/rechazar"), "oro@quickbid.demo")
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.data.estado").value("devolucion_pendiente"));
		request(post("/api/consignaciones/" + id + "/acuerdo/rechazar"), "oro@quickbid.demo")
				.andExpect(status().isConflict())
				.andExpect(jsonPath("$.errors[0].code").value("INVALID_STATE_TRANSITION"));
	}

	@Test void polizaCombinadaSoloAdmiteMismoDuenioYMismaSubasta() {
		Long first = accepted();
		consignments.assignAuctionAndInsurance(first, 1002, 6004, 7004, null);
		String policy = jdbc.queryForObject("""
				SELECT p.seguro FROM productos p JOIN app_solicitudes_consignacion s ON s.producto_id=p.identificador
				WHERE s.id=?
				""", String.class, first);
		Long second = accepted();
		consignments.assignAuctionAndInsurance(second, 1002, 6004, 7004, policy);
		assertEquals("si", jdbc.queryForObject("SELECT \"polizaCombinada\" FROM seguros WHERE \"nroPoliza\"=?",
				String.class, policy));

		Long otherAuction = accepted();
		assertCode("INVALID_COMBINED_POLICY",
				() -> consignments.assignAuctionAndInsurance(otherAuction, 1002, 6001, 7001, policy));

		Long otherOwner = create(3001L);
		consignments.approveDigitalReview(otherOwner, 1002);
		consignments.markPhysicalReception(otherOwner, 1002);
		consignments.approvePhysicalReview(otherOwner, 1002);
		consignments.verifyConsignor(3001L, 1001, true, true, 2);
		consignments.proposeAgreement(otherOwner, 1002, new BigDecimal("25000"), "ARS", null, null, "Otro acuerdo");
		consignments.acceptAgreement(3001L, otherOwner, new ConsignmentAgreementAcceptanceRequest(true, true));
		assertCode("INVALID_COMBINED_POLICY",
				() -> consignments.assignAuctionAndInsurance(otherOwner, 1002, 6004, 7004, policy));
	}

	@Test void proponerAcuerdoExigeDuenioYPermiteVerificadorDistintoDelRevisor() {
		Long id = create(3001L);
		consignments.approveDigitalReview(id, 1002);
		consignments.markPhysicalReception(id, 1002);
		consignments.approvePhysicalReview(id, 1002);
		assertCode("CONSIGNOR_NOT_VERIFIED",
				() -> consignments.proposeAgreement(id, 1002, new BigDecimal("25000"), "ARS", null, null,
						"Acuerdo demo"));

		consignments.verifyConsignor(3001L, 1001, true, true, 2);
		consignments.proposeAgreement(id, 1002, new BigDecimal("25000"), "ARS", null, null, "Acuerdo demo");
		assertEquals(1001, jdbc.queryForObject("SELECT verificador FROM duenios WHERE identificador=2001",
				Integer.class));
		assertEquals(1002, jdbc.queryForObject("SELECT revisor_empleado_id FROM app_solicitudes_consignacion WHERE id=?",
				Integer.class, id));
	}

	@Test void rechazoFisicoPermiteElegirEnvioYPagarlo() throws Exception {
		Long id = rejectedReturn();
		request(post("/api/consignaciones/" + id + "/devolucion").contentType(MediaType.APPLICATION_JSON)
				.content("""
						{"modalidad":"envio","direccion":"Av. Demo 400","codigoPostal":"C1000",
						"localidad":"Buenos Aires","provincia":"Buenos Aires"}
						"""), "oro@quickbid.demo").andExpect(status().isCreated())
				.andExpect(jsonPath("$.data.estado").value("pendiente_pago"))
				.andExpect(jsonPath("$.data.costo").value(12000));
		request(post("/api/consignaciones/" + id + "/devolucion/pagar-envio").contentType(MediaType.APPLICATION_JSON)
				.content("{\"medioPagoId\":5006,\"idempotencyKey\":\"return-" + id + "\"}"), "oro@quickbid.demo")
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.data.estado").value("aprobado"));
		request(post("/api/consignaciones/" + id + "/devolucion/pagar-envio").contentType(MediaType.APPLICATION_JSON)
				.content("{\"medioPagoId\":5006,\"idempotencyKey\":\"return-" + id + "\"}"), "oro@quickbid.demo")
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.data.idempotentReplay").value(true));
		assertEquals("pendiente_entrega", jdbc.queryForObject("""
				SELECT estado FROM app_consignacion_devoluciones WHERE solicitud_id=?
				""", String.class, id));
		assertEquals(1, count("""
				SELECT COUNT(*) FROM app_documentos
				WHERE referencia_tipo='consignacion' AND referencia_id=? AND tipo='comprobante_envio_devolucion'
				""", id));
	}

	@Test void previewDevolucionCotizaEnvioYRetirosSinMutar() throws Exception {
		Long id = rejectedReturn();
		request(post("/api/consignaciones/" + id + "/devolucion/preview").contentType(MediaType.APPLICATION_JSON)
				.content("""
						{"modalidad":"envio","direccion":"Av. Demo 400","codigoPostal":"C1000",
						"localidad":"Buenos Aires","provincia":"Buenos Aires"}
						"""), "oro@quickbid.demo").andExpect(status().isOk())
				.andExpect(jsonPath("$.data.modalidad").value("envio"))
				.andExpect(jsonPath("$.data.costo").value(12000))
				.andExpect(jsonPath("$.data.moneda").value("ARS"))
				.andExpect(jsonPath("$.data.totalEstimado").value(12000))
				.andExpect(jsonPath("$.data.direccionResumen", containsString("Av. Demo 400")));
		assertEquals(1, count("""
				SELECT COUNT(*) FROM app_consignacion_devoluciones
				WHERE solicitud_id=? AND modalidad IS NULL AND estado='pendiente_decision' AND costo=0
				""", id));

		request(post("/api/consignaciones/" + id + "/devolucion/preview").contentType(MediaType.APPLICATION_JSON)
				.content("{\"modalidad\":\"retiro\"}"), "oro@quickbid.demo").andExpect(status().isOk())
				.andExpect(jsonPath("$.data.modalidad").value("retiro"))
				.andExpect(jsonPath("$.data.costo").value(0))
				.andExpect(jsonPath("$.data.totalEstimado").value(0));
		assertEquals(1, count("""
				SELECT COUNT(*) FROM app_consignacion_devoluciones
				WHERE solicitud_id=? AND modalidad IS NULL AND estado='pendiente_decision' AND costo=0
				""", id));
	}

	@Test void previewDevolucionInexistenteOAjenaDevuelveErroresUniformes() throws Exception {
		Long id = rejectedReturn();
		request(post("/api/consignaciones/99999/devolucion/preview").contentType(MediaType.APPLICATION_JSON)
				.content("{\"modalidad\":\"retiro\"}"), "oro@quickbid.demo")
				.andExpect(status().isNotFound())
				.andExpect(jsonPath("$.errors[0].code").value("RESOURCE_NOT_FOUND"));
		request(post("/api/consignaciones/" + id + "/devolucion/preview").contentType(MediaType.APPLICATION_JSON)
				.content("{\"modalidad\":\"retiro\"}"), "aprobado@quickbid.demo")
				.andExpect(status().isForbidden())
				.andExpect(jsonPath("$.errors[0].code").value("RESOURCE_NOT_OWNED"));
	}

	@Test void devolucionYPagoEnvioConRecursosInvalidosDevuelvenErroresUniformes() throws Exception {
		Long id = rejectedReturn();
		request(post("/api/consignaciones/16001/devolucion").contentType(MediaType.APPLICATION_JSON)
				.content("{\"modalidad\":\"retiro\"}"), "oro@quickbid.demo")
				.andExpect(status().isConflict())
				.andExpect(jsonPath("$.errors[0].code").value("INVALID_STATE_TRANSITION"));
		request(post("/api/consignaciones/" + id + "/devolucion/pagar-envio").contentType(MediaType.APPLICATION_JSON)
				.content("{\"medioPagoId\":5006,\"idempotencyKey\":\"return-wrong-state\"}"), "oro@quickbid.demo")
				.andExpect(status().isConflict())
				.andExpect(jsonPath("$.errors[0].code").value("INVALID_STATE_TRANSITION"));
		consignments.selectReturn(3004L, id, shippingReturn());
		request(post("/api/consignaciones/99999/devolucion/pagar-envio").contentType(MediaType.APPLICATION_JSON)
				.content("{\"medioPagoId\":5006,\"idempotencyKey\":\"return-missing\"}"), "oro@quickbid.demo")
				.andExpect(status().isNotFound())
				.andExpect(jsonPath("$.errors[0].code").value("RESOURCE_NOT_FOUND"));
		request(post("/api/consignaciones/" + id + "/devolucion/pagar-envio").contentType(MediaType.APPLICATION_JSON)
				.content("{\"medioPagoId\":5001,\"idempotencyKey\":\"return-foreign\"}"), "aprobado@quickbid.demo")
				.andExpect(status().isForbidden())
				.andExpect(jsonPath("$.errors[0].code").value("RESOURCE_NOT_OWNED"));
	}

	@Test void pagoDeEnvioDeDevolucionExigeMedioVerificadoConValidacionManualVigente() {
		Long id = rejectedReturn();
		consignments.selectReturn(3004L, id, shippingReturn());
		jdbc.update("UPDATE app_medios_pago SET estado='pendiente_verificacion',verificado_hasta=NULL WHERE id=5006");
		assertCode("PAYMENT_METHOD_NOT_VERIFIED",
				() -> consignments.payReturnShipping(3004L, id, new ConsignmentReturnPaymentRequest(5006L, "pending")));
		jdbc.update("UPDATE app_medios_pago SET estado='verificado',verificado_hasta=DATEADD('DAY',-1,CURRENT_TIMESTAMP) WHERE id=5006");
		assertCode("PAYMENT_METHOD_VERIFICATION_EXPIRED",
				() -> consignments.payReturnShipping(3004L, id, new ConsignmentReturnPaymentRequest(5006L, "expired")));
	}

	@Test void pagoDeEnvioDeDevolucionRechazaLimiteNulo() {
		Long id = rejectedReturn();
		consignments.selectReturn(3004L, id, shippingReturn());
		jdbc.update("UPDATE app_medios_pago SET estado='verificado',limite_monto=NULL,verificado_hasta=DATEADD('DAY',7,CURRENT_TIMESTAMP) WHERE id=5006");
		assertCode("INSUFFICIENT_FUNDS_OR_LIMIT",
				() -> consignments.payReturnShipping(3004L, id,
						new ConsignmentReturnPaymentRequest(5006L, "null-limit")));
	}

	@Test void envioDeDevolucionPuedePagarseConMedioDistintoAlRegistradoAlIniciar() {
		jdbc.update("UPDATE app_medios_pago SET estado='pendiente_verificacion',verificado_hasta=NULL WHERE id=5006");
		Long id = rejectedReturn();
		consignments.selectReturn(3004L, id, shippingReturn());
		jdbc.update("""
				INSERT INTO app_medios_pago(id,cuenta_id,tipo,moneda,estado,principal,nacional,alias_visible,ultimos_4,
					titular,hash_identificador,limite_monto,consumo_actual,verificado_hasta) VALUES
					(5092,3004,'tarjeta','ARS','verificado',false,true,'Tarjeta devolucion','2222',
					'Diego Oro','demo-card-return-payment',100000,0,DATEADD('DAY',7,CURRENT_TIMESTAMP))
				""");
		var payment = consignments.payReturnShipping(3004L, id,
				new ConsignmentReturnPaymentRequest(5092L, "other-method"));
		assertEquals(5092L, payment.medioPagoId());
		assertEquals("aprobado", payment.estado());
	}

	@Test void retiroDeDevolucionNoExigePagoDeEnvio() {
		Long id = rejectedReturn();
		var result = consignments.selectReturn(3004L, id,
				new ConsignmentReturnRequest("retiro", null, null, null, null, null, null));
		assertEquals("pendiente_retiro", result.estado());
		assertEquals(BigDecimal.ZERO.setScale(2), result.costo());
		assertEquals(0, count("""
				SELECT COUNT(*) FROM app_pagos p JOIN app_consignacion_devoluciones d
				ON d.id=p.consignacion_devolucion_id WHERE d.solicitud_id=?
				""", id));
	}

	@Test void liquidacionSeGeneraUnaSolaVez() throws Exception {
		jdbc.update("DELETE FROM app_documentos WHERE referencia_tipo='consignacion' AND referencia_id=16007 AND tipo='liquidacion_venta'");
		jdbc.update("DELETE FROM app_liquidaciones_consignacion WHERE solicitud_id=16007");
		jdbc.update("UPDATE app_solicitudes_consignacion SET estado='vendida' WHERE id=16007");
		jdbc.update("UPDATE app_medios_pago SET estado='rechazado',verificado_hasta=DATEADD('DAY',-1,CURRENT_TIMESTAMP) WHERE id=5006");
		jdbc.update("""
				UPDATE app_solicitudes_consignacion
				SET comision_comprador_pct=10,comision_vendedor_pct=15
				WHERE id=16007
				""");
		var liquidation = consignments.liquidate(16007L, 1002, 5006L);
		assertEquals(new BigDecimal("14250.00"), liquidation.comision());
		assertEquals(new BigDecimal("80750.00"), liquidation.montoNeto());
		assertEquals("Cuenta bancaria registrada #5006", liquidation.cuentaDestino());
		BusinessException exception = assertThrows(BusinessException.class,
				() -> consignments.liquidate(16007L, 1002, 5006L));
		assertEquals("LIQUIDATION_ALREADY_EXISTS", exception.getErrors().get(0).code());
		assertEquals(1, count("SELECT COUNT(*) FROM app_liquidaciones_consignacion WHERE solicitud_id=16007"));
		assertEquals(1, count("""
				SELECT COUNT(*) FROM app_documentos
				WHERE referencia_tipo='consignacion' AND referencia_id=16007 AND tipo='liquidacion_venta'
				"""));
		Long fileId = jdbc.queryForObject("""
				SELECT a.id FROM app_documentos d JOIN app_archivos a ON a.id=d.archivo_id
				WHERE d.referencia_tipo='consignacion' AND d.referencia_id=16007 AND d.tipo='liquidacion_venta'
				""", Long.class);
		byte[] persisted = jdbc.queryForObject("SELECT content_bytes FROM app_archivos WHERE id=?", byte[].class, fileId);
		assertNotNull(persisted);
		String pdfText = new String(persisted, java.nio.charset.StandardCharsets.ISO_8859_1);
		assertTrue(pdfText.startsWith("%PDF"));
		assertTrue(pdfText.contains("Referencia: CONSIGNACION-16007"));
		assertTrue(pdfText.contains("Monto bruto:"));
		assertTrue(pdfText.contains("Monto neto:"));
		request(get("/api/consignaciones/16007/archivos/" + fileId + "/descargar"), "oro@quickbid.demo")
				.andExpect(status().isOk())
				.andExpect(result -> assertTrue(new String(result.getResponse().getContentAsByteArray(),
						java.nio.charset.StandardCharsets.ISO_8859_1).startsWith("%PDF")));
	}

	@Test void seedsLiquidadosExponenAcuerdoPolizaYLiquidacionDescargables() throws Exception {
		seedLiquidatedConsignmentDocuments();
		var detail = consignments.detail(3004L, 16007L);
		assertEquals("liquidada", detail.estado());
		assertEquals("Depósito de custodia QuickBid, Buenos Aires", detail.ubicacionFisica());
		assertNotNull(detail.poliza());
		assertEquals(detail.ubicacionFisica(), detail.poliza().ubicacionFisica());
		assertSeedDocument(16007L, "acuerdo_consignacion", "acuerdo_consignacion");
		assertSeedDocument(16007L, "poliza_consignacion", "poliza_consignacion", "poliza_seguro");
		assertSeedDocument(16007L, "liquidacion_venta", "liquidacion_venta");
		assertTrue(detail.documentosGenerados().stream().anyMatch(file ->
				"acuerdo_consignacion".equals(file.tipo()) && file.downloadAvailable()));
		assertTrue(detail.documentosGenerados().stream().anyMatch(file ->
				"poliza_consignacion".equals(file.tipo()) && file.downloadAvailable()));
		assertTrue(detail.documentosGenerados().stream().anyMatch(file ->
				"liquidacion_venta".equals(file.tipo()) && file.downloadAvailable()));
	}

	@Test void seedsMultiloteExponenAcuerdoYPolizaDescargables() throws Exception {
		seedMultilotConsignmentDocuments();
		for (long consignmentId : List.of(16111L, 16112L, 16113L)) {
			jdbc.update("UPDATE app_solicitudes_consignacion SET ubicacion_fisica=? WHERE id=?",
					"Depósito de custodia QuickBid - Sala principal, Buenos Aires", consignmentId);
			var detail = consignments.detail(3004L, consignmentId);
			assertEquals("Depósito de custodia QuickBid - Sala principal, Buenos Aires", detail.ubicacionFisica());
			assertSeedDocument(consignmentId, "acuerdo_consignacion", "acuerdo_consignacion");
			assertSeedDocument(consignmentId, "poliza", "poliza_consignacion", "poliza_seguro");
		}
	}

	@Test void duenioDescargaDocumentoDeConsignacionYOtroUsuarioNo() throws Exception {
		consignments.requestOriginDocuments(16001L, 1002);
		request(multipart("/api/consignaciones/16001/documentacion-origen")
				.file(pdf("facturaCompra")), "oro@quickbid.demo").andExpect(status().isOk());
		Long fileId = jdbc.queryForObject("""
				SELECT archivo_id FROM app_consignacion_documentos_origen
				WHERE solicitud_id=16001 ORDER BY id DESC LIMIT 1
				""", Long.class);

		request(get("/api/consignaciones/16001/archivos/" + fileId + "/descargar"), "oro@quickbid.demo")
				.andExpect(status().isOk())
				.andExpect(content().contentType("application/pdf"))
				.andExpect(header().string("Content-Disposition", containsString("origen.pdf")));
		request(get("/api/consignaciones/16001/archivos/" + fileId + "/descargar"), "aprobado@quickbid.demo")
				.andExpect(status().isForbidden())
				.andExpect(jsonPath("$.errors[0].code").value("RESOURCE_NOT_OWNED"));
		request(get("/api/consignaciones/16001/archivos/99999/descargar"), "oro@quickbid.demo")
				.andExpect(status().isNotFound());
	}

	@Test void devolucionPermiteDireccionGuardadaPropiaYMantieneSnapshot() throws Exception {
		Long id = rejectedReturn();
		jdbc.update("""
				INSERT INTO app_direcciones_envio(id,cuenta_id,alias,destinatario,calle,numero,codigo_postal,
					localidad,provincia,pais,telefono,principal)
				VALUES (5190,3004,'Casa consignador','Diego Oro','Calle Oro','90','C9000',
					'Buenos Aires','Buenos Aires','Argentina','1144445555',true)
				""");

		request(post("/api/consignaciones/" + id + "/devolucion").contentType(MediaType.APPLICATION_JSON)
				.content("{\"modalidad\":\"envio\",\"direccionEnvioId\":5190}"), "oro@quickbid.demo")
				.andExpect(status().isCreated())
				.andExpect(jsonPath("$.data.direccionEnvioId").value(5190))
				.andExpect(jsonPath("$.data.direccionResumen", containsString("Calle Oro 90")));
		assertEquals("Calle Oro 90", jdbc.queryForObject(
				"SELECT direccion FROM app_consignacion_devoluciones WHERE solicitud_id=?", String.class, id));
	}

	@Test void devolucionRechazaDireccionGuardadaAjena() throws Exception {
		Long id = rejectedReturn();
		request(post("/api/consignaciones/" + id + "/devolucion").contentType(MediaType.APPLICATION_JSON)
				.content("{\"modalidad\":\"envio\",\"direccionEnvioId\":5101}"), "oro@quickbid.demo")
				.andExpect(status().isForbidden())
				.andExpect(jsonPath("$.errors[0].code").value("RESOURCE_NOT_OWNED"));
	}

	@Test void listadoSoportaFiltrosActivasRechazadasYVendidas() throws Exception {
		request(get("/api/consignaciones?filtro=activas"), "oro@quickbid.demo")
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.data.content", hasSize(2)));
		request(get("/api/consignaciones?filtro=vendidas"), "oro@quickbid.demo")
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.data.content", hasSize(1)))
				.andExpect(jsonPath("$.data.content[0].id").value(16007));

		Long rejected = rejectedReturn();
		request(get("/api/consignaciones?filtro=rechazadas"), "oro@quickbid.demo")
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.data.content[0].id").value(rejected.intValue()));
		request(get("/api/consignaciones?filtro=inexistente"), "oro@quickbid.demo")
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.errors[0].code").value("INVALID_FILTER"));
	}

	@Test void detalleAjenoEs403YEndpointSinTokenEs401Uniforme() throws Exception {
		request(get("/api/consignaciones/99999"), "oro@quickbid.demo").andExpect(status().isNotFound())
				.andExpect(jsonPath("$.errors[0].code").value("RESOURCE_NOT_FOUND"));
		request(get("/api/consignaciones/16001"), "aprobado@quickbid.demo").andExpect(status().isForbidden())
				.andExpect(jsonPath("$.errors[0].code").value("RESOURCE_NOT_OWNED"));
		mvc.perform(get("/api/consignaciones")).andExpect(status().isUnauthorized())
				.andExpect(jsonPath("$.data").value(nullValue()))
				.andExpect(jsonPath("$.errors[0].code").value("UNAUTHORIZED"));
	}

	private Long accepted() {
		Long id = create(3004L);
		toAgreement(id);
		return consignments.acceptAgreement(3004L, id, new ConsignmentAgreementAcceptanceRequest(true, true)).id();
	}

	private Long rejectedReturn() {
		Long id = create(3004L);
		consignments.approveDigitalReview(id, 1002);
		consignments.markPhysicalReception(id, 1002);
		consignments.rejectPhysicalReview(id, 1002, "Daño detectado");
		return id;
	}

	private ConsignmentReturnRequest shippingReturn() {
		return new ConsignmentReturnRequest("envio", "Av. Demo 400", null, "C1000", "Buenos Aires",
				"Buenos Aires", null);
	}

	private void assertCode(String code, Runnable action) {
		BusinessException exception = assertThrows(BusinessException.class, action::run);
		assertEquals(code, exception.getErrors().get(0).code());
	}

	private void seedLiquidatedConsignmentDocuments() {
		jdbc.update("INSERT INTO seguros(\"nroPoliza\",compania,\"polizaCombinada\",importe) VALUES ('POL-QB-8005','QuickBid Seguros','si',2000.00)");
		jdbc.update("UPDATE productos SET seguro='POL-QB-8005' WHERE identificador=8005");
		jdbc.update("""
				UPDATE app_solicitudes_consignacion
				SET estado='liquidada',
				    acuerdo_texto='Condiciones comerciales aceptadas para la publicacion del bien.',
				    acuerdo_enviado_at=DATEADD('DAY', -2, CURRENT_TIMESTAMP),
				    acuerdo_aceptado_at=DATEADD('DAY', -1, CURRENT_TIMESTAMP)
				WHERE id=16007
				""");
		jdbc.update("""
				INSERT INTO app_liquidaciones_consignacion(
					id,solicitud_id,compra_id,monto_bruto,comision,monto_neto,cuenta_destino,estado,paid_at)
				VALUES (17301,16007,13002,95000.00,9500.00,85500.00,
					'Cuenta bancaria registrada del consignador','pagada',CURRENT_TIMESTAMP)
				""");
		insertSeedFile(4302L, "acuerdo_consignacion-16007.pdf");
		insertSeedFile(4303L, "poliza_consignacion-16007.pdf");
		insertSeedFile(4304L, "liquidacion_venta-16007.pdf");
		insertSeedDocument(17422L, "acuerdo_consignacion", 16007L, 4302L);
		insertSeedDocument(17423L, "poliza_consignacion", 16007L, 4303L);
		insertSeedDocument(17424L, "liquidacion_venta", 16007L, 4304L);
	}

	private void seedMultilotConsignmentDocuments() {
		jdbc.update("""
				INSERT INTO productos(identificador,"descripcionCatalogo",seguro) VALUES
				    (8011,'Sillon escandinavo de roble','POL-QB-8011'),
				    (8012,'Juego de vajilla art deco','POL-QB-8012'),
				    (8013,'Reloj de mesa coleccionable','POL-QB-8013')
				""");
		jdbc.update("""
				INSERT INTO seguros("nroPoliza",compania,"polizaCombinada",importe) VALUES
				    ('POL-QB-8011','QuickBid Seguros','si',3000.00),
				    ('POL-QB-8012','QuickBid Seguros','si',3600.00),
				    ('POL-QB-8013','QuickBid Seguros','si',4100.00)
				""");
		jdbc.update("""
				INSERT INTO app_solicitudes_consignacion (
				    id,cuenta_id,cliente_id,producto_id,item_catalogo_id,subasta_id,titulo,descripcion,segmento,categoria_sugerida,
				    declaracion_propiedad,acepta_devolucion_con_cargo,estado,revisor_empleado_id,valor_base_propuesto,
				    moneda_propuesta,comision_comprador_pct,comision_vendedor_pct,acuerdo_texto,acuerdo_enviado_at,acuerdo_aceptado_at
				) VALUES
				    (16111,3004,2004,8011,NULL,6004,'Sillon escandinavo de roble','Primer lote del escenario live multi-lote',
				     'mobiliario','plata',true,true,'en_subasta',1002,50000,'ARS',10,10,
				     'Condiciones comerciales aceptadas para la publicacion del bien.',DATEADD('DAY', -2, CURRENT_TIMESTAMP),DATEADD('DAY', -1, CURRENT_TIMESTAMP)),
				    (16112,3004,2004,8012,NULL,6004,'Juego de vajilla art deco','Segundo lote del escenario live multi-lote',
				     'decoracion','plata',true,true,'en_subasta',1002,70000,'ARS',10,10,
				     'Condiciones comerciales aceptadas para la publicacion del bien.',DATEADD('DAY', -2, CURRENT_TIMESTAMP),DATEADD('DAY', -1, CURRENT_TIMESTAMP)),
				    (16113,3004,2004,8013,NULL,6004,'Reloj de mesa coleccionable','Tercer lote del escenario live multi-lote',
				     'coleccion','plata',true,true,'en_subasta',1002,90000,'ARS',10,10,
				     'Condiciones comerciales aceptadas para la publicacion del bien.',DATEADD('DAY', -2, CURRENT_TIMESTAMP),DATEADD('DAY', -1, CURRENT_TIMESTAMP))
				""");
		insertSeedFile(4312L, "acuerdo_consignacion-16111.pdf");
		insertSeedFile(4313L, "poliza_consignacion-16111.pdf");
		insertSeedFile(4314L, "acuerdo_consignacion-16112.pdf");
		insertSeedFile(4315L, "poliza_consignacion-16112.pdf");
		insertSeedFile(4316L, "acuerdo_consignacion-16113.pdf");
		insertSeedFile(4317L, "poliza_consignacion-16113.pdf");
		insertSeedDocument(17432L, "acuerdo_consignacion", 16111L, 4312L);
		insertSeedDocument(17433L, "poliza_seguro", 16111L, 4313L);
		insertSeedDocument(17434L, "acuerdo_consignacion", 16112L, 4314L);
		insertSeedDocument(17435L, "poliza_seguro", 16112L, 4315L);
		insertSeedDocument(17436L, "acuerdo_consignacion", 16113L, 4316L);
		insertSeedDocument(17437L, "poliza_seguro", 16113L, 4317L);
	}

	private void insertSeedFile(Long id, String filename) {
		byte[] bytes = "%PDF-1.4\n%%EOF\n".getBytes(java.nio.charset.StandardCharsets.ISO_8859_1);
		jdbc.update("""
				INSERT INTO app_archivos(
					id,owner_cuenta_id,tipo_contexto,filename_original,content_type,size_bytes,storage_path,checksum,content_bytes)
				VALUES (?,?,?,?,?,?,?,?,?)
				""", id, 3004L, "documento_consignacion", filename, "application/pdf",
				(long) bytes.length, "test/" + filename, "test-" + id, bytes);
	}

	private void insertSeedDocument(Long id, String type, Long consignmentId, Long fileId) {
		jdbc.update("""
				INSERT INTO app_documentos(id,tipo,referencia_tipo,referencia_id,archivo_id,estado)
				VALUES (? ,?,'consignacion',?,?,'disponible')
				""", id, type, consignmentId, fileId);
	}

	private void assertSeedDocument(Long consignmentId, String filenameToken, String... acceptedTypes) throws Exception {
		String placeholders = "?,".repeat(acceptedTypes.length);
		placeholders = placeholders.substring(0, placeholders.length() - 1);
		List<Object> params = new ArrayList<>();
		params.add(consignmentId);
		params.addAll(List.of(acceptedTypes));
		SeedDocument document = jdbc.queryForObject("""
				SELECT a.id,a.filename_original,a.size_bytes,a.content_bytes
				FROM app_documentos d JOIN app_archivos a ON a.id=d.archivo_id
				WHERE d.referencia_tipo='consignacion'
				  AND d.referencia_id=?
				  AND d.tipo IN (%s)
				  AND d.estado='disponible'
				  AND a.content_type='application/pdf'
				  AND a.content_bytes IS NOT NULL
				ORDER BY d.id LIMIT 1
				""".formatted(placeholders), (rs, row) -> new SeedDocument(rs.getLong(1),
					rs.getString(2), rs.getLong(3), rs.getBytes(4)), params.toArray());
		assertTrue(document.filename().toLowerCase().contains(filenameToken.toLowerCase()));
		assertEquals(document.sizeBytes().longValue(), document.contentBytes().length);
		request(get("/api/consignaciones/" + consignmentId + "/archivos/" + document.fileId() + "/descargar"), "oro@quickbid.demo")
				.andExpect(status().isOk())
				.andExpect(content().contentType("application/pdf"))
				.andExpect(header().string("Content-Disposition", containsString(".pdf")))
				.andExpect(result -> assertTrue(new String(result.getResponse().getContentAsByteArray(),
						java.nio.charset.StandardCharsets.ISO_8859_1).startsWith("%PDF")));
	}

	private record SeedDocument(Long fileId, String filename, Long sizeBytes, byte[] contentBytes) {
	}

	private String generatedPdfText(Long consignmentId, String type) {
		byte[] bytes = jdbc.queryForObject("""
				SELECT a.content_bytes FROM app_documentos d JOIN app_archivos a ON a.id=d.archivo_id
				WHERE d.referencia_tipo='consignacion' AND d.referencia_id=? AND d.tipo=?
				ORDER BY d.id DESC LIMIT 1
				""", byte[].class, consignmentId, type);
		return new String(bytes, java.nio.charset.StandardCharsets.ISO_8859_1);
	}

	private void toAgreement(Long id) {
		toAgreement(id, "Acuerdo demo");
	}

	private void toAgreement(Long id, String agreementText) {
		consignments.approveDigitalReview(id, 1002);
		consignments.markPhysicalReception(id, 1002);
		consignments.approvePhysicalReview(id, 1002);
		consignments.verifyConsignor(3004L, 1002, true, true, 2);
		consignments.proposeAgreement(id, 1002, new BigDecimal("25000"), "ARS", null, null, agreementText);
	}

	private Long create(Long accountId) {
		return consignments.create(accountId, "arte", "comun", true, true, "Articulo demo", "Descripcion demo", null,
				"1980", false, null, null, null, photos()).id();
	}

	private List<MultipartFile> photos() {
		List<MultipartFile> result = new ArrayList<>();
		for (int i = 0; i < 6; i++) result.add(photo("fotos", i));
		return result;
	}

	private MockMultipartFile photo(String part, int index) {
		return new MockMultipartFile(part, "foto-" + index + ".png", "image/png",
				new byte[] { (byte) 137, 80, 78, 71, 13, 10, 26, 10 });
	}

	private MockMultipartFile pdf(String part) {
		return pdf(part, "origen.pdf");
	}

	private MockMultipartFile pdf(String part, String filename) {
		return new MockMultipartFile(part, filename, "application/pdf", "%PDF-1.4\nDemo".getBytes());
	}

	private void params(org.springframework.test.web.servlet.request.MockMultipartHttpServletRequestBuilder builder) {
		builder.param("segmento", "arte").param("aceptaTyC", "true")
				.param("declaracionPropiedadYOrigenLicito", "true").param("titulo", "Articulo demo")
				.param("descripcion", "Descripcion demo");
	}

	private Long createWithIdempotencyKey(String email, String key) throws Exception {
		var builder = multipart("/api/consignaciones");
		params(builder);
		builder.param("idempotencyKey", key);
		for (int i = 0; i < 6; i++) builder.file(photo("fotos", i));
		String json = request(builder, email).andExpect(status().isCreated())
				.andReturn().getResponse().getContentAsString();
		return ((Number) JsonPath.read(json, "$.data.id")).longValue();
	}

	private long count(String sql, Object... args) {
		return jdbc.queryForObject(sql, Long.class, args);
	}

	private ResultActions request(org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder builder,
			String email) throws Exception {
		return mvc.perform(builder.header("Authorization", "Bearer " + token(email)));
	}

	private ResultActions request(
			org.springframework.test.web.servlet.request.MockMultipartHttpServletRequestBuilder builder, String email)
			throws Exception {
		return mvc.perform(builder.header("Authorization", "Bearer " + token(email)));
	}

	private String token(String email) throws Exception {
		String json = mvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
				.content("{\"email\":\"" + email + "\",\"clave\":\"Demo123!\"}"))
				.andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
		return JsonPath.read(json, "$.data.accessToken");
	}
}
