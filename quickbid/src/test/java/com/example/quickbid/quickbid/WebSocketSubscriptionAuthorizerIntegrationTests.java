package com.example.quickbid.quickbid;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.messaging.MessagingException;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.jdbc.Sql;
import org.springframework.test.web.servlet.MockMvc;

import com.example.quickbid.quickbid.websocket.WebSocketSubscriptionAuthorizer;

@SpringBootTest(properties = "app.mail.enabled=false")
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Sql(scripts = "/auth-test-data.sql", executionPhase = Sql.ExecutionPhase.BEFORE_TEST_METHOD)
class WebSocketSubscriptionAuthorizerIntegrationTests {
	@Autowired WebSocketSubscriptionAuthorizer subscriptions;
	@Autowired JdbcTemplate jdbc;
	@Autowired MockMvc mvc;
	@Autowired @Qualifier("webSocketHeartbeatTaskScheduler") TaskScheduler webSocketHeartbeatScheduler;
	@Autowired @Qualifier("applicationTaskScheduler") TaskScheduler applicationScheduler;

	@Test void heartbeatWebSocketYTimersDeAplicacionUsanPoolsSeparados() {
		assertNotSame(webSocketHeartbeatScheduler, applicationScheduler);
	}

	@Test void handshakeSinJwtHttpLlegaAlTransporteWebSocket() throws Exception {
		mvc.perform(get("/ws")).andExpect(status().isBadRequest());
	}

	@Test void paginaLocalDePruebaWebSocketCargaSinJwt() throws Exception {
		mvc.perform(get("/ws-test.html")).andExpect(status().isOk());
	}

	@Test void usuarioActivoPuedeSuscribirseATopicsLiveYColasPrivadasPropias() {
		assertDoesNotThrow(() -> subscriptions.authorize(3001L, "/topic/subastas/6001/estado"));
		assertDoesNotThrow(() -> subscriptions.authorize(3001L, "/topic/subastas/6001/items/9001/pujas"));
		assertDoesNotThrow(() -> subscriptions.authorize(3001L, "/user/queue/notificaciones"));
		assertDoesNotThrow(() -> subscriptions.authorize(3001L, "/user/queue/pujas"));
	}

	@Test void suscripcionLiveNoExigeInscripcionPrevia() {
		jdbc.update("DELETE FROM app_inscripciones_subasta WHERE cuenta_id=3001 AND subasta_id=6001");

		assertDoesNotThrow(() -> subscriptions.authorize(3001L, "/topic/subastas/6001/estado"));
	}

	@Test void multaCategoriaInsuficienteYMedioPendienteNoBloqueanVisualizacionLive() {
		assertDoesNotThrow(() -> subscriptions.authorize(3002L, "/topic/subastas/6001/estado"));
		jdbc.update("UPDATE app_cuentas SET categoria_calculada='comun' WHERE id=3001");
		jdbc.update("UPDATE app_medios_pago SET estado='pendiente_verificacion',verificado_hasta=NULL WHERE id=5001");

		assertDoesNotThrow(() -> subscriptions.authorize(3001L, "/topic/subastas/6001/items/9001/pujas"));
	}

	@Test void cuentaBloqueadaODeshabilitadaNoPuedeSuscribirse() {
		jdbc.update("UPDATE app_cuentas SET estado='bloqueada_permanente' WHERE id=3001");
		assertThrows(MessagingException.class,
				() -> subscriptions.authorize(3001L, "/topic/subastas/6001/estado"));
		jdbc.update("UPDATE app_cuentas SET estado='deshabilitada_admin' WHERE id=3001");
		assertThrows(MessagingException.class,
				() -> subscriptions.authorize(3001L, "/topic/subastas/6001/estado"));
	}

	@Test void subastaItemYColaPrivadaAjenaSeRechazan() {
		assertThrows(MessagingException.class,
				() -> subscriptions.authorize(3001L, "/topic/subastas/9999/estado"));
		assertThrows(MessagingException.class,
				() -> subscriptions.authorize(3001L, "/topic/subastas/6001/items/9003/pujas"));
		assertThrows(MessagingException.class,
				() -> subscriptions.authorize(3001L, "/user/3002/queue/notificaciones"));
	}
}
