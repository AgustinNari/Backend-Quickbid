package com.example.quickbid.quickbid;

import static org.hamcrest.Matchers.anyOf;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.nullValue;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.LocalDate;

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
import org.springframework.test.web.servlet.request.MockMultipartHttpServletRequestBuilder;

import com.example.quickbid.quickbid.security.AdminInternalAuthenticationFilter;
import com.example.quickbid.quickbid.security.AuthRateLimitService;
import com.jayway.jsonpath.JsonPath;

@SpringBootTest(properties = "app.mail.enabled=false")
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Sql(scripts = "/auth-test-data.sql", executionPhase = Sql.ExecutionPhase.BEFORE_TEST_METHOD)
class MedioPagoIntegrationTests {
	private static final String ADMIN_KEY = "test-admin-key";

	@Autowired MockMvc mvc;
	@Autowired JdbcTemplate jdbc;
	@Autowired AuthRateLimitService limits;

	@BeforeEach
	void clearLimits() {
		limits.clear();
	}

	@Test
	void listaMediosPropiosSinExponerMediosAjenos() throws Exception {
		request(get("/api/usuario/medios-pago"), "aprobado@quickbid.demo")
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.data", hasSize(3)))
				.andExpect(jsonPath("$.data[0].ultimos4", anyOf(nullValue(), is("4242"))))
				.andExpect(jsonPath("$.errors").isArray())
				.andExpect(jsonPath("$.data[?(@.id == 5004)]").isEmpty());
	}

	@Test
	void creaTarjetaSinPersistirNiExponerPanNiCvv() throws Exception {
		String response = request(post("/api/usuario/medios-pago")
				.contentType(MediaType.APPLICATION_JSON)
				.content(card("4111111111111111")), "multa@quickbid.demo")
				.andExpect(status().isCreated())
				.andExpect(jsonPath("$.data.estado").value("pendiente_verificacion"))
				.andExpect(jsonPath("$.data.ultimos4").value("1111"))
				.andExpect(jsonPath("$.data.numeroTarjeta").doesNotExist())
				.andExpect(jsonPath("$.data.cvv").doesNotExist())
				.andExpect(jsonPath("$.data.hashIdentificador").doesNotExist())
				.andReturn().getResponse().getContentAsString();

		assertFalse(response.contains("4111111111111111"));
		assertFalse(response.contains("\"cvv\""));
		assertFalse(response.contains("123"));

		String hash = jdbc.queryForObject("SELECT hash_identificador FROM app_medios_pago WHERE ultimos_4='1111'",
				String.class);
		assertNotEquals("4111111111111111", hash);
		assertFalse(hash.contains("123"));
	}

	@Test
	void creaCuentaBancariaSinExponerDatosSensibles() throws Exception {
		String body = """
				{"tipo":"cuenta_bancaria","moneda":"ARS","nacional":true,"titular":"Ana Aprobada",
				"numeroCuenta":"123456789","cbuCvu":"000123456789","nombreBanco":"Banco Demo","alias":"ANA.DEMO"}
				""";
		String response = request(post("/api/usuario/medios-pago").contentType(MediaType.APPLICATION_JSON).content(body),
				"aprobado@quickbid.demo")
				.andExpect(status().isCreated())
				.andExpect(jsonPath("$.data.banco").value("Banco Demo"))
				.andExpect(jsonPath("$.data.numeroCuenta").doesNotExist())
				.andExpect(jsonPath("$.data.cbuCvu").doesNotExist())
				.andReturn().getResponse().getContentAsString();

		assertFalse(response.contains("123456789"));
		assertFalse(response.contains("000123456789"));
	}

	@Test
	void requestInvalidoDevuelve400ConErroresNoNulos() throws Exception {
		request(post("/api/usuario/medios-pago").contentType(MediaType.APPLICATION_JSON).content("{}"),
				"aprobado@quickbid.demo")
				.andExpect(status().isBadRequest())
				.andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
				.andExpect(jsonPath("$.data").value(nullValue()))
				.andExpect(jsonPath("$.message").isString())
				.andExpect(jsonPath("$.errors").isArray())
				.andExpect(jsonPath("$.errors[0].field").isString());
	}

	@Test
	void tipoNoSoportadoDevuelveErrorControlado() throws Exception {
		String body = """
				{"tipo":"cripto","moneda":"ARS","nacional":true,"titular":"Ana Aprobada"}
				""";
		request(post("/api/usuario/medios-pago").contentType(MediaType.APPLICATION_JSON).content(body),
				"aprobado@quickbid.demo")
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.data").value(nullValue()))
				.andExpect(jsonPath("$.errors[0].code").value("INVALID_PAYMENT_METHOD"));
	}

