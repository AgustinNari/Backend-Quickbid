package com.example.quickbid.quickbid;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.nullValue;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.jdbc.Sql;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import com.example.quickbid.quickbid.security.AuthRateLimitService;
import com.jayway.jsonpath.JsonPath;

@SpringBootTest(properties = "app.mail.enabled=false")
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Sql(scripts = "/auth-test-data.sql", executionPhase = Sql.ExecutionPhase.BEFORE_TEST_METHOD)
class UsuarioIntegrationTests {

	@Autowired MockMvc mvc;
	@Autowired AuthRateLimitService limits;
	@Autowired org.springframework.jdbc.core.JdbcTemplate jdbc;

	@BeforeEach
	void clearLimits() {
		limits.clear();
	}

	@Test
	void perfilActivo() throws Exception {
		request(get("/api/usuario/perfil"), "aprobado@quickbid.demo")
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.data.email").value("aprobado@quickbid.demo"))
				.andExpect(jsonPath("$.data.permisos.puedePujar").value(true))
				.andExpect(jsonPath("$.errors", hasSize(0)))
				.andExpect(content().string(not(containsString("password"))))
				.andExpect(content().string(not(containsString("accessToken"))))
				.andExpect(content().string(not(containsString("refreshToken"))));
	}

	@Test
	void perfilConMultaPuedeNavegarPeroNoPujar() throws Exception {
		request(get("/api/usuario/perfil"), "multa@quickbid.demo")
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.data.estadoCuenta").value("restriccion_multa"))
				.andExpect(jsonPath("$.data.permisos.puedePujar").value(false));
	}

	@Test
	void perfilSinTokenEs401() throws Exception {
		mvc.perform(get("/api/usuario/perfil"))
				.andExpect(status().isUnauthorized())
				.andExpect(jsonPath("$.data").value(nullValue()))
				.andExpect(jsonPath("$.message").isString())
				.andExpect(jsonPath("$.errors[0].code").value("UNAUTHORIZED"));
	}

	@Test
	void perfilConTokenInvalidoEs401Uniforme() throws Exception {
		mvc.perform(get("/api/usuario/perfil").header("Authorization", "Bearer token-invalido"))
				.andExpect(status().isUnauthorized())
				.andExpect(jsonPath("$.data").value(nullValue()))
				.andExpect(jsonPath("$.message").isString())
				.andExpect(jsonPath("$.errors[0].code").value("UNAUTHORIZED"));
	}

	@Test
	void perfilIncluyeProgresoCategoria() throws Exception {
		request(get("/api/usuario/perfil"), "aprobado@quickbid.demo")
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.data.progreso.categoriaActual").value("plata"))
				.andExpect(jsonPath("$.data.progreso.siguienteCategoria").value("oro"))
				.andExpect(jsonPath("$.data.progreso.puntosFaltantes").value(600));
	}

	@Test
	void estadisticasDevuelvenMetricas() throws Exception {
		request(get("/api/usuario/estadisticas"), "aprobado@quickbid.demo")
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.data.periodo").value("total"))
				.andExpect(jsonPath("$.data.cantidadPujas").value(2))
				.andExpect(jsonPath("$.data.cantidadCompras").value(1))
				.andExpect(jsonPath("$.data.totalPagado").value(95000.0))
				.andExpect(jsonPath("$.data.compradorPostor.cantidadPujas").value(2))
				.andExpect(jsonPath("$.data.vendedorConsignador.consignaciones").value(0))
				.andExpect(jsonPath("$.data.actividadMensual").isArray())
				.andExpect(jsonPath("$.errors", hasSize(0)));
	}

	@Test
	void estadisticasAplicanPeriodoYSeparanConsignador() throws Exception {
		jdbc.update("""
				INSERT INTO app_pujas_live(id,subasta_id,item_catalogo_id,cuenta_id,medio_pago_id,monto,moneda,estado,
					secuencia,version_estado,idempotency_key,created_at)
				VALUES (12901,6003,9005,3001,5001,77777,'ARS','superada',9,1,'old-bid-period',
					DATEADD('YEAR',-2,CURRENT_TIMESTAMP))
				""");
		request(get("/api/usuario/estadisticas?periodo=total"), "aprobado@quickbid.demo")
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.data.cantidadPujas").value(3));
		request(get("/api/usuario/estadisticas?periodo=anual"), "aprobado@quickbid.demo")
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.data.periodo").value("anual"))
				.andExpect(jsonPath("$.data.cantidadPujas").value(2));
		request(get("/api/usuario/estadisticas?periodo=total"), "oro@quickbid.demo")
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.data.vendedorConsignador.consignaciones").value(3))
				.andExpect(jsonPath("$.data.vendedorConsignador.vendidas").value(1));
		request(get("/api/usuario/estadisticas?periodo=invalido"), "aprobado@quickbid.demo")
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.errors[0].code").value("INVALID_PERIOD"));
	}

