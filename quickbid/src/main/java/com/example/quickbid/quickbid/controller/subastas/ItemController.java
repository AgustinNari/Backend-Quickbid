package com.example.quickbid.quickbid.controller.subastas;

import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.InvalidMediaTypeException;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.example.quickbid.quickbid.dto.response.ApiResponse;
import com.example.quickbid.quickbid.service.LegacyPhotoService;
import com.example.quickbid.quickbid.service.SubastaService;

@RestController
@RequestMapping("/api/items")
public class ItemController {
	private final SubastaService subastas;
	private final LegacyPhotoService photos;

	public ItemController(SubastaService subastas, LegacyPhotoService photos) {
		this.subastas = subastas;
		this.photos = photos;
	}

	@GetMapping("/{id}")
	public ApiResponse<?> item(@PathVariable Integer id, Authentication authentication) {
		return ApiResponse.success(subastas.item(id, authentication != null), "Detalle de item");
	}

	@GetMapping("/fotos/{fotoId}")
	public ResponseEntity<byte[]> photo(@PathVariable Integer fotoId) {
		var file = photos.catalogPhoto(fotoId);
		MediaType contentType;
		try {
			contentType = MediaType.parseMediaType(file.contentType());
		} catch (InvalidMediaTypeException exception) {
			contentType = MediaType.APPLICATION_OCTET_STREAM;
		}
		return ResponseEntity.ok()
				.contentType(contentType)
				.header(HttpHeaders.CONTENT_DISPOSITION,
						ContentDisposition.inline().filename(file.filename()).build().toString())
				.contentLength(file.content().length)
				.body(file.content());
	}
}
