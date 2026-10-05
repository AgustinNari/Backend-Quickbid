package com.example.quickbid.quickbid.controller.auth;

import java.util.Map;

import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import com.example.quickbid.quickbid.dto.response.ApiResponse;
import com.example.quickbid.quickbid.exception.BusinessException;
import com.example.quickbid.quickbid.service.AuthService;
import com.fasterxml.jackson.annotation.JsonProperty;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

@RestController
@RequestMapping("/api/auth")
public class AuthController {

	private final AuthService auth;

	public AuthController(AuthService a) {
		auth = a;
	}

	@PostMapping("/registro/etapa1")
	public ResponseEntity<ApiResponse<Map<String, Object>>> etapa1(@Valid @RequestBody Etapa1 r) {
		return ResponseEntity.status(201)
				.body(ApiResponse.success(
						auth.etapa1(r.email, r.nombre, r.apellido, r.domicilioLegal, r.idPaisOrigen),
						"Solicitud creada"));
	}

	@PostMapping(value = "/registro/etapa2", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
	public ApiResponse<Void> etapa2(
			@RequestParam String email,
			@RequestPart(required = false) MultipartFile fotoFrenteDni,
			@RequestPart(required = false) MultipartFile fotoDorsoDni,
			@RequestPart(required = false) MultipartFile fotoFrente,
			@RequestPart(required = false) MultipartFile fotoDorso) {
		auth.etapa2(email, file(fotoFrenteDni, fotoFrente), file(fotoDorsoDni, fotoDorso));
		return ApiResponse.success(null, "Documentación recibida");
	}

	@PostMapping("/registro/verificar-token")
	public ApiResponse<Void> verify(@Valid @RequestBody Token r) {
		auth.verify(r.token);
		return ApiResponse.success(null, "Token valido");
	}

	@PostMapping("/registro/etapa3")
	public ApiResponse<Map<String, Object>> etapa3(@RequestBody PasswordChange r) {
		return ApiResponse.success(
				auth.etapa3(r.setup(), r.nueva(), r.confirmacion()),
				"Cuenta creada");
	}

	@PostMapping("/registro/reenviar-link")
	public ApiResponse<Void> resend(@Valid @RequestBody EmailRequest r) {
		auth.resend(r.email);
		return ApiResponse.success(null, "Si corresponde, se envió un nuevo enlace");
	}

	@PostMapping("/login")
	public ApiResponse<Map<String, Object>> login(@Valid @RequestBody Login r) {
		return ApiResponse.success(auth.login(r.email, r.clave), "Login exitoso");
	}

	@PostMapping("/refresh")
	public ApiResponse<Map<String, Object>> refresh(@Valid @RequestBody Refresh r) {
		return ApiResponse.success(auth.refresh(r.refreshToken), "Token renovado");
	}

	@PostMapping("/logout")
	public ApiResponse<Void> logout(@Valid @RequestBody Refresh r) {
		auth.logout(r.refreshToken);
		return ApiResponse.success(null, "Sesion cerrada");
	}

	@PostMapping("/recuperar-clave")
	public ApiResponse<Void> recover(@Valid @RequestBody EmailRequest r) {
		auth.recover(r.email);
		return ApiResponse.success(null, "Si el email existe, se envió un enlace");
	}

	@PutMapping("/cambiar-clave")
	public ApiResponse<Void> change(Authentication a, @RequestBody PasswordChange r) {
		if (a == null || !(a.getPrincipal() instanceof Long)) {
			auth.changeWithToken(r.token(), r.nueva(), r.confirmacion());
		} else {
			auth.changeAuthenticated((Long) a.getPrincipal(), r.claveActual(), r.nueva(), r.confirmacion());
		}
		return ApiResponse.success(null, "Clave actualizada");
	}

	@GetMapping("/sesion")
	public ApiResponse<Map<String, Object>> session(Authentication a) {
		return ApiResponse.success(auth.session((Long) a.getPrincipal()), "Sesion actual");
	}

	public record Etapa1(
			@Email @NotBlank String email,
			@NotBlank String nombre,
			@NotBlank String apellido,
			@NotBlank String domicilioLegal,
			@NotNull Integer idPaisOrigen) {
	}

	public record EmailRequest(@Email @NotBlank String email) {
	}

	public record Login(@Email @NotBlank String email, @NotBlank String clave) {
	}

	public record Token(@NotBlank String token) {
	}

	public record PasswordChange(
			@JsonProperty("setup_token") String setupToken,
			String token,
			String clave,
			String claveActual,
			String claveNueva,
			String claveConfirmacion) {

		String setup() {
			return setupToken != null ? setupToken : token;
		}

		String nueva() {
			return claveNueva != null ? claveNueva : clave;
		}

		String confirmacion() {
			return claveConfirmacion != null ? claveConfirmacion : clave;
		}
	}

	public record Refresh(@JsonProperty("refreshToken") @NotBlank String refreshToken) {
	}

	private MultipartFile file(MultipartFile primary, MultipartFile secondary) {
		MultipartFile value = primary != null ? primary : secondary;
		if (value == null || value.isEmpty()) {
			throw new BusinessException(HttpStatus.BAD_REQUEST, "Falta archivo requerido", "MISSING_FILE");
		}
		return value;
	}
}
