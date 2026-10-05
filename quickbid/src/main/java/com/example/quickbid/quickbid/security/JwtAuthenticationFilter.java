package com.example.quickbid.quickbid.security;

import java.io.IOException;

import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import com.example.quickbid.quickbid.repository.app.CuentaAppRepository;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

@Component
public class JwtAuthenticationFilter extends OncePerRequestFilter {

	private final TokenService tokens;
	private final CuentaAppRepository cuentas;
	private final SecurityErrorWriter errors;

	public JwtAuthenticationFilter(TokenService t, CuentaAppRepository c, SecurityErrorWriter e) {
		tokens = t;
		cuentas = c;
		errors = e;
	}

	protected void doFilterInternal(
			HttpServletRequest req,
			HttpServletResponse res,
			FilterChain chain) throws ServletException, IOException {
		String h = req.getHeader("Authorization");
		if (h != null && h.startsWith("Bearer ")) {
			try {
				var c = cuentas.findById(tokens.accountId(h.substring(7))).orElseThrow();
				if (
						c.getEstado().equals("bloqueada_permanente") ||
						c.getEstado().equals("deshabilitada_admin")) {
					errors.write(res, HttpServletResponse.SC_FORBIDDEN, "Cuenta bloqueada", "ACCOUNT_BLOCKED");
					return;
				}
				if (c.getEstado().equals("activa") || c.getEstado().equals("restriccion_multa")) {
					SecurityContextHolder.getContext()
							.setAuthentication(new UsernamePasswordAuthenticationToken(
									c.getId(),
									null,
									java.util.List.of()));
				}
			} catch (Exception ignored) {
				errors.write(res, HttpServletResponse.SC_UNAUTHORIZED, "Autenticación requerida", "UNAUTHORIZED");
				return;
			}
		}
		chain.doFilter(req, res);
	}
}