	@Test
	void monedaInvalidaDevuelveErrorControlado() throws Exception {
		String body = """
				{"tipo":"tarjeta","moneda":"EUR","nacional":true,"titular":"Ana Aprobada",
				"numeroTarjeta":"4111111111111111","cvv":"123","vencimientoMes":12,"vencimientoAnio":2030}
				""";
		request(post("/api/usuario/medios-pago").contentType(MediaType.APPLICATION_JSON).content(body),
				"aprobado@quickbid.demo")
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.errors[0].code").value("INVALID_CURRENCY"));
	}

	@Test
	void chequeSinFotosEsError422Uniforme() throws Exception {
		request(multipart("/api/usuario/medios-pago")
				.param("moneda", "ARS")
				.param("nacional", "true")
				.param("titular", "Ana")
				.param("numeroCheque", "CH-1")
				.param("monto", "1000")
				.param("fechaVencimiento", LocalDate.now().plusDays(5).toString())
				.param("bancoEmisor", "Banco"), "aprobado@quickbid.demo")
				.andExpect(status().isUnprocessableEntity())
				.andExpect(jsonPath("$.data").value(nullValue()))
				.andExpect(jsonPath("$.errors").isArray());
	}

	@Test
	void chequeConFormatoInvalidoEsError() throws Exception {
		MockMultipartFile bad = new MockMultipartFile("fotoAnverso", "a.png", "image/png", "falso".getBytes());
		MockMultipartFile ok = new MockMultipartFile("fotoReverso", "b.png", "image/png", png());

		request(multipart("/api/usuario/medios-pago")
				.file(bad)
				.file(ok)
				.param("moneda", "ARS")
				.param("nacional", "true")
				.param("titular", "Ana")
				.param("numeroCheque", "CH-2")
				.param("monto", "1000")
				.param("fechaVencimiento", LocalDate.now().plusDays(5).toString())
				.param("bancoEmisor", "Banco"), "aprobado@quickbid.demo")
				.andExpect(status().isUnprocessableEntity())
				.andExpect(jsonPath("$.errors[0].code").value("FILE_TYPE_NOT_SUPPORTED"));
	}

	@Test
	void duplicadoEs409() throws Exception {
		var req = post("/api/usuario/medios-pago").contentType(MediaType.APPLICATION_JSON)
				.content(card("5555555555554444"));
		request(req, "multa@quickbid.demo").andExpect(status().isCreated());
		request(post("/api/usuario/medios-pago").contentType(MediaType.APPLICATION_JSON)
				.content(card("5555555555554444")), "multa@quickbid.demo")
				.andExpect(status().isConflict())
				.andExpect(jsonPath("$.data").value(nullValue()))
				.andExpect(jsonPath("$.errors[0].code").value("PAYMENT_METHOD_DUPLICATE"));
	}

	@Test
	void eliminaMedioPropioLogicamente() throws Exception {
		request(delete("/api/usuario/medios-pago/5005"), "multa@quickbid.demo")
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.data").value(nullValue()))
				.andExpect(jsonPath("$.errors").isArray());
		assertEquals("eliminado", jdbc.queryForObject("SELECT estado FROM app_medios_pago WHERE id=5005", String.class));
	}

	@Test
	void eliminarMedioPrincipalSinUsoLoDesmarcaYLoElimina() throws Exception {
		jdbc.update("""
				INSERT INTO app_medios_pago(id,cuenta_id,tipo,moneda,estado,principal,nacional,alias_visible,
					ultimos_4,titular,hash_identificador,consumo_actual,verificado_hasta)
				VALUES (5097,3002,'tarjeta','ARS','verificado',true,true,'Tarjeta 7777','7777',
					'Bruno','hash-delete-principal',0,DATEADD('DAY',5,CURRENT_TIMESTAMP))
				""");

		request(delete("/api/usuario/medios-pago/5097"), "multa@quickbid.demo").andExpect(status().isOk());

		MapRow row = jdbc.queryForObject("SELECT estado,principal,deleted_at IS NOT NULL deleted FROM app_medios_pago WHERE id=5097",
				(rs, number) -> new MapRow(rs.getString("estado"), rs.getBoolean("principal"), rs.getBoolean("deleted")));
		assertEquals("eliminado", row.estado());
		assertFalse(row.principal());
		assertEquals(true, row.deleted());
	}