	@Test
	void estadisticasSinTokenOTokenInvalidoSon401Uniforme() throws Exception {
		mvc.perform(get("/api/usuario/estadisticas"))
				.andExpect(status().isUnauthorized())
				.andExpect(jsonPath("$.data").value(nullValue()))
				.andExpect(jsonPath("$.errors[0].code").value("UNAUTHORIZED"));

		mvc.perform(get("/api/usuario/estadisticas").header("Authorization", "Bearer token-invalido"))
				.andExpect(status().isUnauthorized())
				.andExpect(jsonPath("$.data").value(nullValue()))
				.andExpect(jsonPath("$.errors[0].code").value("UNAUTHORIZED"));
	}

	@Test
	void estadisticasDeUsuarioSinActividadDevuelvenCeros() throws Exception {
		request(get("/api/usuario/estadisticas"), "oro@quickbid.demo")
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.data.cantidadPujas").value(0))
				.andExpect(jsonPath("$.data.cantidadCompras").value(0))
				.andExpect(jsonPath("$.data.totalPagado").value(0))
				.andExpect(jsonPath("$.data.actividadMensual", hasSize(0)))
				.andExpect(jsonPath("$.errors", hasSize(0)));
	}

	@Test
	void historialEsPaginado() throws Exception {
		request(get("/api/usuario/historial?page=0&size=2"), "aprobado@quickbid.demo")
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.data.content", hasSize(2)))
				.andExpect(jsonPath("$.data.totalElements").value(3))
				.andExpect(jsonPath("$.data.totalPages").value(2));
	}

	@Test
	void historialIncluyePujasGanadorasYSuperadas() throws Exception {
		jdbc.update("""
				INSERT INTO app_pujas_live(id,subasta_id,item_catalogo_id,cuenta_id,medio_pago_id,monto,moneda,estado,
					secuencia,version_estado,idempotency_key)
				VALUES (12902,6003,9005,3001,5001,88000,'ARS','superada',10,1,'history-outbid')
				""");
		request(get("/api/usuario/historial?page=0&size=10"), "aprobado@quickbid.demo")
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.data.content[?(@.estado=='ganadora')]").isArray())
				.andExpect(jsonPath("$.data.content[?(@.estado=='superada')]").isArray());
	}

	@Test
	void historialSinTokenTokenInvalidoYPaginacionInvalidaDevuelvenErroresUniformes() throws Exception {
		mvc.perform(get("/api/usuario/historial"))
				.andExpect(status().isUnauthorized())
				.andExpect(jsonPath("$.data").value(nullValue()))
				.andExpect(jsonPath("$.errors[0].code").value("UNAUTHORIZED"));

		mvc.perform(get("/api/usuario/historial").header("Authorization", "Bearer token-invalido"))
				.andExpect(status().isUnauthorized())
				.andExpect(jsonPath("$.data").value(nullValue()))
				.andExpect(jsonPath("$.errors[0].code").value("UNAUTHORIZED"));

		request(get("/api/usuario/historial?page=-1&size=20"), "aprobado@quickbid.demo")
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.data").value(nullValue()))
				.andExpect(jsonPath("$.errors[0].code").value("INVALID_PAGE"));
	}

	@Test
	void historialDeUsuarioSinActividadNoMuestraActividadAjena() throws Exception {
		request(get("/api/usuario/historial"), "oro@quickbid.demo")
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.data.content", hasSize(0)))
				.andExpect(jsonPath("$.data.totalElements").value(0))
				.andExpect(jsonPath("$.errors", hasSize(0)));
	}

	@Test
	void listaNotificaciones() throws Exception {
		request(get("/api/usuario/notificaciones?leida=false"), "aprobado@quickbid.demo")
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.data.content", hasSize(2)))
				.andExpect(jsonPath("$.errors", hasSize(0)));
	}

	@Test
	void listadoNotificacionesSinTokenOTokenInvalidoSon401Uniforme() throws Exception {
		mvc.perform(get("/api/usuario/notificaciones"))
				.andExpect(status().isUnauthorized())
				.andExpect(jsonPath("$.data").value(nullValue()))
				.andExpect(jsonPath("$.errors[0].code").value("UNAUTHORIZED"));

		mvc.perform(get("/api/usuario/notificaciones").header("Authorization", "Bearer token-invalido"))
				.andExpect(status().isUnauthorized())
				.andExpect(jsonPath("$.data").value(nullValue()))
				.andExpect(jsonPath("$.errors[0].code").value("UNAUTHORIZED"));
	}

