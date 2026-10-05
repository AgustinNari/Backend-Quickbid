package com.example.quickbid.quickbid;

import static org.hamcrest.Matchers.greaterThan;
import static org.hamcrest.Matchers.nullValue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
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

import com.example.quickbid.quickbid.security.AuthRateLimitService;
import com.jayway.jsonpath.JsonPath;

@SpringBootTest(properties = {
		"app.mail.enabled=false",
		"app.frontend.base-url=quickbid://auth"
})
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Sql(scripts = "/auth-test-data.sql", executionPhase = Sql.ExecutionPhase.BEFORE_TEST_METHOD)
class ApiErrorIntegrationTests {

	@Autowired MockMvc mvc;
	@Autowired AuthRateLimitService limits;

	@BeforeEach
	void clearRateLimits() {
		limits.clear();
	}

	@Test
	void endpointProtegidoSinTokenDevuelveJsonEstandar() throws Exception {
		mvc.perform(get("/api/auth/sesion"))
				.andExpect(status().isUnauthorized())
				.andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
				.andExpect(jsonPath("$.data").value(nullValue()))
				.andExpect(jsonPath("$.message").isString())
				.andExpect(jsonPath("$.errors.length()").value(greaterThan(0)));
	}

	@Test
	void authLinkLandingsArePublicHtmlAndDoNotValidateTokens() throws Exception {
		mvc.perform(get("/auth-links/recuperar-clave").param("token", "abc"))
				.andExpect(status().isOk())
				.andExpect(content().contentTypeCompatibleWith(MediaType.TEXT_HTML))
				.andExpect(content().string(org.hamcrest.Matchers.containsString("Abrir QuickBid")))
				.andExpect(content().string(org.hamcrest.Matchers.containsString(
						"quickbid://auth/recuperar-clave?token=abc")));

		mvc.perform(get("/auth-links/completar-registro").param("token", "abc"))
				.andExpect(status().isOk())
				.andExpect(content().contentTypeCompatibleWith(MediaType.TEXT_HTML))
				.andExpect(content().string(org.hamcrest.Matchers.containsString(
						"quickbid://auth/completar-registro?token=abc")));
	}

	@Test
	void authLinkLandingHandlesMissingAndHtmlSensitiveTokensSafely() throws Exception {
		mvc.perform(get("/auth-links/recuperar-clave"))
				.andExpect(status().isBadRequest())
				.andExpect(content().contentTypeCompatibleWith(MediaType.TEXT_HTML))
				.andExpect(content().string(org.hamcrest.Matchers.containsString("Enlace incompleto")));
		mvc.perform(get("/auth-links/completar-registro"))
				.andExpect(status().isBadRequest())
				.andExpect(content().contentTypeCompatibleWith(MediaType.TEXT_HTML));

		mvc.perform(get("/auth-links/recuperar-clave").param("token", "<script>alert('x')</script>"))
				.andExpect(status().isOk())
				.andExpect(content().string(org.hamcrest.Matchers.not(
						org.hamcrest.Matchers.containsString("<script>"))))
				.andExpect(content().string(org.hamcrest.Matchers.containsString("&lt;script&gt;")))
				.andExpect(content().string(org.hamcrest.Matchers.containsString("%3Cscript%3E")));
	}

	@Test
	void tokenInvalidoDevuelveJsonEstandar() throws Exception {
		mvc.perform(get("/api/auth/sesion").header("Authorization", "Bearer token-invalido"))
				.andExpect(status().isUnauthorized())
				.andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
				.andExpect(jsonPath("$.data").value(nullValue()))
				.andExpect(jsonPath("$.message").isString())
				.andExpect(jsonPath("$.errors[0].code").value("UNAUTHORIZED"));
	}