	@Test
	void eliminarMedioAjenoEs403() throws Exception {
		request(delete("/api/usuario/medios-pago/5004"), "aprobado@quickbid.demo")
				.andExpect(status().isForbidden())
				.andExpect(jsonPath("$.data").value(nullValue()))
				.andExpect(jsonPath("$.errors[0].code").value("RESOURCE_NOT_OWNED"));
	}

	@Test
	void eliminarMedioAsociadoEs409() throws Exception {
		request(delete("/api/usuario/medios-pago/5001"), "aprobado@quickbid.demo")
				.andExpect(status().isConflict())
				.andExpect(jsonPath("$.errors[0].code").value("PAYMENT_METHOD_IN_USE"));
	}

	@Test
	void multaPendienteBloqueaEliminacion() throws Exception {
		jdbc.update("""
				INSERT INTO app_compras(id,subasta_id,item_catalogo_id,producto_id,cuenta_comprador_id,
					medio_pago_id,monto_adjudicacion,moneda,estado)
				VALUES (13999,6003,8003,1003,3002,5005,1000,'USD','completada')
				""");
		jdbc.update("""
				INSERT INTO app_multas(id,cuenta_id,compra_id,monto,moneda,estado)
				VALUES (14999,3002,13999,100,'USD','pendiente')
				""");
		request(delete("/api/usuario/medios-pago/5005"), "multa@quickbid.demo")
				.andExpect(status().isConflict())
				.andExpect(jsonPath("$.errors[0].code").value("PAYMENT_METHOD_IN_USE"));
	}

	@Test
	void marcaPrincipalPorMonedaDejaSoloUnoPrincipalParaElUsuario() throws Exception {
		request(patch("/api/usuario/medios-pago/5002/principal"), "aprobado@quickbid.demo")
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.data.principal").value(true));

		assertEquals(1, jdbc.queryForObject("""
				SELECT COUNT(*) FROM app_medios_pago
				WHERE cuenta_id=3001 AND moneda='USD' AND principal=true AND deleted_at IS NULL
				""", Integer.class));
		assertEquals(0, jdbc.queryForObject("""
				SELECT COUNT(*) FROM app_medios_pago
				WHERE cuenta_id<>3001 AND moneda='USD' AND principal=true AND deleted_at IS NULL
				""", Integer.class));
	}

	@Test
	void listaExponeUsoConfiableSoloCuandoExisteLimite() throws Exception {
		request(get("/api/usuario/medios-pago"), "aprobado@quickbid.demo")
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.data[?(@.id == 5001)].limiteMonto", contains(500000.0)))
				.andExpect(jsonPath("$.data[?(@.id == 5001)].limiteUsado", contains(50200.0)))
				.andExpect(jsonPath("$.data[?(@.id == 5001)].limiteDisponible", contains(449800.0)))
				.andExpect(jsonPath("$.data[?(@.id == 5003)].limiteMonto", contains(nullValue())))
				.andExpect(jsonPath("$.data[?(@.id == 5003)].limiteUsado", contains(nullValue())))
				.andExpect(jsonPath("$.data[?(@.id == 5003)].limiteDisponible", contains(nullValue())));
	}

	@Test
	void limiteDisponibleNuncaEsNegativoAnteConsumoMayorAlLimite() throws Exception {
		jdbc.update("UPDATE app_medios_pago SET consumo_actual=600000 WHERE id=5001");

		request(get("/api/usuario/medios-pago"), "aprobado@quickbid.demo")
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.data[?(@.id == 5001)].limiteUsado", contains(625100.0)))
				.andExpect(jsonPath("$.data[?(@.id == 5001)].limiteDisponible", contains(0)));
	}

	@Test
	void nuevoMedioVerificadoPuedeSerPrincipalSinAfectarOtraMoneda() throws Exception {
		String created = request(post("/api/usuario/medios-pago").contentType(MediaType.APPLICATION_JSON)
				.content(card("4000000000000002")), "aprobado@quickbid.demo")
				.andExpect(status().isCreated())
				.andReturn().getResponse().getContentAsString();
		Number id = JsonPath.read(created, "$.data.id");

		admin(post("/api/admin/medios-pago/" + id.longValue() + "/verificar")
				.contentType(MediaType.APPLICATION_JSON).content("{\"limiteAprobado\":250000}"))
				.andExpect(status().isOk());
		request(patch("/api/usuario/medios-pago/" + id.longValue() + "/principal"), "aprobado@quickbid.demo")
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.data.principal").value(true));
		request(patch("/api/usuario/medios-pago/" + id.longValue() + "/principal"), "aprobado@quickbid.demo")
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.data.principal").value(true));

		assertEquals(1, jdbc.queryForObject("""
				SELECT COUNT(*) FROM app_medios_pago
				WHERE cuenta_id=3001 AND moneda='ARS' AND principal=true AND deleted_at IS NULL
				""", Integer.class));
		assertEquals(false, jdbc.queryForObject("SELECT principal FROM app_medios_pago WHERE id=5001", Boolean.class));
		assertEquals(true, jdbc.queryForObject("SELECT principal FROM app_medios_pago WHERE id=5002", Boolean.class));
	}

