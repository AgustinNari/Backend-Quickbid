package com.example.quickbid.quickbid;

import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.blankOrNullString;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.nullValue;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalTime;
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
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.web.multipart.MultipartFile;

import com.example.quickbid.quickbid.dto.request.ConsignmentAgreementAcceptanceRequest;
import com.example.quickbid.quickbid.security.AdminInternalAuthenticationFilter;
import com.example.quickbid.quickbid.security.AuthRateLimitService;
import com.example.quickbid.quickbid.service.ConsignmentService;
import com.example.quickbid.quickbid.service.SimulatedMailService;
import com.jayway.jsonpath.JsonPath;

@SpringBootTest(properties = "app.mail.enabled=false")
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Sql(scripts = "/auth-test-data.sql", executionPhase = Sql.ExecutionPhase.BEFORE_TEST_METHOD)
class AdminIntegrationTests {
	private static final String ADMIN_KEY = "test-admin-key";
	@Autowired MockMvc mvc;
	@Autowired JdbcTemplate jdbc;
	@Autowired AuthRateLimitService limits;
	@Autowired ConsignmentService consignments;
	@Autowired SimulatedMailService mail;

	@BeforeEach void clearLimits() {
		limits.clear();
		mail.clear();
	}

	@Test void adminSinCredencialEs401YUsuarioNormalEs403() throws Exception {
		mvc.perform(get("/api/admin/consignaciones")).andExpect(status().isUnauthorized())
				.andExpect(jsonPath("$.data").value(nullValue()))
				.andExpect(jsonPath("$.errors[0].code").value("UNAUTHORIZED"));
		mvc.perform(get("/api/admin/consignaciones").header("Authorization", "Bearer token-invalido"))
				.andExpect(status().isUnauthorized())
				.andExpect(jsonPath("$.data").value(nullValue()))
				.andExpect(jsonPath("$.errors[0].code").value("UNAUTHORIZED"));
		mvc.perform(get("/api/admin/consignaciones").header("Authorization", "Bearer " + token("aprobado@quickbid.demo")))
				.andExpect(status().isForbidden())
				.andExpect(jsonPath("$.data").value(nullValue()))
				.andExpect(jsonPath("$.errors[0].code").value("FORBIDDEN"));
	}

	@Test void adminErroresBasicosSonEnvelopeUniforme() throws Exception {
		admin(get("/api/admin/solicitudes-registro/99999")).andExpect(status().isNotFound())
				.andExpect(jsonPath("$.data").value(nullValue()))
				.andExpect(jsonPath("$.errors[0].code").value("RESOURCE_NOT_FOUND"));
		admin(post("/api/admin/medios-pago/5003/rechazar").contentType(MediaType.APPLICATION_JSON)
				.content("{}")).andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.data").value(nullValue()))
				.andExpect(jsonPath("$.errors[0].code").value("INVALID_FIELD"));
	}

	@Test void adminApruebaRegistroYGuardaSetupTokenHasheado() throws Exception {
		long id = registration("aprobar@quickbid.demo");
		admin(post("/api/admin/solicitudes-registro/" + id + "/aprobar").contentType(MediaType.APPLICATION_JSON)
				.content("{\"documento\":\"DNI-ADMIN-001\",\"categoriaInicial\":\"especial\"}")).andExpect(status().isOk())
				.andExpect(jsonPath("$.data.estado").value("aprobada_pendiente_finalizacion"))
				.andExpect(content().string(not(containsString("setup_token"))))
				.andExpect(content().string(not(containsString("password"))))
				.andExpect(content().string(not(containsString("refreshToken"))))
				.andExpect(content().string(not(containsString("hash"))));
		String hash = jdbc.queryForObject("SELECT setup_token_hash FROM app_solicitudes_registro WHERE id=?", String.class, id);
		assertNotNull(hash);
		assertEquals(64, hash.length());
		assertNotEquals("DNI-ADMIN-001", hash);
		assertEquals("especial", jdbc.queryForObject("SELECT categoria FROM clientes WHERE identificador=(SELECT cliente_id FROM app_solicitudes_registro WHERE id=?)", String.class, id));
		assertTrue(delivered("token", "registro"));
	}

