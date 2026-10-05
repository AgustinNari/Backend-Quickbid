package com.example.quickbid.quickbid.dto.response;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.util.List;

public final class SubastaDtos {
	private SubastaDtos() {
	}

	public record Page<T>(List<T> content, int page, int size, long totalElements, int totalPages) {
	}

	public record PublicSummary(Integer id, String titulo, String descripcion, LocalDate fecha, LocalTime hora,
			String ubicacion, String categoria, String moneda, String segmento, String estadoOperativo,
			String imagenPrincipalUrl) {
	}

	public record AuthenticatedSummary(Integer id, String titulo, String descripcion, LocalDate fecha, LocalTime hora,
			String ubicacion, String categoria, String moneda, String segmento, String estadoOperativo,
			String imagenPrincipalUrl) {
	}

	public record Rematador(String nombre, String matricula, String region) {
	}

	public record PublicDetail(Integer id, String titulo, String descripcion, LocalDate fecha, LocalTime hora,
			String ubicacion, String categoria, String moneda, String segmento, String estadoOperativo,
			Rematador rematador, String imagenPrincipalUrl) {
	}

	public record AuthenticatedDetail(Integer id, String titulo, String descripcion, LocalDate fecha, LocalTime hora,
			String ubicacion, String categoria, String moneda, String segmento, String estadoOperativo,
			Boolean permiteInscripcionOnline, Boolean autenticado, Rematador rematador, String imagenPrincipalUrl) {
	}

	public record PublicCatalog(Integer subastaId, Integer catalogoId, String descripcion, List<PublicItem> items) {
	}

	public record AuthenticatedCatalog(Integer subastaId, Integer catalogoId, String descripcion,
			List<AuthenticatedItem> items) {
	}

	public record PublicItem(Integer id, Integer productoId, String descripcion, List<Integer> fotoIds,
			List<String> fotoUrls, String imagenPrincipalUrl, Integer ordenLote, String estadoLote, String estado,
			Boolean activo, Boolean subastado, String resultadoLote, Long compraId, Boolean compradorEmpresa,
			String duenioActual, String fechaObjeto, String historia, String historiaExtendida,
			String artistaDisenador, String segmentoConsignacion, String categoriaAsignada, Long consignacionId) {
	}

	public record AuthenticatedItem(Integer id, Integer productoId, String descripcion, List<Integer> fotoIds,
			List<String> fotoUrls, String imagenPrincipalUrl, BigDecimal precioBase, BigDecimal comision,
			Integer ordenLote, String estadoLote, String estado, Boolean activo, Boolean subastado,
			String resultadoLote, Long compraId, Boolean compradorEmpresa,
			String duenioActual, String fechaObjeto, String historia, String historiaExtendida,
			String artistaDisenador, String segmentoConsignacion, String categoriaAsignada, Long consignacionId) {
	}

	public record PaymentOption(Long id, String tipo, String moneda, String estado, Boolean principal,
			String aliasVisible, String ultimos4, Boolean verificacionVigente, Boolean requiereRevalidacion) {
	}

	public record Registration(Long id, Integer subastaId, Long medioPagoId, String estado, Boolean existente,
			Boolean requiereRevisionMedioPago, OffsetDateTime createdAt) {
	}

	public record Verification(
			Boolean puedeVerDetalleCompleto,
			Boolean puedeInscribirse,
			Boolean puedePujar,
			Boolean requiereLogin,
			Boolean requiereMedioPagoParaInscripcion,
			Boolean requiereMedioPagoVerificadoParaPujar,
			Boolean requiereRevalidacionMedioPagoParaPujar,
			Boolean categoriaInsuficienteParaInscripcion,
			Boolean categoriaInsuficienteParaPujar,
			Boolean monedaIncompatibleParaInscripcion,
			Boolean monedaIncompatibleParaPujar,
			Boolean cuentaRestringida,
			Boolean cuentaBloqueada,
			Boolean yaInscripto,
			Boolean inscripcionCerradaPorTiempo,
			Boolean subastaYaIniciada,
			Boolean subastaNoIniciadaParaPuja,
			Boolean sinLoteActivo,
			Boolean conectadoOParticipandoEnOtraSubasta,
			List<PaymentOption> mediosPagoCompatiblesParaInscripcion,
			List<PaymentOption> mediosPagoVerificadosVigentesCompatiblesParaPuja,
			List<PaymentOption> mediosPagoRevalidablesParaInscripcion) {
	}