	@Test
	void jsonMalformadoDevuelve400Uniforme() throws Exception {
		mvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
				.content("{\"email\":\"aprobado@quickbid.demo\""))
				.andExpect(status().isBadRequest())
				.andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
				.andExpect(jsonPath("$.data").value(nullValue()))
				.andExpect(jsonPath("$.message").isString())
				.andExpect(jsonPath("$.errors[0].code").value("INVALID_JSON"));
	}

	@Test
	void dtoInvalidoDevuelve400ConDetalleDeCampos() throws Exception {
		mvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
				.content("{}"))
				.andExpect(status().isBadRequest())
				.andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
				.andExpect(jsonPath("$.data").value(nullValue()))
				.andExpect(jsonPath("$.message").isString())
				.andExpect(jsonPath("$.errors.length()").value(greaterThan(0)))
				.andExpect(jsonPath("$.errors[0].field").isString());
	}

	@Test
	void recursoInexistenteDevuelve404Uniforme() throws Exception {
		mvc.perform(get("/api/items/999999"))
				.andExpect(status().isNotFound())
				.andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
				.andExpect(jsonPath("$.data").value(nullValue()))
				.andExpect(jsonPath("$.message").isString())
				.andExpect(jsonPath("$.errors[0].code").value("RESOURCE_NOT_FOUND"));
	}

	@Test
	void reglaDeNegocioValidableDevuelve422Uniforme() throws Exception {
		mvc.perform(post("/api/subastas/6001/pujar")
				.header("Authorization", "Bearer " + token("aprobado@quickbid.demo"))
				.contentType(MediaType.APPLICATION_JSON)
				.content(bid("25299", "api-error-422")))
				.andExpect(status().isUnprocessableEntity())
				.andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
				.andExpect(jsonPath("$.data").value(nullValue()))
				.andExpect(jsonPath("$.message").isString())
				.andExpect(jsonPath("$.errors.length()").value(greaterThan(0)));
	}

	@Test
	void conflictoDevuelve409Uniforme() throws Exception {
		String access = token("multa@quickbid.demo");
		mvc.perform(post("/api/usuario/medios-pago")
				.header("Authorization", "Bearer " + access)
				.contentType(MediaType.APPLICATION_JSON)
				.content(card("5555555555554444")))
				.andExpect(status().isCreated());

		mvc.perform(post("/api/usuario/medios-pago")
				.header("Authorization", "Bearer " + access)
				.contentType(MediaType.APPLICATION_JSON)
				.content(card("5555555555554444")))
				.andExpect(status().isConflict())
				.andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
				.andExpect(jsonPath("$.data").value(nullValue()))
				.andExpect(jsonPath("$.message").isString())
				.andExpect(jsonPath("$.errors[0].code").value("PAYMENT_METHOD_DUPLICATE"));
	}

	@Test
	void parametroConTipoInvalidoDevuelve400Uniforme() throws Exception {
		mvc.perform(get("/api/subastas?page=no-es-un-numero"))
				.andExpect(status().isBadRequest())
				.andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
				.andExpect(jsonPath("$.data").value(nullValue()))
				.andExpect(jsonPath("$.errors[0].field").value("page"))
				.andExpect(jsonPath("$.errors[0].code").value("INVALID_PARAMETER"));
	}

	private String token(String email) throws Exception {
		String json = mvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
				.content("{\"email\":\"" + email + "\",\"clave\":\"Demo123!\"}"))
				.andExpect(status().isOk())
				.andReturn().getResponse().getContentAsString();
		return JsonPath.read(json, "$.data.accessToken");
	}

	private String bid(String monto, String idempotencyKey) {
		return "{\"itemCatalogoId\":9001,\"monto\":" + monto + ",\"medioPagoId\":5001,"
				+ "\"clientStateVersion\":1,\"idempotencyKey\":\"" + idempotencyKey + "\"}";
	}

	private String card(String pan) {
		return "{\"tipo\":\"tarjeta\",\"moneda\":\"ARS\",\"nacional\":true,\"titular\":\"Titular Demo\","
				+ "\"numeroTarjeta\":\"" + pan + "\",\"cvv\":\"123\",\"vencimientoMes\":12,"
				+ "\"vencimientoAnio\":2030,\"marca\":\"Visa\"}";
	}
}