	@Test void adminRechazaRegistroConMotivo() throws Exception {
		long id = registration("rechazar@quickbid.demo");
		admin(post("/api/admin/solicitudes-registro/" + id + "/rechazar").contentType(MediaType.APPLICATION_JSON)
				.content("{\"motivo\":\"Documento ilegible\"}")).andExpect(status().isOk())
				.andExpect(jsonPath("$.data.estado").value("rechazado"))
				.andExpect(jsonPath("$.data.motivoRechazo").value("Documento ilegible"));
	}

	@Test void adminBloqueaDesbloqueaYAjustaPuntosYCategoria() throws Exception {
		admin(post("/api/admin/usuarios/3001/bloquear")).andExpect(status().isOk())
				.andExpect(jsonPath("$.data.estado").value("deshabilitada_admin"));
		admin(post("/api/admin/usuarios/3001/desbloquear")).andExpect(status().isOk())
				.andExpect(jsonPath("$.data.estado").value("activa"));
		admin(patch("/api/admin/usuarios/3001/puntos").contentType(MediaType.APPLICATION_JSON)
				.content("{\"delta\":25}")).andExpect(status().isOk())
				.andExpect(jsonPath("$.data.puntos").value(925));
		assertEquals(1, count("SELECT COUNT(*) FROM app_movimientos_puntos WHERE motivo='ajuste_admin' AND cuenta_id=3001"));
		admin(patch("/api/admin/usuarios/3001/categoria").contentType(MediaType.APPLICATION_JSON)
				.content("{\"categoria\":\"oro\"}")).andExpect(status().isOk())
				.andExpect(jsonPath("$.data.categoria").value("oro"));
		assertEquals("oro", jdbc.queryForObject("SELECT categoria FROM clientes WHERE identificador=2001", String.class));
	}

	@Test void adminVerificaYRechazaMediosPendientes() throws Exception {
		admin(get("/api/admin/medios-pago?estado=VERIFICADO")).andExpect(status().isOk())
				.andExpect(jsonPath("$.errors", hasSize(0)))
				.andExpect(content().string(not(containsString("hash_identificador"))))
				.andExpect(content().string(not(containsString("titular"))))
				.andExpect(content().string(not(containsString("password"))));
		admin(get("/api/admin/medios-pago?estado=inexistente")).andExpect(status().isUnprocessableEntity())
				.andExpect(jsonPath("$.data").value(nullValue()))
				.andExpect(jsonPath("$.errors[0].code").value("INVALID_FILTER"));

		int before = jdbc.queryForObject("SELECT puntos FROM app_cuentas WHERE id=3001", Integer.class);
		admin(post("/api/admin/medios-pago/5003/verificar").contentType(MediaType.APPLICATION_JSON)
				.content("{\"limiteAprobado\":150000}")).andExpect(status().isOk())
				.andExpect(jsonPath("$.data.estado").value("verificado"));
		assertEquals(before + 30, jdbc.queryForObject("SELECT puntos FROM app_cuentas WHERE id=3001", Integer.class));
		assertEquals(new BigDecimal("150000.00"),
				jdbc.queryForObject("SELECT limite_monto FROM app_medios_pago WHERE id=5003", BigDecimal.class));

		jdbc.update("""
				INSERT INTO app_medios_pago(id,cuenta_id,tipo,moneda,estado,principal,nacional,alias_visible,titular,
					hash_identificador,consumo_actual) VALUES (5099,3001,'tarjeta','ARS','pendiente_verificacion',
					false,true,'Pendiente rechazo','Ana','hash-reject-admin',0)
				""");
		admin(post("/api/admin/medios-pago/5099/rechazar").contentType(MediaType.APPLICATION_JSON)
				.content("{\"motivo\":\"Datos inconsistentes\"}")).andExpect(status().isOk())
				.andExpect(jsonPath("$.data.estado").value("rechazado"));
		assertEquals("Datos inconsistentes",
				jdbc.queryForObject("SELECT motivo_rechazo FROM app_medios_pago WHERE id=5099", String.class));
		assertTrue(delivered("notification", "medio_pago_verificado"));
		assertTrue(delivered("notification", "medio_pago_rechazado"));
	}

