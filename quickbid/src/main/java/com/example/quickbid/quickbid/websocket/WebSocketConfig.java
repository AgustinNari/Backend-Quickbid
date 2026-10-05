package com.example.quickbid.quickbid.websocket;

import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Bean;
import org.springframework.messaging.simp.config.MessageBrokerRegistry;
import org.springframework.messaging.simp.config.ChannelRegistration;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.SchedulingConfigurer;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;
import org.springframework.scheduling.config.ScheduledTaskRegistrar;
import org.springframework.web.socket.config.annotation.EnableWebSocketMessageBroker;
import org.springframework.web.socket.config.annotation.StompEndpointRegistry;
import org.springframework.web.socket.config.annotation.WebSocketTransportRegistration;
import org.springframework.web.socket.config.annotation.WebSocketMessageBrokerConfigurer;
import org.springframework.web.socket.WebSocketHandler;
import org.springframework.web.socket.WebSocketMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.WebSocketHandlerDecorator;

@Configuration
@EnableWebSocketMessageBroker
@EnableScheduling
public class WebSocketConfig implements WebSocketMessageBrokerConfigurer, SchedulingConfigurer {
	private final WebSocketAuthChannelInterceptor authentication;
	private final WebSocketOutboundChannelInterceptor outboundAuthorization;
	private final WebSocketConnectionRegistry connections;

	public WebSocketConfig(WebSocketAuthChannelInterceptor authentication,
			WebSocketOutboundChannelInterceptor outboundAuthorization, WebSocketConnectionRegistry connections) {
		this.authentication = authentication;
		this.outboundAuthorization = outboundAuthorization;
		this.connections = connections;
	}

	@Override
	public void configureMessageBroker(MessageBrokerRegistry registry) {
		registry.enableSimpleBroker("/topic", "/queue")
				.setHeartbeatValue(new long[] { 10000, 10000 })
				.setTaskScheduler(webSocketHeartbeatTaskScheduler());
		registry.setApplicationDestinationPrefixes("/app");
		registry.setUserDestinationPrefix("/user");
	}

	@Override
	public void registerStompEndpoints(StompEndpointRegistry registry) {
		registry.addEndpoint("/ws");
	}

	@Override
	public void configureClientInboundChannel(ChannelRegistration registration) {
		registration.interceptors(authentication);
	}

	@Override
	public void configureClientOutboundChannel(ChannelRegistration registration) {
		registration.interceptors(outboundAuthorization);
	}

	@Override
	public void configureWebSocketTransport(WebSocketTransportRegistration registration) {
		registration.addDecoratorFactory(this::presenceAware);
	}

	@Override
	public void configureTasks(ScheduledTaskRegistrar taskRegistrar) {
		taskRegistrar.setTaskScheduler(applicationTaskScheduler());
	}

	private WebSocketHandler presenceAware(WebSocketHandler delegate) {
		return new WebSocketHandlerDecorator(delegate) {
			@Override
			public void handleMessage(WebSocketSession session, WebSocketMessage<?> message) throws Exception {
				connections.touch(session.getId());
				super.handleMessage(session, message);
			}

			@Override
			public void afterConnectionClosed(WebSocketSession session,
					org.springframework.web.socket.CloseStatus closeStatus) throws Exception {
				connections.disconnected(session.getId());
				super.afterConnectionClosed(session, closeStatus);
			}
		};
	}

	@Bean(name = "webSocketHeartbeatTaskScheduler")
	public TaskScheduler webSocketHeartbeatTaskScheduler() {
		ThreadPoolTaskScheduler scheduler = new ThreadPoolTaskScheduler();
		scheduler.setPoolSize(1);
		scheduler.setThreadNamePrefix("quickbid-ws-heartbeat-");
		return scheduler;
	}

	@Bean(name = "applicationTaskScheduler")
	public TaskScheduler applicationTaskScheduler() {
		ThreadPoolTaskScheduler scheduler = new ThreadPoolTaskScheduler();
		scheduler.setPoolSize(2);
		scheduler.setThreadNamePrefix("quickbid-scheduled-");
		return scheduler;
	}
}
