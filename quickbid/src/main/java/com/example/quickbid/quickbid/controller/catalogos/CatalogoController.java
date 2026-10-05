package com.example.quickbid.quickbid.controller.catalogos;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.example.quickbid.quickbid.dto.response.ApiResponse;
import com.example.quickbid.quickbid.dto.response.CatalogoDtos.Page;
import com.example.quickbid.quickbid.dto.response.CatalogoDtos.Pais;
import com.example.quickbid.quickbid.service.CatalogoService;

@RestController
@RequestMapping("/api/catalogos")
public class CatalogoController {
	private final CatalogoService catalogos;

	public CatalogoController(CatalogoService catalogos) {
		this.catalogos = catalogos;
	}

	@GetMapping("/paises")
	public ApiResponse<Page<Pais>> paises(@RequestParam(required = false) String q,
			@RequestParam(required = false) String buscar, @RequestParam(defaultValue = "0") int page,
			@RequestParam(defaultValue = "50") int size) {
		return ApiResponse.success(catalogos.paises(q, buscar, page, size), "Países");
	}

	@GetMapping("/paises/{id}")
	public ApiResponse<Pais> pais(@PathVariable Integer id) {
		return ApiResponse.success(catalogos.pais(id), "País");
	}
}