	@Test void adminVerificarMedioExigeLimiteAprobadoYPermiteRevalidar() throws Exception {
		admin(post("/api/admin/medios-pago/5003/verificar").contentType(MediaType.APPLICATION_JSON)
				.content("{}")).andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.errors[0].code").value("INVALID_FIELD"));

		jdbc.update("""
				INSERT INTO app_medios_pago(id,cuenta_id,tipo,moneda,estado,principal,nacional,alias_visible,titular,
					hash_identificador,limite_monto,consumo_actual,verificado_hasta)
				VALUES (5096,3001,'tarjeta','ARS','vencido',false,true,'Vencida','Ana','hash-expired-admin',
					5000,0,DATEADD('DAY',-1,CURRENT_TIMESTAMP))
				""");
		admin(post("/api/admin/medios-pago/5096/verificar").contentType(MediaType.APPLICATION_JSON)
				.content("{\"limiteAprobado\":8000}")).andExpect(status().isOk())
				.andExpect(jsonPath("$.data.estado").value("verificado"));
		assertEquals(new BigDecimal("8000.00"),
				jdbc.queryForObject("SELECT limite_monto FROM app_medios_pago WHERE id=5096", BigDecimal.class));
		assertEquals(900, jdbc.queryForObject("SELECT puntos FROM app_cuentas WHERE id=3001", Integer.class));

		jdbc.update("""
				INSERT INTO app_medios_pago(id,cuenta_id,tipo,moneda,estado,principal,nacional,alias_visible,titular,
					hash_identificador,limite_monto,consumo_actual,verificado_hasta)
				VALUES (5097,3001,'tarjeta','ARS','verificado',false,true,'Verificada','Ana','hash-verified-admin',
					6000,0,DATEADD('DAY',2,CURRENT_TIMESTAMP))
				""");
		admin(post("/api/admin/medios-pago/5097/verificar").contentType(MediaType.APPLICATION_JSON)
				.content("{\"limiteAprobado\":9000}")).andExpect(status().isOk())
				.andExpect(jsonPath("$.data.estado").value("verificado"));
		assertEquals(new BigDecimal("9000.00"),
				jdbc.queryForObject("SELECT limite_monto FROM app_medios_pago WHERE id=5097", BigDecimal.class));
		assertEquals(900, jdbc.queryForObject("SELECT puntos FROM app_cuentas WHERE id=3001", Integer.class));
	}

	@Test void adminPuedeMarcarMediosExpiradosComoVencidos() throws Exception {
		jdbc.update("""
				INSERT INTO app_medios_pago(id,cuenta_id,tipo,moneda,estado,principal,nacional,alias_visible,titular,
					hash_identificador,limite_monto,consumo_actual,verificado_hasta)
				VALUES (5095,3001,'tarjeta','ARS','verificado',true,true,'Expirada','Ana','hash-expire-job',
					1000,0,DATEADD('DAY',-1,CURRENT_TIMESTAMP))
				""");
		admin(post("/api/admin/medios-pago/vencer-expirados")).andExpect(status().isOk())
				.andExpect(jsonPath("$.data.estado").value("procesado"));
		assertEquals("vencido", jdbc.queryForObject("SELECT estado FROM app_medios_pago WHERE id=5095", String.class));
		assertEquals(false, jdbc.queryForObject("SELECT principal FROM app_medios_pago WHERE id=5095", Boolean.class));
	}

