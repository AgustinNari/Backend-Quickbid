package com.example.quickbid.quickbid;

import static org.hamcrest.Matchers.hasSize;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.jdbc.Sql;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest(properties = "app.mail.enabled=false")
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Sql(scripts = "/auth-test-data.sql", executionPhase = Sql.ExecutionPhase.BEFORE_TEST_METHOD)
class CatalogoIntegrationTests {

	@Autowired MockMvc mvc;

	@Test
	void paisesEsPublicoYPaginado() throws Exception {
		mvc.perform(get("/api/catalogos/paises?size=2"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.data.content", hasSize(2)))
				.andExpect(jsonPath("$.data.page").value(0))
				.andExpect(jsonPath("$.data.size").value(2))
				.andExpect(jsonPath("$.data.totalElements").value(5))
				.andExpect(jsonPath("$.data.totalPages").value(3));
	}

	@Test
	void paisesBuscaCaseInsensitivePorQOBuscar() throws Exception {
		mvc.perform(get("/api/catalogos/paises?q=ARG"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.data.content", hasSize(1)))
				.andExpect(jsonPath("$.data.content[0].id").value(32))
				.andExpect(jsonPath("$.data.content[0].nombre").value("Argentina"))
				.andExpect(jsonPath("$.data.content[0].nombreCorto").value("AR"))
				.andExpect(jsonPath("$.data.content[0].nacionalidad").value("argentina"));

		mvc.perform(get("/api/catalogos/paises?buscar=buenos"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.data.content[0].id").value(32));
	}

	@Test
	void detallePaisDevuelveArgentinaYOtrosIdsDan404Controlado() throws Exception {
		mvc.perform(get("/api/catalogos/paises/32"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.data.id").value(32))
				.andExpect(jsonPath("$.data.nombre").value("Argentina"));

		mvc.perform(get("/api/catalogos/paises/999"))
				.andExpect(status().isNotFound())
				.andExpect(jsonPath("$.errors[0].code").value("RESOURCE_NOT_FOUND"));
	}
}
