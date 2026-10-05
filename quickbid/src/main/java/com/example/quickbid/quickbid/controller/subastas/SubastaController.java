package com.example.quickbid.quickbid.controller.subastas;

import java.time.LocalDate;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.example.quickbid.quickbid.dto.request.InscripcionSubastaRequest;
import com.example.quickbid.quickbid.dto.request.BidRequest;
import com.example.quickbid.quickbid.dto.response.ApiResponse;
import com.example.quickbid.quickbid.dto.response.SubastaDtos.Bid;
import com.example.quickbid.quickbid.dto.response.SubastaDtos.CurrentBid;
import com.example.quickbid.quickbid.dto.response.SubastaDtos.Page;
import com.example.quickbid.quickbid.dto.response.SubastaDtos.Registration;
import com.example.quickbid.quickbid.dto.response.SubastaDtos.Verification;
import com.example.quickbid.quickbid.service.SubastaService;
import com.example.quickbid.quickbid.service.BidService;

import jakarta.validation.Valid;

@RestController
@RequestMapping("/api/subastas")
public class SubastaController {
	private final SubastaService subastas;
	private final BidService bids;

	public SubastaController(SubastaService subastas, BidService bids) {
		this.subastas = subastas;
		this.bids = bids;
	}

	@GetMapping
	public ApiResponse<Page<?>> list(@RequestParam(required = false) String estado,
			@RequestParam(required = false) String categoria, @RequestParam(required = false) String moneda,
			@RequestParam(required = false) LocalDate fechaDesde, @RequestParam(required = false) LocalDate fechaHasta,
			@RequestParam(required = false) String q, @RequestParam(defaultValue = "0") int page,
			@RequestParam(defaultValue = "20") int size, Authentication authentication) {
		return ApiResponse.success(subastas.list(estado, categoria, moneda, fechaDesde, fechaHasta, q, page, size,
				authentication != null), "Subastas");
	}

	@GetMapping("/{id}")
	public ApiResponse<?> detail(@PathVariable Integer id, Authentication authentication) {
		return ApiResponse.success(subastas.detail(id, authentication != null), "Detalle de subasta");
	}

	@GetMapping("/{id}/catalogo")
	public ApiResponse<?> catalog(@PathVariable Integer id, Authentication authentication) {
		return ApiResponse.success(subastas.catalog(id, authentication != null), "Catálogo de subasta");
	}

	@PostMapping("/{id}/inscribirse")
	public ResponseEntity<ApiResponse<Registration>> enroll(@PathVariable Integer id, Authentication authentication,
			@RequestBody(required = false) InscripcionSubastaRequest request) {
		Registration result = subastas.enroll(accountId(authentication), id, request == null ? null : request.medioPagoId());
		HttpStatus status = result.existente() ? HttpStatus.OK : HttpStatus.CREATED;
		return ResponseEntity.status(status).body(ApiResponse.success(result, result.existente() ? "Inscripción existente" : "Inscripción registrada"));
	}

	@PostMapping("/{id}/verificacion")
	public ApiResponse<Verification> verification(@PathVariable Integer id, Authentication authentication) {
		return ApiResponse.success(subastas.verification(accountId(authentication), id), "Verificación de acceso");
	}

	@GetMapping("/{id}/puja-actual")
	public ApiResponse<CurrentBid> currentBid(@PathVariable Integer id, Authentication authentication) {
		return ApiResponse.success(subastas.currentBid(accountId(authentication), id), "Puja actual");
	}

	@PostMapping("/{id}/pujar")
	public ResponseEntity<ApiResponse<Bid>> bid(@PathVariable Integer id, Authentication authentication,
			@Valid @RequestBody BidRequest request) {
		return ResponseEntity.status(HttpStatus.CREATED)
				.body(ApiResponse.success(bids.bid(accountId(authentication), id, request), "Puja aceptada"));
	}

	private Long accountId(Authentication authentication) {
		return (Long) authentication.getPrincipal();
	}
}
