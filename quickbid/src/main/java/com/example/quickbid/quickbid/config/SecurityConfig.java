package com.example.quickbid.quickbid.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import com.example.quickbid.quickbid.security.AdminInternalAuthenticationFilter;
import com.example.quickbid.quickbid.security.JwtAuthenticationFilter;
import com.example.quickbid.quickbid.security.JsonAccessDeniedHandler;
import com.example.quickbid.quickbid.security.JsonAuthenticationEntryPoint;

@Configuration
public class SecurityConfig {

	@Bean
	SecurityFilterChain securityFilterChain(HttpSecurity http, JwtAuthenticationFilter jwt,
			AdminInternalAuthenticationFilter admin,
			JsonAuthenticationEntryPoint authenticationEntryPoint, JsonAccessDeniedHandler accessDeniedHandler)
			throws Exception {
		return http
				.csrf(csrf -> csrf.disable())
				.formLogin(form -> form.disable())
				.httpBasic(basic -> basic.disable())
				.sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
				.exceptionHandling(exceptions -> exceptions
						.authenticationEntryPoint(authenticationEntryPoint)
						.accessDeniedHandler(accessDeniedHandler))
				.authorizeHttpRequests(auth -> auth
						.requestMatchers(
								"/api/auth/registro/**",
								"/api/auth/login",
								"/api/auth/refresh",
								"/api/auth/recuperar-clave",
								"/api/auth/cambiar-clave",
								"/api/auth/logout",
								"/auth-links/**",
								"/actuator/health",
								"/ws",
								"/ws/**",
								"/ws-test.html",
								"/favicon.ico").permitAll()
						.requestMatchers("/api/catalogos/**").permitAll()
						.requestMatchers(
								HttpMethod.GET,
								"/api/subastas",
								"/api/subastas/*",
								"/api/subastas/*/catalogo",
								"/api/items/*",
								"/api/items/fotos/*").permitAll()
						.requestMatchers("/api/admin/**").hasRole("ADMIN")
						.anyRequest().authenticated())
				.addFilterBefore(admin, UsernamePasswordAuthenticationFilter.class)
				.addFilterAfter(jwt, AdminInternalAuthenticationFilter.class).build();
	}

	@Bean
	PasswordEncoder passwordEncoder() {
		return new BCryptPasswordEncoder();
	}
}