	@Test void adminProcesaVencimientosGeneralesYLimpiaNotificacionesAntiguas() throws Exception {
		jdbc.update("""
				INSERT INTO app_medios_pago(id,cuenta_id,tipo,moneda,estado,principal,nacional,alias_visible,titular,
					hash_identificador,limite_monto,consumo_actual,verificado_hasta)
				VALUES (5094,3001,'tarjeta','ARS','verificado',true,true,'Expirada job','Ana','hash-expire-all',
					1000,0,DATEADD('DAY',-1,CURRENT_TIMESTAMP))
				""");
		jdbc.update("UPDATE app_multas SET vence_at=DATEADD('HOUR',-1,CURRENT_TIMESTAMP) WHERE id=14001");
		jdbc.update("UPDATE app_compras SET updated_at=DATEADD('HOUR',-73,CURRENT_TIMESTAMP) WHERE id=13002");
		jdbc.update("""
				INSERT INTO app_solicitudes_consignacion(id,cuenta_id,cliente_id,titulo,descripcion,segmento,categoria_sugerida,
					declaracion_propiedad,acepta_devolucion_con_cargo,estado)
				VALUES (16901,3004,2004,'Devolucion vencida','Demo','arte','comun',true,true,'devolucion_pendiente')
				""");
		jdbc.update("""
				INSERT INTO app_consignacion_devoluciones(id,solicitud_id,motivo,costo,moneda,estado,created_at)
				VALUES (17901,16901,'Retiro pendiente',0,'ARS','pendiente_retiro',DATEADD('HOUR',-73,CURRENT_TIMESTAMP))
				""");
		jdbc.update("""
				INSERT INTO app_notificaciones(id,cuenta_id,tipo,titulo,descripcion,leida,created_at,read_at)
				VALUES (18901,3001,'vieja_leida','Vieja','Debe limpiarse',true,DATEADD('DAY',-40,CURRENT_TIMESTAMP),
					DATEADD('DAY',-31,CURRENT_TIMESTAMP))
				""");
		jdbc.update("""
				INSERT INTO app_notificaciones(id,cuenta_id,tipo,titulo,descripcion,leida,created_at)
				VALUES (18902,3001,'vieja_no_leida','Vieja','Debe limpiarse',false,DATEADD('DAY',-91,CURRENT_TIMESTAMP))
				""");
		jdbc.update("""
				INSERT INTO app_documentos(id,tipo,referencia_tipo,referencia_id,archivo_id,estado)
				VALUES (18903,'factura_compra','compra',13002,1,'disponible')
				""");

		admin(post("/api/admin/vencimientos/procesar")).andExpect(status().isOk())
				.andExpect(jsonPath("$.data.estado").value("procesado"))
				.andExpect(jsonPath("$.data.detalle", containsString("Medios vencidos: 1")))
				.andExpect(jsonPath("$.data.detalle", containsString("multas vencidas: 1")))
				.andExpect(jsonPath("$.data.detalle", containsString("compras abandonadas: 1")))
				.andExpect(jsonPath("$.data.detalle", containsString("devoluciones vencidas: 1")))
				.andExpect(jsonPath("$.data.detalle", containsString("notificaciones eliminadas: 2")));

		assertEquals("vencido", jdbc.queryForObject("SELECT estado FROM app_medios_pago WHERE id=5094", String.class));
		assertEquals("bloqueada_permanente", jdbc.queryForObject("SELECT estado FROM app_cuentas WHERE id=3002", String.class));
		assertEquals("abandonada_por_incumplimiento_pago",
				jdbc.queryForObject("SELECT estado FROM app_compras WHERE id=13002", String.class));
		assertEquals("devolucion_incompleta",
				jdbc.queryForObject("SELECT estado FROM app_solicitudes_consignacion WHERE id=16901", String.class));
		assertEquals(0, count("SELECT COUNT(*) FROM app_notificaciones WHERE id IN (18901,18902)"));
		assertEquals(1, count("SELECT COUNT(*) FROM app_documentos WHERE id=18903"));
	}

	@Test void adminProcesadoresDeVencimientosPuntuales() throws Exception {
		jdbc.update("UPDATE app_multas SET vence_at=DATEADD('HOUR',-1,CURRENT_TIMESTAMP) WHERE id=14001");
		admin(post("/api/admin/multas/vencer-expiradas")).andExpect(status().isOk())
				.andExpect(jsonPath("$.data.detalle").value("1 multas vencidas"));

		jdbc.update("UPDATE app_compras SET updated_at=DATEADD('HOUR',-73,CURRENT_TIMESTAMP) WHERE id=13002");
		admin(post("/api/admin/compras/abandonar-vencidas")).andExpect(status().isOk())
				.andExpect(jsonPath("$.data.detalle").value("1 compras abandonadas"));

		jdbc.update("""
				INSERT INTO app_notificaciones(id,cuenta_id,tipo,titulo,descripcion,leida,created_at)
				VALUES (18904,3001,'vieja','Vieja','Debe limpiarse',false,DATEADD('DAY',-91,CURRENT_TIMESTAMP))
				""");
		admin(post("/api/admin/notificaciones/limpiar")).andExpect(status().isOk())
				.andExpect(jsonPath("$.data.detalle").value("1 notificaciones eliminadas"));
	}