	public record CurrentBid(Integer subastaId, Integer itemActivoId, BigDecimal mejorOfertaActual,
			String moneda, Long versionEstado, Boolean puedePujar, String motivo, BigDecimal precioBase,
			BigDecimal incrementoMinimo, OffsetDateTime serverNow, OffsetDateTime retencionHasta,
			Long segundosRestantes, Boolean miPujaGanadora, String estadoLote, Boolean adjudicado,
			String siguienteAccion, Boolean esperandoPrimeraPuja, Boolean timerActivo, String mensajeEstado,
			OffsetDateTime deadlineActual, String tipoTimer, OffsetDateTime proximoLoteAt,
			OffsetDateTime subastaFinalizaAt, Integer siguienteItemId, Integer siguienteLoteOrden) {
	}

	public record Bid(Long id, Integer subastaId, Integer itemCatalogoId, String estado, BigDecimal monto,
			String moneda, Long secuencia, Long versionEstado, BigDecimal mejorOfertaActual, Integer numeroPostor,
			Boolean idempotentReplay, OffsetDateTime retencionHasta) {
		public Bid(Long id, Integer subastaId, Integer itemCatalogoId, String estado, BigDecimal monto,
				String moneda, Long secuencia, Long versionEstado, BigDecimal mejorOfertaActual, Integer numeroPostor,
				Boolean idempotentReplay) {
			this(id, subastaId, itemCatalogoId, estado, monto, moneda, secuencia, versionEstado, mejorOfertaActual,
					numeroPostor, idempotentReplay, null);
		}
	}

	public record BidEvent(String tipo, Integer subastaId, Integer itemCatalogoId, Long pujaId, BigDecimal monto,
			String moneda, Long secuencia, Long versionEstado, Integer numeroPostor, String postorAlias,
			OffsetDateTime retencionHasta) {
		public BidEvent(String tipo, Integer subastaId, Integer itemCatalogoId, Long pujaId, BigDecimal monto,
				String moneda, Long secuencia, Long versionEstado, Integer numeroPostor, String postorAlias) {
			this(tipo, subastaId, itemCatalogoId, pujaId, monto, moneda, secuencia, versionEstado, numeroPostor,
					postorAlias, null);
		}
	}

	public record AuctionStateEvent(String tipo, Integer subastaId, Integer itemCatalogoActivoId,
			BigDecimal mejorOfertaActual, String moneda, Long versionEstado, OffsetDateTime retencionHasta) {
		public AuctionStateEvent(String tipo, Integer subastaId, Integer itemCatalogoActivoId,
				BigDecimal mejorOfertaActual, String moneda, Long versionEstado) {
			this(tipo, subastaId, itemCatalogoActivoId, mejorOfertaActual, moneda, versionEstado, null);
		}
	}

	public record AuctionLifecycleEvent(String tipo, Integer subastaId, Integer itemCatalogoId, Long versionEstado,
			OffsetDateTime loteFinalizaEstimadoAt) {
		public AuctionLifecycleEvent(String tipo, Integer subastaId, Integer itemCatalogoId, Long versionEstado) {
			this(tipo, subastaId, itemCatalogoId, versionEstado, null);
		}
	}

	public record RejectedBidEvent(String tipo, Integer subastaId, Integer itemCatalogoId, String code,
			String message) {
	}
}
