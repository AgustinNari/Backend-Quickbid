package com.example.quickbid.quickbid.websocket;

import java.util.List;
import java.util.Set;

import org.springframework.messaging.Message;
import org.springframework.messaging.MessageChannel;
import org.springframework.messaging.MessagingException;
import org.springframework.messaging.simp.stomp.StompCommand;
import org.springframework.messaging.simp.stomp.StompHeaderAccessor;
import org.springframework.messaging.support.ChannelInterceptor;
import org.springframework.messaging.support.MessageHeaderAccessor;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.stereotype.Component;

import com.example.quickbid.quickbid.repository.app.CuentaAppRepository;
import com.example.quickbid.quickbid.security.TokenService;

@Component
public class WebSocketAuthChannelInterceptor implements ChannelInterceptor {
	private final TokenService tokens;
	private final CuentaAppRepository accounts;
	private final WebSocketSubscriptionAuthorizer subscriptions;
	private final WebSocketConnectionRegistry connections;

	public WebSocketAuthChannelInterceptor(TokenService tokens, CuentaAppRepository accounts,
			WebSocketSubscriptionAuthorizer subscriptions, WebSocketConnectionRegistry connections) {
		this.tokens = tokens;
		this.accounts = accounts;
		this.subscriptions = subscriptions;
		this.connections = connections;
	}

	@Override
	public Message<?> preSend(Message<?> message, MessageChannel channel) {
		StompHeaderAccessor accessor = MessageHeaderAccessor.getAccessor(message, StompHeaderAccessor.class);
		if (accessor == null) return message;
		if (accessor.getCommand() == StompCommand.CONNECT) authenticate(accessor);
		if (accessor.getCommand() == StompCommand.SUBSCRIBE) authorizeSubscription(accessor);
		if (accessor.getCommand() == StompCommand.DISCONNECT) connections.disconnected(accessor.getSessionId());
		connections.touch(accessor.getSessionId());
		return message;
	}

	private void authenticate(StompHeaderAccessor accessor) {
		String authorization = accessor.getFirstNativeHeader("Authorization");
		if (authorization == null || !authorization.startsWith("Bearer ")) {
			throw new MessagingException("Bearer token requerido");
		}
		try {
			Long accountId = tokens.accountId(authorization.substring(7));
			var account = accounts.findById(accountId).orElseThrow();
			if (!Set.of("activa", "restriccion_multa").contains(account.getEstado())) {
				throw new MessagingException("Cuenta bloqueada");
			}
			accessor.setUser(new UsernamePasswordAuthenticationToken(accountId, null, List.of()));
			connections.connected(accessor.getSessionId(), accountId);
		} catch (MessagingException exception) {
			throw exception;
		} catch (Exception exception) {
			throw new MessagingException("Bearer token invalido");
		}
	}

	private void authorizeSubscription(StompHeaderAccessor accessor) {
		Long accountId;
		try {
			if (accessor.getUser() != null) {
				accountId = Long.valueOf(accessor.getUser().getName());
			} else {
				accountId = connections.accountId(accessor.getSessionId())
						.orElseThrow(() -> new MessagingException("Autenticación requerida para suscribirse"));
				accessor.setUser(new UsernamePasswordAuthenticationToken(accountId, null, List.of()));
			}
		} catch (NumberFormatException exception) {
			throw new MessagingException("Principal STOMP invalido");
		}
		subscriptions.authorize(accountId, accessor.getDestination());
	}
}
