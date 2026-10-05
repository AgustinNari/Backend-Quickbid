package com.example.quickbid.quickbid.security;

import java.io.IOException;

import org.springframework.security.core.AuthenticationException;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.stereotype.Component;

import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

@Component
public class JsonAuthenticationEntryPoint implements AuthenticationEntryPoint {

	private final SecurityErrorWriter writer;

	public JsonAuthenticationEntryPoint(SecurityErrorWriter writer) {
		this.writer = writer;
	}

	@Override
	public void commence(HttpServletRequest request, HttpServletResponse response,
			AuthenticationException authException) throws IOException, ServletException {
		writer.write(response, HttpServletResponse.SC_UNAUTHORIZED, "Autenticación requerida", "UNAUTHORIZED");
	}
}
