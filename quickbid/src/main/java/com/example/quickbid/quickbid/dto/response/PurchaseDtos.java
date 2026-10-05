package com.example.quickbid.quickbid.dto.response;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;

public final class PurchaseDtos {
	private PurchaseDtos() {
	}

	public record Summary(Long id, Integer subastaId, Integer itemCatalogoId, Integer productoId,
			BigDecimal montoAdjudicacion, String moneda, String estado, OffsetDateTime createdAt, Fine multa) {
	}

	public record Detail(Long id, Integer subastaId, Integer itemCatalogoId, Integer productoId, Long pujaId,
			BigDecimal montoAdjudicacion, String moneda, String estado, Long medioPagoId, Delivery entrega,
			Fine multa, BigDecimal comisionComprador, BigDecimal comisionVendedor, OffsetDateTime createdAt) {
	}

	public record Delivery(Long id, String tipo, Long direccionEnvioId, BigDecimal costoEnvio, String estado,
			Boolean perdioCoberturaSeguro, String direccionSnapshotJson, OffsetDateTime direccionSnapshotAt) {
	}

	public record DeliveryPreview(String tipo, Long direccionEnvioId, BigDecimal costoEnvio, String moneda,
			BigDecimal comisionComprador, BigDecimal totalEstimado) {
	}

	public record Fine(Long id, BigDecimal monto, String moneda, String estado, OffsetDateTime venceAt,
			OffsetDateTime paidAt) {
	}

	public record Payment(Long id, Long compraId, Long multaId, Long medioPagoId, BigDecimal monto, String moneda,
			String estado, String errorCodigo, String compraEstado, Boolean idempotentReplay) {
	}

	public record Document(Long id, String tipo, String estado, Long archivoId, String filename, String contentType,
			Long sizeBytes, OffsetDateTime createdAt, Boolean downloadAvailable, String downloadUrl) {
	}

	public record Page<T>(List<T> content, int page, int size, long totalElements, int totalPages) {
	}

	public record LotClosedEvent(String tipo, Integer subastaId, Integer itemCatalogoId, Long compraId,
			Long pujaGanadoraId, BigDecimal montoAdjudicacion, String moneda, Boolean compradorEmpresa,
			Long versionEstado, OffsetDateTime proximoLoteProgramadoAt, OffsetDateTime subastaFinalizaProgramadoAt) {
		public LotClosedEvent(String tipo, Integer subastaId, Integer itemCatalogoId, Long compraId,
				Long pujaGanadoraId, BigDecimal montoAdjudicacion, String moneda, Boolean compradorEmpresa,
				Long versionEstado) {
			this(tipo, subastaId, itemCatalogoId, compraId, pujaGanadoraId, montoAdjudicacion, moneda, compradorEmpresa,
					versionEstado, null, null);
		}
	}
}