	@Test void adminCreaAbreYSeteaItemActivo() throws Exception {
		String date = LocalDate.now().plusDays(20).toString();
		String body = """
				{"fecha":"%s","hora":"18:30:00","titulo":"Subasta admin","descripcion":"Demo",
				"ubicacion":"Casa central","categoria":"comun","moneda":"ARS","segmento":"demo"}
				""".formatted(date);
		String json = admin(post("/api/admin/subastas").contentType(MediaType.APPLICATION_JSON).content(body))
				.andExpect(status().isOk()).andExpect(jsonPath("$.data.estado").value("programada"))
				.andReturn().getResponse().getContentAsString();
		Integer id = ((Number) JsonPath.read(json, "$.data.id")).intValue();
		assertNull(jdbc.queryForObject("SELECT monto_minimo FROM app_subasta_ext WHERE subasta_id=?", Integer.class, id));
		assertNull(jdbc.queryForObject("SELECT monto_maximo FROM app_subasta_ext WHERE subasta_id=?", Integer.class, id));
		jdbc.update("""
				INSERT INTO app_inscripciones_subasta(subasta_id,cuenta_id,medio_pago_id,estado)
				VALUES (?,3001,5001,'aprobada')
				""", id);
		jdbc.update("UPDATE subastas SET estado='cerrada' WHERE identificador=?", id);
		admin(post("/api/admin/subastas/" + id + "/abrir")).andExpect(status().isOk())
				.andExpect(jsonPath("$.data.estado").value("en_vivo"));
		assertEquals("cerrada", jdbc.queryForObject("SELECT estado FROM subastas WHERE identificador=?", String.class, id));
		assertTrue(delivered("notification", "subasta_inscripta_proxima_inicio"));
		assertNotNull(jdbc.queryForObject(
				"SELECT proximo_lote_programado_at FROM app_subasta_estado_vivo WHERE subasta_id=?",
				java.time.OffsetDateTime.class, id));
		admin(post("/api/admin/subastas/6004/item-activo").contentType(MediaType.APPLICATION_JSON)
				.content("{\"itemCatalogoId\":9006}")).andExpect(status().isOk());
		assertEquals(9006, jdbc.queryForObject("SELECT item_catalogo_activo_id FROM app_subasta_estado_vivo WHERE subasta_id=6004",
				Integer.class));
	}

	@Test void adminValidaLimitesOpcionalesDeSubasta() throws Exception {
		String date = LocalDate.now().plusDays(20).toString();
		String body = """
				{"fecha":"%s","hora":"18:30:00","titulo":"Subasta admin","ubicacion":"Casa central",
				"categoria":"comun","moneda":"ARS",%s}
				""";
		for (String limits : List.of("\"montoMinimo\":-1", "\"montoMaximo\":-1",
				"\"montoMinimo\":200,\"montoMaximo\":100")) {
			admin(post("/api/admin/subastas").contentType(MediaType.APPLICATION_JSON).content(body.formatted(date, limits)))
					.andExpect(status().isBadRequest())
					.andExpect(jsonPath("$.errors[0].code").value("INVALID_AMOUNT_RANGE"));
		}
		for (String limits : List.of("\"montoMinimo\":100", "\"montoMaximo\":200",
				"\"montoMinimo\":100,\"montoMaximo\":200")) {
			admin(post("/api/admin/subastas").contentType(MediaType.APPLICATION_JSON).content(body.formatted(date, limits)))
					.andExpect(status().isOk());
		}
	}

	@Test void adminCierraLoteConPujaYSinPujas() throws Exception {
		jdbc.update("UPDATE productos SET duenio=2004 WHERE identificador=8001");
		admin(post("/api/admin/subastas/6001/cerrar-lote")).andExpect(status().isOk())
				.andExpect(jsonPath("$.data.estado").value("pagos_extra_pendientes"));
		assertEquals(false, jdbc.queryForObject("SELECT comprador_empresa FROM app_compras WHERE item_catalogo_id=9001",
				Boolean.class));
		assertTrue(delivered("notification", "lote_ganado"));

		admin(post("/api/admin/subastas/6004/abrir")).andExpect(status().isOk());
		admin(post("/api/admin/subastas/6004/item-activo").contentType(MediaType.APPLICATION_JSON)
				.content("{\"itemCatalogoId\":9006}")).andExpect(status().isOk());
		admin(post("/api/admin/subastas/6004/cerrar-lote")).andExpect(status().isOk())
				.andExpect(jsonPath("$.data.estado").value("pagos_extra_pendientes"));
		assertEquals(true, jdbc.queryForObject("SELECT comprador_empresa FROM app_compras WHERE item_catalogo_id=9006",
				Boolean.class));
	}