	@Test
	void principalAjenoEs403() throws Exception {
		request(patch("/api/usuario/medios-pago/5004/principal"), "aprobado@quickbid.demo")
				.andExpect(status().isForbidden())
				.andExpect(jsonPath("$.errors[0].code").value("RESOURCE_NOT_OWNED"));
	}

	@Test
	void principalPendienteEs409() throws Exception {
		request(patch("/api/usuario/medios-pago/5003/principal"), "aprobado@quickbid.demo")
				.andExpect(status().isConflict())
				.andExpect(jsonPath("$.errors[0].code").value("PAYMENT_METHOD_NOT_VERIFIED"));
	}

	@Test
	void idsInexistentesDevuelven404Uniforme() throws Exception {
		request(delete("/api/usuario/medios-pago/999999"), "aprobado@quickbid.demo")
				.andExpect(status().isNotFound())
				.andExpect(jsonPath("$.data").value(nullValue()))
				.andExpect(jsonPath("$.errors[0].code").value("RESOURCE_NOT_FOUND"));
		request(patch("/api/usuario/medios-pago/999999/principal"), "aprobado@quickbid.demo")
				.andExpect(status().isNotFound())
				.andExpect(jsonPath("$.data").value(nullValue()))
				.andExpect(jsonPath("$.errors[0].code").value("RESOURCE_NOT_FOUND"));
	}

	@Test
	void endpointsSinTokenDevuelven401Uniforme() throws Exception {
		mvc.perform(get("/api/usuario/medios-pago"))
				.andExpect(status().isUnauthorized())
				.andExpect(jsonPath("$.data").value(nullValue()))
				.andExpect(jsonPath("$.errors[0].code").value("UNAUTHORIZED"));
		mvc.perform(post("/api/usuario/medios-pago").contentType(MediaType.APPLICATION_JSON).content(card("4111111111111111")))
				.andExpect(status().isUnauthorized())
				.andExpect(jsonPath("$.data").value(nullValue()))
				.andExpect(jsonPath("$.errors[0].code").value("UNAUTHORIZED"));
		mvc.perform(delete("/api/usuario/medios-pago/5001"))
				.andExpect(status().isUnauthorized())
				.andExpect(jsonPath("$.data").value(nullValue()))
				.andExpect(jsonPath("$.errors[0].code").value("UNAUTHORIZED"));
		mvc.perform(patch("/api/usuario/medios-pago/5001/principal"))
				.andExpect(status().isUnauthorized())
				.andExpect(jsonPath("$.data").value(nullValue()))
				.andExpect(jsonPath("$.errors[0].code").value("UNAUTHORIZED"));
	}

	private ResultActions request(MockHttpServletRequestBuilder builder, String email) throws Exception {
		return mvc.perform(builder.header("Authorization", "Bearer " + token(email)));
	}

	private ResultActions request(MockMultipartHttpServletRequestBuilder builder, String email) throws Exception {
		return mvc.perform(builder.header("Authorization", "Bearer " + token(email)));
	}

	private ResultActions admin(MockHttpServletRequestBuilder builder) throws Exception {
		return mvc.perform(builder.header(AdminInternalAuthenticationFilter.HEADER, ADMIN_KEY));
	}

	private String token(String email) throws Exception {
		String json = mvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
				.content("{\"email\":\"" + email + "\",\"clave\":\"Demo123!\"}"))
				.andExpect(status().isOk())
				.andReturn().getResponse().getContentAsString();
		return JsonPath.read(json, "$.data.accessToken");
	}

	private String card(String pan) {
		return "{\"tipo\":\"tarjeta\",\"moneda\":\"ARS\",\"nacional\":true,\"titular\":\"Titular Demo\","
				+ "\"numeroTarjeta\":\"" + pan + "\",\"cvv\":\"123\",\"vencimientoMes\":12,"
				+ "\"vencimientoAnio\":2030,\"marca\":\"Visa\"}";
	}

	private byte[] png() {
		return new byte[] {(byte) 137, 80, 78, 71, 13, 10, 26, 10};
	}

	private record MapRow(String estado, boolean principal, boolean deleted) {
	}
}