	@Test
	void leeNotificacionPropia() throws Exception {
		request(patch("/api/usuario/notificaciones/18001/leer"), "aprobado@quickbid.demo")
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.data.leida").value(true))
				.andExpect(jsonPath("$.errors", hasSize(0)));
	}

	@Test
	void lecturaNotificacionInexistenteDevuelve404Uniforme() throws Exception {
		request(patch("/api/usuario/notificaciones/99999/leer"), "aprobado@quickbid.demo")
				.andExpect(status().isNotFound())
				.andExpect(jsonPath("$.data").value(nullValue()))
				.andExpect(jsonPath("$.errors[0].code").value("RESOURCE_NOT_FOUND"));
	}

	@Test
	void rechazaLecturaNotificacionAjena() throws Exception {
		request(patch("/api/usuario/notificaciones/18003/leer"), "aprobado@quickbid.demo")
				.andExpect(status().isForbidden())
				.andExpect(jsonPath("$.data").value(nullValue()))
				.andExpect(jsonPath("$.errors[0].code").value("RESOURCE_NOT_OWNED"));
	}

	@Test
	void marcarTodasComoLeidasAfectaSoloAlUsuarioAutenticado() throws Exception {
		request(patch("/api/usuario/notificaciones/all/leer"), "aprobado@quickbid.demo")
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.data", hasSize(2)))
				.andExpect(jsonPath("$.data[0].leida").value(true))
				.andExpect(jsonPath("$.errors", hasSize(0)));

		request(get("/api/usuario/notificaciones?leida=false"), "aprobado@quickbid.demo")
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.data.content", hasSize(0)));

		request(get("/api/usuario/notificaciones?leida=false"), "multa@quickbid.demo")
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.data.content", hasSize(1)))
				.andExpect(jsonPath("$.data.content[0].id").value(18003));
	}

	@Test
	void marcarTodasSinTokenOTokenInvalidoDevuelve401Uniforme() throws Exception {
		mvc.perform(patch("/api/usuario/notificaciones/all/leer"))
				.andExpect(status().isUnauthorized())
				.andExpect(jsonPath("$.data").value(nullValue()))
				.andExpect(jsonPath("$.errors[0].code").value("UNAUTHORIZED"));

		mvc.perform(patch("/api/usuario/notificaciones/all/leer").header("Authorization", "Bearer token-invalido"))
				.andExpect(status().isUnauthorized())
				.andExpect(jsonPath("$.data").value(nullValue()))
				.andExpect(jsonPath("$.errors[0].code").value("UNAUTHORIZED"));
	}

	@Test
	void direccionAusenteDevuelveNull() throws Exception {
		request(get("/api/usuario/direccion-envio"), "multa@quickbid.demo")
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.data").value(nullValue()))
				.andExpect(jsonPath("$.errors", hasSize(0)));
	}

	@Test
	void direccionProtegidaYValidaBody() throws Exception {
		mvc.perform(get("/api/usuario/direccion-envio"))
				.andExpect(status().isUnauthorized())
				.andExpect(jsonPath("$.errors[0].code").value("UNAUTHORIZED"));

		request(put("/api/usuario/direccion-envio")
				.contentType(MediaType.APPLICATION_JSON)
				.content("{\"alias\":\"Casa\"}"), "multa@quickbid.demo")
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.data").value(nullValue()))
				.andExpect(jsonPath("$.errors").isArray());
	}

	@Test
	void creaYActualizaDireccion() throws Exception {
		String body = """
				{"alias":"Trabajo","destinatario":"Bruno Restringido","calle":"Calle Demo","numero":"42",
				"codigoPostal":"C1001","localidad":"Buenos Aires","provincia":"Buenos Aires","pais":"Argentina"}
				""";
		request(put("/api/usuario/direccion-envio").contentType(MediaType.APPLICATION_JSON).content(body),
				"multa@quickbid.demo")
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.data.alias").value("Trabajo"));

		String updated = body.replace("Trabajo", "Casa");
		request(put("/api/usuario/direccion-envio").contentType(MediaType.APPLICATION_JSON).content(updated),
				"multa@quickbid.demo")
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.data.alias").value("Casa"));
	}

