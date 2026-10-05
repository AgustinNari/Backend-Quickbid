package com.example.quickbid.quickbid;

import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.greaterThan;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.nullValue;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.sql.Time;
import java.time.LocalDateTime;

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

import com.example.quickbid.quickbid.security.AuthRateLimitService;
import com.jayway.jsonpath.JsonPath;

@SpringBootTest(properties = "app.mail.enabled=false")
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Sql(scripts = "/auth-test-data.sql", executionPhase = Sql.ExecutionPhase.BEFORE_TEST_METHOD)
class SubastaIntegrationTests {
	@Autowired MockMvc mvc;
	@Autowired JdbcTemplate jdbc;
	@Autowired AuthRateLimitService limits;

	@BeforeEach void clearLimits() { limits.clear(); }

	@Test void invitadoListaSubastasSinDatosEconomicos() throws Exception {
		mvc.perform(get("/api/subastas")).andExpect(status().isOk())
				.andExpect(jsonPath("$.data.content", hasSize(4)))
				.andExpect(jsonPath("$.data.content[?(@.id == 6001)].estadoOperativo", hasItem("abierta")))
				.andExpect(jsonPath("$.data.content[0].precioBase").doesNotExist())
				.andExpect(jsonPath("$.errors", hasSize(0)));
	}

	@Test void listadoValidaFiltrosYPaginacionConErroresUniformes() throws Exception {
		mvc.perform(get("/api/subastas?estado=inexistente")).andExpect(status().isUnprocessableEntity())
				.andExpect(jsonPath("$.data").value(nullValue()))
				.andExpect(jsonPath("$.errors[0].code").value("INVALID_FILTER"));
		mvc.perform(get("/api/subastas?categoria=inexistente")).andExpect(status().isUnprocessableEntity())
				.andExpect(jsonPath("$.data").value(nullValue()))
				.andExpect(jsonPath("$.errors[0].code").value("INVALID_FILTER"));
		mvc.perform(get("/api/subastas?moneda=EUR")).andExpect(status().isUnprocessableEntity())
				.andExpect(jsonPath("$.data").value(nullValue()))
				.andExpect(jsonPath("$.errors[0].code").value("INVALID_FILTER"));
		mvc.perform(get("/api/subastas?fechaDesde=2026-06-10&fechaHasta=2026-06-01"))
				.andExpect(status().isUnprocessableEntity())
				.andExpect(jsonPath("$.data").value(nullValue()))
				.andExpect(jsonPath("$.errors[0].code").value("INVALID_FILTER"));
		mvc.perform(get("/api/subastas?page=-1")).andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.data").value(nullValue()))
				.andExpect(jsonPath("$.errors[0].code").value("INVALID_PAGE"));
	}

