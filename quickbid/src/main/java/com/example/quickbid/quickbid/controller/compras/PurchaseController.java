package com.example.quickbid.quickbid.controller.compras;

import java.util.List;

import org.springframework.security.core.Authentication;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.InvalidMediaTypeException;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.example.quickbid.quickbid.dto.request.PurchaseDeliveryRequest;
import com.example.quickbid.quickbid.dto.request.PurchasePaymentRequest;
import com.example.quickbid.quickbid.dto.response.ApiResponse;
import com.example.quickbid.quickbid.dto.response.PurchaseDtos;
import com.example.quickbid.quickbid.dto.response.PurchaseDtos.Detail;
import com.example.quickbid.quickbid.dto.response.PurchaseDtos.DeliveryPreview;
import com.example.quickbid.quickbid.dto.response.PurchaseDtos.Document;
import com.example.quickbid.quickbid.dto.response.PurchaseDtos.Page;
import com.example.quickbid.quickbid.dto.response.PurchaseDtos.Payment;
import com.example.quickbid.quickbid.dto.response.PurchaseDtos.Summary;
import com.example.quickbid.quickbid.service.PurchaseService;

import jakarta.validation.Valid;

@RestController
@RequestMapping("/api/compras")
public class PurchaseController {
	private final PurchaseService purchases;

	public PurchaseController(PurchaseService purchases) {
		this.purchases = purchases;
	}

	@GetMapping
	public ApiResponse<Page<Summary>> list(Authentication authentication, @RequestParam(defaultValue = "0") int page,
			@RequestParam(defaultValue = "20") int size, @RequestParam(required = false) String estado) {
		return ApiResponse.success(purchases.list(accountId(authentication), estado, page, size), "Compras");
	}

	@GetMapping("/{id}")
	public ApiResponse<Detail> detail(Authentication authentication, @PathVariable Long id) {
		return ApiResponse.success(purchases.detail(accountId(authentication), id), "Detalle de compra");
	}

	@PutMapping("/{id}/entrega")
	public ApiResponse<PurchaseDtos.Delivery> delivery(Authentication authentication, @PathVariable Long id,
			@Valid @RequestBody PurchaseDeliveryRequest request) {
		return ApiResponse.success(purchases.configureDelivery(accountId(authentication), id, request),
				"Entrega configurada");
	}

	@PostMapping("/{id}/entrega/preview")
	public ApiResponse<DeliveryPreview> deliveryPreview(Authentication authentication, @PathVariable Long id,
			@Valid @RequestBody PurchaseDeliveryRequest request) {
		return ApiResponse.success(purchases.previewDelivery(accountId(authentication), id, request),
				"Cotización de entrega");
	}

	@PostMapping("/{id}/pagar")
	public ApiResponse<Payment> pay(Authentication authentication, @PathVariable Long id,
			@Valid @RequestBody PurchasePaymentRequest request) {
		return ApiResponse.success(purchases.payExtras(accountId(authentication), id, request), "Pago procesado");
	}

	@PostMapping("/{id}/pagar-con-multa")
	public ApiResponse<Payment> payFine(Authentication authentication, @PathVariable Long id,
			@Valid @RequestBody PurchasePaymentRequest request) {
		return ApiResponse.success(purchases.payFine(accountId(authentication), id, request), "Pago procesado");
	}

	@GetMapping("/{id}/documentos")
	public ApiResponse<List<Document>> documents(Authentication authentication, @PathVariable Long id) {
		return ApiResponse.success(purchases.documents(accountId(authentication), id), "Documentos de compra");
	}

	@GetMapping("/{id}/documentos/{documentoId}/descargar")
	public ResponseEntity<byte[]> downloadDocument(Authentication authentication, @PathVariable Long id,
			@PathVariable Long documentoId) {
		var file = purchases.downloadDocument(accountId(authentication), id, documentoId);
		MediaType contentType;
		try {
			contentType = MediaType.parseMediaType(file.contentType());
		} catch (InvalidMediaTypeException exception) {
			contentType = MediaType.APPLICATION_OCTET_STREAM;
		}
		return ResponseEntity.ok()
				.contentType(contentType)
				.header(HttpHeaders.CONTENT_DISPOSITION,
						ContentDisposition.attachment().filename(file.filename(), java.nio.charset.StandardCharsets.UTF_8).build().toString())
				.contentLength(file.content().length)
				.body(file.content());
	}

	private Long accountId(Authentication authentication) {
		return (Long) authentication.getPrincipal();
	}
}