	@Test void adminSimulaPagoExitosoYFallido() throws Exception {
		jdbc.update("INSERT INTO app_entregas(compra_id,tipo,costo_envio,estado) VALUES (13002,'retiro',0,'pendiente')");
		admin(post("/api/admin/compras/13002/simular-pago-exitoso").contentType(MediaType.APPLICATION_JSON)
				.content("{\"cuentaId\":3001,\"medioPagoId\":5001}")).andExpect(status().isOk())
				.andExpect(jsonPath("$.data.estado").value("aprobado"));

		jdbc.update("UPDATE app_medios_pago SET limite_monto=500000 WHERE id=5004");
		admin(post("/api/admin/compras/13001/simular-falla-pago").contentType(MediaType.APPLICATION_JSON)
				.content("{\"cuentaId\":3002,\"medioPagoId\":5004,\"tipo\":\"multa\"}")).andExpect(status().isOk())
				.andExpect(jsonPath("$.data.estado").value("rechazado"));
	}

	@Test void adminPuedeMarcarCompraAbandonada() throws Exception {
		admin(post("/api/admin/compras/13002/abandonar")).andExpect(status().isOk())
				.andExpect(jsonPath("$.data.estado").value("abandonada"));
		assertEquals("abandonada_por_incumplimiento_pago",
				jdbc.queryForObject("SELECT estado FROM app_compras WHERE id=13002", String.class));
		admin(post("/api/admin/compras/13001/abandonar?retiro=true")).andExpect(status().isOk());
		assertEquals("abandonada_por_incumplimiento_retiro",
				jdbc.queryForObject("SELECT estado FROM app_compras WHERE id=13001", String.class));
	}

	@Test void adminVenceYMarcaPagadaMulta() throws Exception {
		admin(post("/api/admin/multas/14001/vencer")).andExpect(status().isOk());
		assertEquals("bloqueada_permanente", jdbc.queryForObject("SELECT estado FROM app_cuentas WHERE id=3002", String.class));

		jdbc.update("UPDATE app_multas SET estado='pendiente' WHERE id=14001");
		admin(post("/api/admin/multas/14001/marcar-pagada")).andExpect(status().isOk());
		assertEquals("activa", jdbc.queryForObject("SELECT estado FROM app_cuentas WHERE id=3002", String.class));
		assertEquals(1, count("SELECT COUNT(*) FROM app_documentos WHERE referencia_id=13001 AND tipo='recibo_multa' AND estado='disponible'"));
	}

	@Test void adminGestionaRevisionDocumentalYListaConsignaciones() throws Exception {
		admin(get("/api/admin/consignaciones")).andExpect(status().isOk())
				.andExpect(jsonPath("$.data", hasSize(3)));
		admin(post("/api/admin/consignaciones/16001/pedir-documentacion")).andExpect(status().isOk());
		assertEquals("documentacion_adicional",
				jdbc.queryForObject("SELECT estado FROM app_solicitudes_consignacion WHERE id=16001", String.class));
		assertTrue(delivered("notification", "consignacion_documentacion_requerida"));
		jdbc.update("""
				INSERT INTO app_archivos(id,tipo_contexto,filename_original,content_type,size_bytes,storage_path,checksum)
				VALUES (5291,'documento_consignacion','origen-admin.pdf','application/pdf',123,'demo/origen-admin.pdf','checksum-admin-origin')
				""");
		jdbc.update("""
				INSERT INTO app_consignacion_documentos_origen(id,solicitud_id,archivo_id,estado)
				VALUES (5292,16001,5291,'pendiente')
				""");
		jdbc.update("UPDATE app_solicitudes_consignacion SET estado='documentacion_recibida' WHERE id=16001");
		admin(post("/api/admin/consignaciones/16001/revisar-documentacion").contentType(MediaType.APPLICATION_JSON)
				.content("{\"aprobada\":true}")).andExpect(status().isOk());
		assertEquals("recepcion_pendiente",
				jdbc.queryForObject("SELECT estado FROM app_solicitudes_consignacion WHERE id=16001", String.class));
	}

