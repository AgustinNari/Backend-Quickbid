package com.example.quickbid.quickbid.dto.admin;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.util.List;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

public final class AdminDtos {
	private AdminDtos() {
	}

	public record Status(String estado, String detalle) {
	}

	public record DemoResetStatus(String estado, String detalle, Integer subastaId, LocalDateTime programadaPara,
			Integer itemInicial, List<Integer> itemsDemo, Boolean autoOpenEnabled,
			Boolean firstLotWaitsForFirstBid) {
	}

	public record Registration(Long id, String email, String nombre, String apellido, String estado,
			String motivoRechazo, Integer personaId, Integer clienteId) {
	}

	public record Account(Long id, String email, String estado, Integer puntos, String categoria) {
	}

	public record PaymentMethod(Long id, Long cuentaId, String tipo, String moneda, String estado, String aliasVisible,
			String motivoRechazo, OffsetDateTime verificadoHasta) {
	}

	public record Auction(Integer id, String titulo, LocalDate fecha, LocalTime hora, String ubicacion,
			String categoria, String moneda, String estado) {
	}

	public record Consignment(Long id, Long cuentaId, String titulo, String estado, Integer productoId,
			Integer itemCatalogoId, Integer subastaId) {
	}

	public record ApproveRegistration(@NotBlank String documento, String categoriaInicial) {
	}

	public record Reason(@NotBlank String motivo) {
	}

	public record PaymentVerification(@NotNull BigDecimal limiteAprobado) {
	}

	public record Points(@NotNull Integer delta) {
	}

	public record Category(@NotBlank String categoria) {
	}

	public record AuctionCreate(@NotNull LocalDate fecha, @NotNull LocalTime hora, @NotBlank String titulo,
			String descripcion, @NotBlank String ubicacion, @NotBlank String categoria, @NotBlank String moneda,
			String segmento, Boolean permiteInscripcionOnline, Integer montoMinimo, Integer montoMaximo) {
	}

	public record AuctionUpdate(@NotNull LocalDate fecha, @NotNull LocalTime hora, @NotBlank String titulo,
			String descripcion, @NotBlank String ubicacion, @NotBlank String categoria, String segmento,
			Boolean permiteInscripcionOnline, Integer montoMinimo, Integer montoMaximo) {
	}

	public record ActiveItem(@NotNull Integer itemCatalogoId) {
	}

	public record CatalogItem(@NotNull Integer catalogoId, @NotNull Integer productoId,
			@NotNull BigDecimal precioBase, @NotNull BigDecimal comision) {
	}

	public record PurchaseSimulation(@NotNull Long cuentaId, @NotNull Long medioPagoId, String tipo) {
	}

	public record ReviewDecision(@NotNull Boolean aprobada, String motivo) {
	}

	public record OwnerVerification(@NotNull Boolean financiera, @NotNull Boolean judicial, @NotNull Integer riesgo) {
	}

	public record Agreement(@NotNull BigDecimal valorBase, @NotBlank String moneda,
			BigDecimal comisionCompradorPct, BigDecimal comisionVendedorPct, @NotBlank String condiciones) {
	}

	public record AssignAuction(@NotNull Integer subastaId, @NotNull Integer catalogoId, String polizaCombinada) {
	}

	public record Liquidate(@NotNull Long medioPagoId) {
	}
}