	@Test
	void direccionesPermitenColeccionMaximoCincoYBajaLogica() throws Exception {
		String bearer = token("multa@quickbid.demo");
		for (int i = 1; i <= 5; i++) {
			requestWithToken(post("/api/usuario/direcciones-envio").contentType(MediaType.APPLICATION_JSON)
					.content(addressBody("Dir " + i, "Calle " + i)), bearer)
					.andExpect(status().isOk());
		}
		requestWithToken(get("/api/usuario/direcciones-envio"), bearer)
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.data", hasSize(5)));
		requestWithToken(post("/api/usuario/direcciones-envio").contentType(MediaType.APPLICATION_JSON)
				.content(addressBody("Dir 6", "Calle 6")), bearer)
				.andExpect(status().isUnprocessableEntity())
				.andExpect(jsonPath("$.errors[0].code").value("ADDRESS_LIMIT_EXCEEDED"));

		String list = requestWithToken(get("/api/usuario/direcciones-envio"), bearer)
				.andReturn().getResponse().getContentAsString();
		Number id = JsonPath.read(list, "$.data[0].id");
		requestWithToken(delete("/api/usuario/direcciones-envio/" + id.longValue()), bearer)
				.andExpect(status().isOk());
		requestWithToken(get("/api/usuario/direcciones-envio"), bearer)
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.data", hasSize(4)));
		requestWithToken(post("/api/usuario/direcciones-envio").contentType(MediaType.APPLICATION_JSON)
				.content(addressBody("Dir nueva", "Calle nueva")), bearer)
				.andExpect(status().isOk());
	}

	@Test
	void direccionesMantienenUnaSolaPrincipal() throws Exception {
		String created = request(post("/api/usuario/direcciones-envio").contentType(MediaType.APPLICATION_JSON)
				.content(addressBody("Trabajo", "Calle Trabajo")), "aprobado@quickbid.demo")
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.data.principal").value(false))
				.andReturn().getResponse().getContentAsString();
		Number id = JsonPath.read(created, "$.data.id");
		request(patch("/api/usuario/direcciones-envio/" + id.longValue() + "/principal"), "aprobado@quickbid.demo")
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.data.principal").value(true));
		request(patch("/api/usuario/direcciones-envio/" + id.longValue() + "/principal"), "aprobado@quickbid.demo")
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.data.principal").value(true));
		request(get("/api/usuario/direcciones-envio"), "aprobado@quickbid.demo")
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.data", hasSize(2)))
				.andExpect(jsonPath("$.data[0].id").value(id.intValue()))
				.andExpect(jsonPath("$.data[0].principal").value(true))
				.andExpect(jsonPath("$.data[1].principal").value(false));
		assertEquals(1, jdbc.queryForObject("""
				SELECT COUNT(*) FROM app_direcciones_envio
				WHERE cuenta_id=3001 AND principal=true AND deleted_at IS NULL
				""", Integer.class));
	}

	@Test
	void principalDireccionInexistenteOAjenaNoDevuelve500() throws Exception {
		jdbc.update("""
				INSERT INTO app_direcciones_envio
					(id,cuenta_id,alias,destinatario,calle,numero,codigo_postal,localidad,provincia,pais,principal)
				VALUES (5199,3002,'Ajena','Bruno Restringido','Calle Ajena','42','C1001',
					'Buenos Aires','Buenos Aires','Argentina',false)
				""");

		request(patch("/api/usuario/direcciones-envio/5199/principal"), "aprobado@quickbid.demo")
				.andExpect(status().isForbidden());
		request(patch("/api/usuario/direcciones-envio/999999/principal"), "aprobado@quickbid.demo")
				.andExpect(status().isNotFound());
	}

	private String addressBody(String alias, String street) {
		return """
				{"alias":"%s","destinatario":"Demo Usuario","calle":"%s","numero":"42",
				"codigoPostal":"C1001","localidad":"Buenos Aires","provincia":"Buenos Aires","pais":"Argentina"}
				""".formatted(alias, street);
	}

	private ResultActions request(MockHttpServletRequestBuilder builder, String email) throws Exception {
		return mvc.perform(builder.header("Authorization", "Bearer " + token(email)));
	}

	private ResultActions requestWithToken(MockHttpServletRequestBuilder builder, String token) throws Exception {
		return mvc.perform(builder.header("Authorization", "Bearer " + token));
	}

	private String token(String email) throws Exception {
		String json = mvc.perform(post("/api/auth/login")
						.contentType(MediaType.APPLICATION_JSON)
						.content("{\"email\":\"" + email + "\",\"clave\":\"Demo123!\"}"))
				.andExpect(status().isOk())
				.andReturn()
				.getResponse()
				.getContentAsString();
		return JsonPath.read(json, "$.data.accessToken");
	}
}