	@Test void adminExigeDuenioAntesDeProponerAcuerdoYLuegoPublica() throws Exception {
		Long id = consignmentReadyForAgreement(3001L);
		admin(post("/api/admin/consignaciones/" + id + "/proponer-acuerdo").contentType(MediaType.APPLICATION_JSON)
				.content(agreement())).andExpect(status().isConflict())
				.andExpect(jsonPath("$.errors[0].code").value("CONSIGNOR_NOT_VERIFIED"));
		admin(post("/api/admin/consignadores/3001/verificar-duenio").contentType(MediaType.APPLICATION_JSON)
				.content("{\"financiera\":true,\"judicial\":true,\"riesgo\":2}")).andExpect(status().isOk());
		admin(post("/api/admin/consignaciones/" + id + "/proponer-acuerdo").contentType(MediaType.APPLICATION_JSON)
				.content(agreement())).andExpect(status().isOk());
		assertTrue(delivered("notification", "acuerdo_disponible"));
		consignments.acceptAgreement(3001L, id, new ConsignmentAgreementAcceptanceRequest(true, true));
		admin(post("/api/admin/consignaciones/" + id + "/asignar-subasta").contentType(MediaType.APPLICATION_JSON)
				.content("{\"subastaId\":6004,\"catalogoId\":7004}")).andExpect(status().isOk());
		assertEquals("publicada",
				jdbc.queryForObject("SELECT estado FROM app_solicitudes_consignacion WHERE id=?", String.class, id));
		assertTrue(delivered("notification", "consignacion_publicada"));
	}

	@Test void adminLiquidaConsignacionYSeedEsNoOpSeguro() throws Exception {
		admin(post("/api/admin/consignaciones/16007/liquidar").contentType(MediaType.APPLICATION_JSON)
				.content("{\"medioPagoId\":5006}")).andExpect(status().isOk())
				.andExpect(jsonPath("$.data.montoNeto").value(85500));
		assertTrue(delivered("notification", "liquidacion_disponible"));
		admin(post("/api/admin/seed/base")).andExpect(status().isOk())
				.andExpect(jsonPath("$.data.estado").value("sin_cambios"));
		admin(post("/api/admin/reset/demo")).andExpect(status().isOk())
				.andExpect(jsonPath("$.data.estado").value("preparada"))
				.andExpect(jsonPath("$.data.detalle", containsString("item 9006")));
		assertEquals("abierta", jdbc.queryForObject(
				"SELECT estado_operativo FROM app_subasta_ext WHERE subasta_id=6004", String.class));
		assertEquals("no", jdbc.queryForObject(
				"SELECT subastado FROM \"itemsCatalogo\" WHERE identificador=9006", String.class));
	}

	private long registration(String email) {
		jdbc.update("""
				INSERT INTO app_solicitudes_registro(email,nombre,apellido,domicilio_legal,id_pais_origen,estado)
				VALUES (?,'Admin','Demo','Calle 1',32,'pendiente_revision')
				""", email);
		return jdbc.queryForObject("SELECT id FROM app_solicitudes_registro WHERE email=?", Long.class, email);
	}

	private Long consignmentReadyForAgreement(Long accountId) {
		Long id = consignments.create(accountId, "arte", "comun", true, true, "Articulo admin", "Descripcion demo", null,
				"1980", false, null, null, null, photos()).id();
		consignments.approveDigitalReview(id, 1002);
		consignments.markPhysicalReception(id, 1002);
		consignments.approvePhysicalReview(id, 1002);
		return id;
	}

	private String agreement() {
		return """
				{"valorBase":25000,"moneda":"ARS","comisionCompradorPct":10,
				"comisionVendedorPct":10,"condiciones":"Acuerdo demo"}
				""";
	}

	private List<MultipartFile> photos() {
		List<MultipartFile> result = new ArrayList<>();
		for (int i = 0; i < 6; i++) {
			result.add(new MockMultipartFile("fotos", "foto-" + i + ".png", "image/png",
					new byte[] { (byte) 137, 80, 78, 71, 13, 10, 26, 10 }));
		}
		return result;
	}

	private ResultActions admin(MockHttpServletRequestBuilder builder) throws Exception {
		return mvc.perform(builder.header(AdminInternalAuthenticationFilter.HEADER, ADMIN_KEY));
	}

	private String token(String email) throws Exception {
		String json = mvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
				.content("{\"email\":\"" + email + "\",\"clave\":\"Demo123!\"}"))
				.andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
		return JsonPath.read(json, "$.data.accessToken");
	}

	private int count(String sql, Object... args) {
		return jdbc.queryForObject(sql, Integer.class, args);
	}

	private boolean delivered(String kind, String purpose) {
		return mail.deliveries().stream().anyMatch(delivery ->
				delivery.kind().equals(kind) && delivery.purpose().equals(purpose));
	}
}
