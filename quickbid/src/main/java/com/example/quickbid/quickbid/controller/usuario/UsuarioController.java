package com.example.quickbid.quickbid.controller.usuario;

import java.util.List;

import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.example.quickbid.quickbid.dto.request.DireccionEnvioRequest;
import com.example.quickbid.quickbid.dto.response.ApiResponse;
import com.example.quickbid.quickbid.dto.response.UsuarioDtos.Address;
import com.example.quickbid.quickbid.dto.response.UsuarioDtos.HistoryItem;
import com.example.quickbid.quickbid.dto.response.UsuarioDtos.Notification;
import com.example.quickbid.quickbid.dto.response.UsuarioDtos.Page;
import com.example.quickbid.quickbid.dto.response.UsuarioDtos.Profile;
import com.example.quickbid.quickbid.dto.response.UsuarioDtos.Statistics;
import com.example.quickbid.quickbid.service.UsuarioService;

import jakarta.validation.Valid;

@RestController
@RequestMapping("/api/usuario")
public class UsuarioController {

	private final UsuarioService usuarios;

	public UsuarioController(UsuarioService u) {
		usuarios = u;
	}

	@GetMapping("/perfil")
	public ApiResponse<Profile> profile(Authentication a) {
		return ApiResponse.success(usuarios.profile(id(a)), "Perfil");
	}

	@GetMapping("/estadisticas")
	public ApiResponse<Statistics> statistics(
			Authentication a,
			@RequestParam(defaultValue = "total") String periodo) {
		return ApiResponse.success(usuarios.statistics(id(a), periodo), "Estadisticas");
	}

	@GetMapping("/historial")
	public ApiResponse<Page<HistoryItem>> history(
			Authentication a,
			@RequestParam(defaultValue = "0") int page,
			@RequestParam(defaultValue = "20") int size) {
		return ApiResponse.success(usuarios.history(id(a), page, size), "Historial");
	}

	@GetMapping("/notificaciones")
	public ApiResponse<Page<Notification>> notifications(
			Authentication a,
			@RequestParam(required = false) String tipo,
			@RequestParam(required = false) String categoria,
			@RequestParam(required = false) Boolean leida,
			@RequestParam(defaultValue = "0") int page,
			@RequestParam(defaultValue = "20") int size) {
		return ApiResponse.success(
				usuarios.notifications(id(a), tipo != null ? tipo : categoria, leida, page, size),
				"Notificaciones");
	}

	@PatchMapping("/notificaciones/all/leer")
	public ApiResponse<List<Notification>> readAll(Authentication a) {
		return ApiResponse.success(usuarios.readAllNotifications(id(a)), "Notificaciones leídas");
	}

	@PatchMapping("/notificaciones/{id}/leer")
	public ApiResponse<Notification> read(Authentication a, @PathVariable Long id) {
		return ApiResponse.success(usuarios.readNotification(id(a), id), "Notificación leída");
	}

	@GetMapping("/direccion-envio")
	public ApiResponse<Address> address(Authentication a) {
		return ApiResponse.success(usuarios.address(id(a)), "Dirección de envío");
	}

	@PutMapping("/direccion-envio")
	public ApiResponse<Address> updateAddress(Authentication a, @Valid @RequestBody DireccionEnvioRequest request) {
		return ApiResponse.success(usuarios.updateAddress(id(a), request), "Dirección de envío actualizada");
	}

	@GetMapping("/direcciones-envio")
	public ApiResponse<List<Address>> addresses(Authentication a) {
		return ApiResponse.success(usuarios.addresses(id(a)), "Direcciones de envío");
	}

	@PostMapping("/direcciones-envio")
	public ApiResponse<Address> createAddress(Authentication a, @Valid @RequestBody DireccionEnvioRequest request) {
		return ApiResponse.success(usuarios.createAddress(id(a), request), "Dirección de envío creada");
	}

	@DeleteMapping("/direcciones-envio/{addressId}")
	public ApiResponse<Void> deleteAddress(Authentication a, @PathVariable Long addressId) {
		usuarios.deleteAddress(id(a), addressId);
		return ApiResponse.success(null, "Dirección de envío eliminada");
	}

	@PatchMapping("/direcciones-envio/{addressId}/principal")
	public ApiResponse<Address> principalAddress(Authentication a, @PathVariable Long addressId) {
		return ApiResponse.success(
				usuarios.setPrincipalAddress(id(a), addressId),
				"Dirección principal actualizada");
	}

	private Long id(Authentication authentication) {
		return (Long) authentication.getPrincipal();
	}
}
