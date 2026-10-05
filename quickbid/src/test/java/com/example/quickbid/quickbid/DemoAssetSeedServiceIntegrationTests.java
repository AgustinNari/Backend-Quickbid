package com.example.quickbid.quickbid;

import static org.hamcrest.Matchers.hasSize;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.math.BigDecimal;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.core.io.support.ResourcePatternResolver;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.jdbc.Sql;
import org.springframework.test.web.servlet.MockMvc;

import com.example.quickbid.quickbid.service.DemoAssetSeedService;

@SpringBootTest(properties = {
		"app.mail.enabled=false",
		"app.demo.assets.base-path=classpath:/demo-assets-test/products"
})
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Sql(scripts = "/auth-test-data.sql", executionPhase = Sql.ExecutionPhase.BEFORE_TEST_METHOD)
class DemoAssetSeedServiceIntegrationTests {
	@Autowired DemoAssetSeedService seeder;
	@Autowired JdbcTemplate jdbc;
	@Autowired MockMvc mvc;
	@Autowired ResourcePatternResolver resources;

	@BeforeEach
	void setUpDemoRows() {
		jdbc.update("INSERT INTO subastas(identificador,fecha,hora,estado,ubicacion,categoria) VALUES (6011,CURRENT_DATE+30,'20:00:00','abierta','Demo','plata')");
		jdbc.update("INSERT INTO app_subasta_ext(subasta_id,titulo,descripcion,moneda,segmento,estado_operativo) VALUES (6011,'Demo','Demo','ARS','demo','abierta')");
		jdbc.update("INSERT INTO catalogos(identificador,descripcion,subasta,responsable) VALUES (7011,'Catalogo demo',6011,1004)");
		for (int offset = 0; offset < 4; offset++) {
			int productId = 8010 + offset;
			int itemId = 9010 + offset;
			long consignmentId = 16110L + offset;
			jdbc.update("""
					INSERT INTO productos(identificador,fecha,disponible,"descripcionCatalogo","descripcionCompleta",revisor,duenio)
					VALUES (?,CURRENT_DATE,'si',?,?,?,?)
					""", productId, "Producto demo " + productId, "Producto demo completo " + productId, 1002, 2004);
			jdbc.update("INSERT INTO fotos(identificador,producto,foto) VALUES (?,?,X'89504E470D0A1A0A')",
					8110 + offset, productId);
			jdbc.update("""
					INSERT INTO "itemsCatalogo"(identificador,catalogo,producto,"precioBase",comision,subastado)
					VALUES (?,?,?,?,?,'no')
					""", itemId, 7011, productId, new BigDecimal("1000.00"), new BigDecimal("100.00"));
			jdbc.update("""
					INSERT INTO app_solicitudes_consignacion(id,cuenta_id,cliente_id,producto_id,item_catalogo_id,subasta_id,
						titulo,descripcion,segmento,categoria_sugerida,declaracion_propiedad,acepta_devolucion_con_cargo,
						estado,revisor_empleado_id,valor_base_propuesto,moneda_propuesta,comision_comprador_pct,comision_vendedor_pct)
					VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)
					""", consignmentId, 3004, 2004, productId, itemId, 6011,
					"Producto demo " + productId, "Descripcion demo", "demo", "plata", true, true,
					"en_subasta", 1002, new BigDecimal("1000.00"), "ARS",
					new BigDecimal("10.00"), new BigDecimal("10.00"));
		}
	}

	@Test
	void seedsSixPhotosAndConsignmentFilesFromClasspathAssets() {
		seeder.seedDemoAssets();

		for (int offset = 0; offset < 4; offset++) {
			int productId = 8010 + offset;
			long consignmentId = 16110L + offset;
			assertEquals(6, count("""
					SELECT COUNT(*) FROM fotos
					WHERE producto=? AND identificador BETWEEN ? AND ?
					""", productId, photoBase(productId) + 1, photoBase(productId) + 6));
			assertEquals(0, count("SELECT COUNT(*) FROM fotos WHERE producto=? AND identificador=?",
					productId, 8110 + offset));
			assertEquals(6, count("SELECT COUNT(*) FROM app_consignacion_fotos WHERE solicitud_id=?",
					consignmentId));
			assertEquals(6, count("""
					SELECT COUNT(*) FROM app_consignacion_fotos cf
					JOIN app_archivos a ON a.id=cf.archivo_id
					WHERE cf.solicitud_id=? AND a.tipo_contexto='consignacion'
					  AND a.content_bytes IS NOT NULL AND OCTET_LENGTH(a.content_bytes)>0
					""", consignmentId));
		}
	}

	@Test
	void prioritizesDescriptiveNameOverLegacyForSameSlot() {
		seeder.seedDemoAssets();

		assertEquals("lampara_italiana_restaurada_01.png", jdbc.queryForObject("""
				SELECT filename_original FROM app_archivos
				WHERE id=821001
				""", String.class));
	}

	@Test
	void nonStrictWithNoValidImagesKeepsExistingPhotos() {
		var emptySeeder = new DemoAssetSeedService(jdbc, resources, true, false,
				"classpath:/demo-assets-empty/products");

		emptySeeder.seedDemoAssets();

		for (int offset = 0; offset < 4; offset++) {
			int productId = 8010 + offset;
			assertEquals(1, count("SELECT COUNT(*) FROM fotos WHERE producto=? AND identificador=?",
					productId, 8110 + offset));
			assertEquals(0, count("""
					SELECT COUNT(*) FROM fotos
					WHERE producto=? AND identificador BETWEEN ? AND ?
					""", productId, photoBase(productId) + 1, photoBase(productId) + 6));
		}
	}

	@Test
	void strictWithNoValidImagesFailsWithoutReplacingPhotos() {
		var strictSeeder = new DemoAssetSeedService(jdbc, resources, true, true,
				"classpath:/demo-assets-empty/products");

		assertThrows(IllegalStateException.class, strictSeeder::seedDemoAssets);

		for (int offset = 0; offset < 4; offset++) {
			int productId = 8010 + offset;
			assertEquals(1, count("SELECT COUNT(*) FROM fotos WHERE producto=? AND identificador=?",
					productId, 8110 + offset));
		}
	}

	@Test
	void exposesSeededPhotoEndpointAndItemDtoUrls() throws Exception {
		seeder.seedDemoAssets();

		mvc.perform(get("/api/items/fotos/811101"))
				.andExpect(status().isOk())
				.andExpect(content().contentTypeCompatibleWith(MediaType.IMAGE_PNG))
				.andExpect(result -> assertTrue(result.getResponse().getContentAsByteArray().length > 8));

		mvc.perform(get("/api/items/9011"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.data.fotoIds", hasSize(6)))
				.andExpect(jsonPath("$.data.fotoIds[0]").value(811101))
				.andExpect(jsonPath("$.data.fotoUrls[0]").value("/api/items/fotos/811101"))
				.andExpect(jsonPath("$.data.imagenPrincipalUrl").value("/api/items/fotos/811101"));
	}

	private int count(String sql, Object... args) {
		return jdbc.queryForObject(sql, Integer.class, args);
	}

	private int photoBase(int productId) {
		return 811000 + ((productId - 8010) * 100);
	}
}