	@Test void listadoAceptaFiltrosDocumentadosNormalizados() throws Exception {
		mvc.perform(get("/api/subastas?estado=EN_VIVO&categoria=PLATA&moneda=ars&page=0&size=10"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.data.content", hasSize(1)))
				.andExpect(jsonPath("$.data.content[0].id").value(6001))
				.andExpect(jsonPath("$.errors", hasSize(0)));
	}

	@Test void listadoConTokenInvalidoDevuelve401Uniforme() throws Exception {
		mvc.perform(get("/api/subastas").header("Authorization", "Bearer token-invalido"))
				.andExpect(status().isUnauthorized())
				.andExpect(jsonPath("$.data").value(nullValue()))
				.andExpect(jsonPath("$.errors[0].code").value("UNAUTHORIZED"));
	}

	@Test void invitadoDetalleNoExponeLive() throws Exception {
		mvc.perform(get("/api/subastas/6001")).andExpect(status().isOk())
				.andExpect(jsonPath("$.data.autenticado").doesNotExist())
				.andExpect(jsonPath("$.data.estadoOperativo").value("abierta"))
				.andExpect(jsonPath("$.data.itemActivoId").doesNotExist())
				.andExpect(jsonPath("$.data.mejorOfertaActual").doesNotExist())
				.andExpect(content().string(not(containsString("25100"))));
	}

	@Test void detalleSubastaExponeRematadorSinTextoDemoVisible() throws Exception {
		request(get("/api/subastas/6001"), "aprobado@quickbid.demo").andExpect(status().isOk())
				.andExpect(jsonPath("$.data.rematador.nombre").value("Martillero QuickBid"))
				.andExpect(jsonPath("$.data.rematador.matricula").value("MAT-QB-001"))
				.andExpect(jsonPath("$.data.rematador.region").value("Buenos Aires"))
				.andExpect(content().string(not(containsString("Martillero Demo"))))
				.andExpect(content().string(not(containsString("MAT-DEMO"))));
	}

	@Test void detalleSubastaInexistenteDevuelve404Uniforme() throws Exception {
		mvc.perform(get("/api/subastas/99999")).andExpect(status().isNotFound())
				.andExpect(jsonPath("$.data").value(nullValue()))
				.andExpect(jsonPath("$.errors[0].code").value("RESOURCE_NOT_FOUND"));
	}

	@Test void invitadoCatalogoNoVePrecioBase() throws Exception {
		mvc.perform(get("/api/subastas/6001/catalogo")).andExpect(status().isOk())
				.andExpect(jsonPath("$.data.items[0].precioBase").doesNotExist())
				.andExpect(jsonPath("$.data.items[0].comision").doesNotExist())
				.andExpect(jsonPath("$.data.items[0].fotoIds").isArray())
				.andExpect(content().string(not(containsString("medioPago"))))
				.andExpect(content().string(not(containsString("titular"))));
	}

	@Test void catalogoDeSubastaInexistenteDevuelve404Uniforme() throws Exception {
		mvc.perform(get("/api/subastas/99999/catalogo")).andExpect(status().isNotFound())
				.andExpect(jsonPath("$.data").value(nullValue()))
				.andExpect(jsonPath("$.errors[0].code").value("RESOURCE_NOT_FOUND"));
	}

	@Test void invitadoDetalleItemNoVePrecioBase() throws Exception {
		mvc.perform(get("/api/items/9001")).andExpect(status().isOk())
				.andExpect(jsonPath("$.data.precioBase").doesNotExist())
				.andExpect(jsonPath("$.data.comision").doesNotExist())
				.andExpect(jsonPath("$.data.fotoIds").isArray())
				.andExpect(content().string(not(containsString("mejorOferta"))));
	}

	@Test void usuarioAutenticadoVePrecioBaseEnDetalleItem() throws Exception {
		request(get("/api/items/9001"), "aprobado@quickbid.demo").andExpect(status().isOk())
				.andExpect(jsonPath("$.data.precioBase").value(20000))
				.andExpect(jsonPath("$.data.comision").value(2000))
				.andExpect(jsonPath("$.errors", hasSize(0)));
	}

	@Test void detalleItemExponeDatosDeConsignacionCuandoExisten() throws Exception {
		request(get("/api/items/9005"), "aprobado@quickbid.demo").andExpect(status().isOk())
				.andExpect(jsonPath("$.data.duenioActual").value("Propietario registrado"))
				.andExpect(jsonPath("$.data.fechaObjeto").value("1970"))
				.andExpect(jsonPath("$.data.historia").value("Mesa restaurada con procedencia particular."))
				.andExpect(jsonPath("$.data.historiaExtendida").value("Trabajo de restauracion documentado por el equipo de catalogacion."))
				.andExpect(jsonPath("$.data.artistaDisenador").value("Taller de restauracion QuickBid"))
				.andExpect(jsonPath("$.data.segmentoConsignacion").value("muebles"))
				.andExpect(jsonPath("$.data.categoriaAsignada").value("comun"))
				.andExpect(jsonPath("$.data.consignacionId").value(16007));
	}

	@Test void detalleItemNoRompeSiNoHayDatosDeConsignacion() throws Exception {
		request(get("/api/items/9001"), "aprobado@quickbid.demo").andExpect(status().isOk())
				.andExpect(jsonPath("$.data.historia").doesNotExist())
				.andExpect(jsonPath("$.data.historiaExtendida").doesNotExist())
				.andExpect(jsonPath("$.data.consignacionId").doesNotExist());
	}

	@Test void detalleItemInexistenteDevuelve404Uniforme() throws Exception {
		mvc.perform(get("/api/items/99999")).andExpect(status().isNotFound())
				.andExpect(jsonPath("$.data").value(nullValue()))
				.andExpect(jsonPath("$.errors[0].code").value("RESOURCE_NOT_FOUND"));
	}

	@Test void usuarioAutenticadoVePrecioBase() throws Exception {
		request(get("/api/subastas/6001/catalogo"), "aprobado@quickbid.demo").andExpect(status().isOk())
				.andExpect(jsonPath("$.data.items[0].precioBase").value(20000));
	}

	@Test void catalogoAutenticadoExponeOrdenYEstadosDeLotes() throws Exception {
		liveAuction(6110, 9110);
		int catalogId = 11110;
		addCatalogItem(catalogId, 9111, "si");
		addCatalogItem(catalogId, 9112, "si");
		addCatalogItem(catalogId, 9113, "si");
		addCatalogItem(catalogId, 9114, "no");
		jdbc.update("""
				INSERT INTO app_compras(subasta_id,item_catalogo_id,producto_id,cuenta_comprador_id,comprador_empresa,
					puja_id,monto_adjudicacion,moneda,estado,medio_pago_id,comision_comprador,comision_vendedor)
				VALUES (6110,9111,10111,3001,false,NULL,1200,'ARS','pagada',5001,0,0),
				       (6110,9112,10112,NULL,true,NULL,1000,'ARS','pagada',NULL,0,0)
				""");

		request(get("/api/subastas/6110/catalogo"), "aprobado@quickbid.demo").andExpect(status().isOk())
				.andExpect(jsonPath("$.data.items[?(@.id == 9110)].ordenLote", hasItem(1)))
				.andExpect(jsonPath("$.data.items[?(@.id == 9110)].estadoLote", hasItem("en_vivo")))
				.andExpect(jsonPath("$.data.items[?(@.id == 9111)].estadoLote", hasItem("vendido")))
				.andExpect(jsonPath("$.data.items[?(@.id == 9112)].estadoLote", hasItem("comprado_por_empresa")))
				.andExpect(jsonPath("$.data.items[?(@.id == 9113)].estadoLote", hasItem("no_vendido")))
				.andExpect(jsonPath("$.data.items[?(@.id == 9114)].estadoLote", hasItem("pendiente")));
	}

	@Test void pujaActualSinTokenEs401() throws Exception {
		mvc.perform(get("/api/subastas/6001/puja-actual")).andExpect(status().isUnauthorized())
				.andExpect(jsonPath("$.data").value(nullValue()));
	}

	@Test void restringidoPuedeNavegarPeroNoInscribirseNiPujar() throws Exception {
		request(get("/api/subastas/6001"), "multa@quickbid.demo").andExpect(status().isOk())
				.andExpect(jsonPath("$.data.autenticado").value(true));
		request(post("/api/subastas/6002/inscribirse").contentType(MediaType.APPLICATION_JSON).content("{}"),
				"multa@quickbid.demo").andExpect(status().isForbidden());
		request(post("/api/subastas/6001/verificacion"), "multa@quickbid.demo").andExpect(status().isOk())
				.andExpect(jsonPath("$.data.cuentaRestringida").value(true))
				.andExpect(jsonPath("$.data.puedePujar").value(false));
	}

	@Test void categoriaMenorPuedeNavegarPeroNoInscribirseNiPujar() throws Exception {
		request(get("/api/subastas/6004"), "aprobado@quickbid.demo").andExpect(status().isOk());
		request(post("/api/subastas/6004/inscribirse").contentType(MediaType.APPLICATION_JSON).content("{\"medioPagoId\":5001}"),
				"aprobado@quickbid.demo").andExpect(status().isForbidden())
				.andExpect(jsonPath("$.errors[0].code").value("AUCTION_CATEGORY_FORBIDDEN"));
		request(post("/api/subastas/6004/verificacion"), "aprobado@quickbid.demo").andExpect(status().isOk())
				.andExpect(jsonPath("$.data.categoriaInsuficienteParaPujar").value(true));
	}

	@Test void inscripcionValidaConMedioVerificadoVigente() throws Exception {
		request(post("/api/subastas/6002/inscribirse").contentType(MediaType.APPLICATION_JSON).content("{}"),
				"aprobado@quickbid.demo").andExpect(status().isCreated())
				.andExpect(jsonPath("$.data.estado").value("aprobada"))
				.andExpect(jsonPath("$.data.medioPagoId").value(5002));
	}

	@Test void inscripcionValidaConMedioPendiente() throws Exception {
		futureAuction(6101, "ARS", "especial", LocalDateTime.now().plusDays(3), "programada", null);
		request(post("/api/subastas/6101/inscribirse").contentType(MediaType.APPLICATION_JSON).content("{\"medioPagoId\":5003}"),
				"aprobado@quickbid.demo").andExpect(status().isCreated())
				.andExpect(jsonPath("$.data.estado").value("pendiente_validacion"))
				.andExpect(jsonPath("$.data.requiereRevisionMedioPago").value(true));
	}

	@Test void inscripcionValidaConVerificacionExpirada() throws Exception {
		expiredPayment(5099);
		futureAuction(6102, "ARS", "especial", LocalDateTime.now().plusDays(3), "programada", null);
		request(post("/api/subastas/6102/inscribirse").contentType(MediaType.APPLICATION_JSON).content("{\"medioPagoId\":5099}"),
				"aprobado@quickbid.demo").andExpect(status().isCreated())
				.andExpect(jsonPath("$.data.estado").value("pendiente_validacion"));
		request(post("/api/subastas/6102/verificacion"), "aprobado@quickbid.demo").andExpect(status().isOk())
				.andExpect(jsonPath("$.data.mediosPagoRevalidablesParaInscripcion[0].requiereRevalidacion").value(true));
	}

	@Test void inscripcionValidaConMedioVencido() throws Exception {
		jdbc.update("""
				INSERT INTO app_medios_pago(id,cuenta_id,tipo,moneda,estado,principal,nacional,alias_visible,titular,
					hash_identificador,limite_monto,consumo_actual,verificado_hasta)
				VALUES (5097,3001,'tarjeta','ARS','vencido',false,true,'Vencida','Ana','hash-vencida-subasta',
					5000,0,DATEADD('DAY',-1,CURRENT_TIMESTAMP))
				""");
		futureAuction(6108, "ARS", "especial", LocalDateTime.now().plusDays(3), "programada", null);
		request(post("/api/subastas/6108/inscribirse").contentType(MediaType.APPLICATION_JSON)
				.content("{\"medioPagoId\":5097}"), "aprobado@quickbid.demo").andExpect(status().isCreated())
				.andExpect(jsonPath("$.data.estado").value("pendiente_validacion"))
				.andExpect(jsonPath("$.data.requiereRevisionMedioPago").value(true));
	}

	@Test void inscripcionFallaConMedioRechazado() throws Exception {
		jdbc.update("INSERT INTO app_medios_pago(id,cuenta_id,tipo,moneda,estado,principal,nacional,alias_visible,titular,hash_identificador,consumo_actual) VALUES (5098,3001,'tarjeta','ARS','rechazado',false,true,'Rechazada','Ana','hash-rejected',0)");
		futureAuction(6103, "ARS", "especial", LocalDateTime.now().plusDays(3), "programada", null);
		request(post("/api/subastas/6103/inscribirse").contentType(MediaType.APPLICATION_JSON).content("{\"medioPagoId\":5098}"),
				"aprobado@quickbid.demo").andExpect(status().isUnprocessableEntity());
	}

	@Test void inscripcionFallaSinMedioDeLaMoneda() throws Exception {
		jdbc.update("UPDATE app_medios_pago SET estado='eliminado',deleted_at=CURRENT_TIMESTAMP WHERE id=5002");
		request(post("/api/subastas/6002/inscribirse").contentType(MediaType.APPLICATION_JSON).content("{}"),
				"aprobado@quickbid.demo").andExpect(status().isUnprocessableEntity())
				.andExpect(jsonPath("$.errors[0].code").value("PAYMENT_METHOD_CURRENCY_MISMATCH"));
	}

	@Test void inscripcionFallaCuandoRestanSesentaMinutos() throws Exception {
		futureAuction(6104, "ARS", "especial", LocalDateTime.now().plusMinutes(59), "programada", null);
		request(post("/api/subastas/6104/inscribirse").contentType(MediaType.APPLICATION_JSON).content("{\"medioPagoId\":5001}"),
				"aprobado@quickbid.demo").andExpect(status().isConflict())
				.andExpect(jsonPath("$.errors[0].code").value("AUCTION_ENROLLMENT_CLOSED"));
	}

	@Test void inscripcionFallaSiSubastaYaEmpezo() throws Exception {
		request(post("/api/subastas/6001/inscribirse").contentType(MediaType.APPLICATION_JSON).content("{\"medioPagoId\":5001}"),
				"aprobado@quickbid.demo").andExpect(status().isConflict());
	}

	@Test void inscripcionNoFallaPorParticiparEnOtraSubasta() throws Exception {
		request(post("/api/subastas/6002/inscribirse").contentType(MediaType.APPLICATION_JSON).content("{}"),
				"aprobado@quickbid.demo").andExpect(status().isCreated());
	}

	@Test void inscripcionDuplicadaDevuelveEstadoExistente() throws Exception {
		request(post("/api/subastas/6002/inscribirse").contentType(MediaType.APPLICATION_JSON).content("{}"),
				"aprobado@quickbid.demo").andExpect(status().isCreated());
		request(post("/api/subastas/6002/inscribirse").contentType(MediaType.APPLICATION_JSON).content("{}"),
				"aprobado@quickbid.demo").andExpect(status().isOk())
				.andExpect(jsonPath("$.data.existente").value(true));
	}

	@Test void inscripcionRechazadaPermiteReintentoConOtroMedio() throws Exception {
		futureAuction(6109, "ARS", "especial", LocalDateTime.now().plusDays(3), "programada", null);
		jdbc.update("""
				INSERT INTO app_inscripciones_subasta(subasta_id,cuenta_id,medio_pago_id,estado)
				VALUES (6109,3001,5001,'rechazada')
				""");
		request(post("/api/subastas/6109/inscribirse").contentType(MediaType.APPLICATION_JSON)
				.content("{\"medioPagoId\":5003}"), "aprobado@quickbid.demo").andExpect(status().isCreated())
				.andExpect(jsonPath("$.data.estado").value("pendiente_validacion"))
				.andExpect(jsonPath("$.data.existente").value(false));
		assertEquals(2, jdbc.queryForObject("""
				SELECT COUNT(*) FROM app_inscripciones_subasta WHERE subasta_id=6109 AND cuenta_id=3001
				""", Integer.class));
	}

	@Test void inscripcionNoCreaAsistenteLegacy() throws Exception {
		int before = jdbc.queryForObject("SELECT COUNT(*) FROM asistentes", Integer.class);
		request(post("/api/subastas/6002/inscribirse").contentType(MediaType.APPLICATION_JSON).content("{}"),
				"aprobado@quickbid.demo").andExpect(status().isCreated());
		assertEquals(before, jdbc.queryForObject("SELECT COUNT(*) FROM asistentes", Integer.class));
	}

	@Test void verificacionDiferenciaInscripcionYPujaAntesDeInicio() throws Exception {
		request(post("/api/subastas/6002/verificacion"), "aprobado@quickbid.demo").andExpect(status().isOk())
				.andExpect(jsonPath("$.data.puedeInscribirse").value(true))
				.andExpect(jsonPath("$.data.puedePujar").value(false))
				.andExpect(jsonPath("$.data.subastaNoIniciadaParaPuja").value(true));
	}

	@Test void verificacionLiveHabilitaPujaSiCumpleReglas() throws Exception {
		request(post("/api/subastas/6001/verificacion"), "aprobado@quickbid.demo").andExpect(status().isOk())
				.andExpect(jsonPath("$.data.puedeInscribirse").value(false))
				.andExpect(jsonPath("$.data.puedePujar").value(true));
	}

	@Test void pujaActualAutorizadaDevuelveSnapshot() throws Exception {
		request(get("/api/subastas/6001/puja-actual"), "aprobado@quickbid.demo").andExpect(status().isOk())
				.andExpect(jsonPath("$.data.itemActivoId").value(9001))
				.andExpect(jsonPath("$.data.mejorOfertaActual").value(25100))
				.andExpect(jsonPath("$.data.versionEstado").value(1));
	}

	@Test void pujaActualConLoteActivoSinPujasUsaDeadlineDelLote() throws Exception {
		liveAuction(6111, 9111);
		jdbc.update("""
				UPDATE app_subasta_estado_vivo
				SET lote_finaliza_estimado_at=DATEADD('SECOND',30,CURRENT_TIMESTAMP)
				WHERE subasta_id=6111
				""");

		request(get("/api/subastas/6111/puja-actual"), "aprobado@quickbid.demo").andExpect(status().isOk())
				.andExpect(jsonPath("$.data.estadoLote").value("activo"))
				.andExpect(jsonPath("$.data.tipoTimer").value("sin_pujas_empresa"))
				.andExpect(jsonPath("$.data.deadlineActual").exists())
				.andExpect(jsonPath("$.data.segundosRestantes", greaterThan(0)));
	}

	@Test void pujaActualSinLoteActivoDistingueProximoLoteYCierre() throws Exception {
		liveAuction(6112, 9112);
		addCatalogItem(11112, 9113, "no");
		jdbc.update("""
				UPDATE app_subasta_estado_vivo
				SET item_catalogo_activo_id=NULL,proximo_lote_programado_at=DATEADD('SECOND',45,CURRENT_TIMESTAMP)
				WHERE subasta_id=6112
				""");

		request(get("/api/subastas/6112/puja-actual"), "aprobado@quickbid.demo").andExpect(status().isOk())
				.andExpect(jsonPath("$.data.estadoLote").value("esperando_proximo_lote"))
				.andExpect(jsonPath("$.data.tipoTimer").value("esperando_proximo_lote"))
				.andExpect(jsonPath("$.data.proximoLoteAt").exists())
				.andExpect(jsonPath("$.data.siguienteItemId").value(9112));

		jdbc.update("""
				UPDATE "itemsCatalogo" SET subastado='si' WHERE identificador IN (9112,9113)
				""");
		jdbc.update("""
				UPDATE app_subasta_estado_vivo
				SET proximo_lote_programado_at=NULL,subasta_finaliza_programado_at=DATEADD('SECOND',45,CURRENT_TIMESTAMP)
				WHERE subasta_id=6112
				""");

		request(get("/api/subastas/6112/puja-actual"), "aprobado@quickbid.demo").andExpect(status().isOk())
				.andExpect(jsonPath("$.data.estadoLote").value("cerrando_subasta"))
				.andExpect(jsonPath("$.data.tipoTimer").value("cerrando_subasta"))
				.andExpect(jsonPath("$.data.subastaFinalizaAt").exists());
	}

	@Test void medioPendientePermiteInscripcionPeroNoPujaActual() throws Exception {
		liveAuction(6105, 9105);
		jdbc.update("INSERT INTO app_inscripciones_subasta(subasta_id,cuenta_id,medio_pago_id,estado) VALUES (6105,3001,5003,'pendiente_validacion')");
		request(post("/api/subastas/6105/verificacion"), "aprobado@quickbid.demo").andExpect(status().isOk())
				.andExpect(jsonPath("$.data.puedePujar").value(false));
		request(get("/api/subastas/6105/puja-actual"), "aprobado@quickbid.demo").andExpect(status().isOk())
				.andExpect(jsonPath("$.data.puedePujar").value(false));
	}

	@Test void medioVerificadoSinLimiteNoCuentaComoLimiteInfinito() throws Exception {
		jdbc.update("""
				INSERT INTO app_medios_pago(id,cuenta_id,tipo,moneda,estado,principal,nacional,alias_visible,titular,
					hash_identificador,limite_monto,consumo_actual,verificado_hasta)
				VALUES (5096,3001,'tarjeta','ARS','verificado',false,true,'Sin limite','Ana','hash-no-limit',
					NULL,0,DATEADD('DAY',7,CURRENT_TIMESTAMP))
				""");
		request(post("/api/subastas/6001/pujar").contentType(MediaType.APPLICATION_JSON)
				.content("{\"itemCatalogoId\":9001,\"monto\":25200,\"medioPagoId\":5096,"
						+ "\"clientStateVersion\":1,\"idempotencyKey\":\"no-limit-test\"}"),
				"aprobado@quickbid.demo").andExpect(status().isUnprocessableEntity())
				.andExpect(jsonPath("$.errors[0].code").value("PAYMENT_METHOD_INSUFFICIENT_FUNDS"));
	}

	@Test void verificacionLiveNoExigeInscripcionPreviaParaPujar() throws Exception {
		jdbc.update("DELETE FROM app_inscripciones_subasta WHERE cuenta_id=3001 AND subasta_id=6001");

		request(post("/api/subastas/6001/verificacion"), "aprobado@quickbid.demo").andExpect(status().isOk())
				.andExpect(jsonPath("$.data.yaInscripto").value(false))
				.andExpect(jsonPath("$.data.puedePujar").value(true));
	}

	@Test void verificacionExpiradaPermiteRevalidarPeroNoPujar() throws Exception {
		expiredPayment(5099);
		liveAuction(6106, 9106);
		jdbc.update("INSERT INTO app_inscripciones_subasta(subasta_id,cuenta_id,medio_pago_id,estado) VALUES (6106,3001,5099,'pendiente_validacion')");
		jdbc.update("UPDATE app_medios_pago SET deleted_at=CURRENT_TIMESTAMP,estado='eliminado' WHERE id=5001");
		request(post("/api/subastas/6106/verificacion"), "aprobado@quickbid.demo").andExpect(status().isOk())
				.andExpect(jsonPath("$.data.requiereRevalidacionMedioPagoParaPujar").value(true))
				.andExpect(jsonPath("$.data.puedePujar").value(false));
	}

	@Test void participacionEfectivaEnOtraSubastaBloqueaPujaNoInscripcion() throws Exception {
		liveAuction(6107, 9107);
		jdbc.update("INSERT INTO app_inscripciones_subasta(subasta_id,cuenta_id,medio_pago_id,estado) VALUES (6107,3001,5001,'aprobada')");
		request(post("/api/subastas/6107/verificacion"), "aprobado@quickbid.demo").andExpect(status().isOk())
				.andExpect(jsonPath("$.data.conectadoOParticipandoEnOtraSubasta").value(true))
				.andExpect(jsonPath("$.data.puedePujar").value(false));
	}

	private void futureAuction(int id, String currency, String category, LocalDateTime start, String state, Integer activeItem) {
		jdbc.update("INSERT INTO subastas(identificador,fecha,hora,estado,ubicacion,categoria) VALUES (?,?,?,?,?,?)",
				id, start.toLocalDate(), Time.valueOf(start.toLocalTime()), "abierta", "Demo", category);
		jdbc.update("INSERT INTO app_subasta_ext(subasta_id,titulo,descripcion,moneda,segmento,estado_operativo) VALUES (?,?,?,?,?,?)",
				id, "Subasta " + id, "Demo", currency, "demo", state);
		jdbc.update("INSERT INTO app_subasta_estado_vivo(subasta_id,item_catalogo_activo_id,version,usuarios_conectados) VALUES (?,?,?,?)",
				id, activeItem, 0, 0);
	}

	private void liveAuction(int id, int itemId) {
		int productId = itemId + 1000, catalogId = itemId + 2000;
		futureAuction(id, "ARS", "especial", LocalDateTime.now().plusDays(2), "en_vivo", itemId);
		jdbc.update("INSERT INTO productos(identificador,\"descripcionCatalogo\") VALUES (?,?)", productId, "Item demo");
		jdbc.update("INSERT INTO catalogos(identificador,descripcion,subasta) VALUES (?,?,?)", catalogId, "Catalogo demo", id);
		jdbc.update("INSERT INTO \"itemsCatalogo\"(identificador,catalogo,producto,\"precioBase\",comision,subastado) VALUES (?,?,?,?,?,?)",
				itemId, catalogId, productId, 1000, 100, "no");
	}

	private void addCatalogItem(int catalogId, int itemId, String auctioned) {
		int productId = itemId + 1000;
		jdbc.update("INSERT INTO productos(identificador,\"descripcionCatalogo\") VALUES (?,?)", productId,
				"Item demo " + itemId);
		jdbc.update("INSERT INTO \"itemsCatalogo\"(identificador,catalogo,producto,\"precioBase\",comision,subastado) VALUES (?,?,?,?,?,?)",
				itemId, catalogId, productId, 1000, 100, auctioned);
	}

	private void expiredPayment(long id) {
		jdbc.update("INSERT INTO app_medios_pago(id,cuenta_id,tipo,moneda,estado,principal,nacional,alias_visible,titular,hash_identificador,consumo_actual,verificado_hasta) VALUES (?,3001,'tarjeta','ARS','verificado',false,true,'Expirada','Ana',?,0,DATEADD('DAY',-1,CURRENT_TIMESTAMP))",
				id, "expired-" + id);
	}

	private ResultActions request(org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder builder, String email)
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
