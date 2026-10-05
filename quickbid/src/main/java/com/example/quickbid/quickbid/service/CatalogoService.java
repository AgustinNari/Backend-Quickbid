package com.example.quickbid.quickbid.service;

import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.example.quickbid.quickbid.dto.response.CatalogoDtos.Page;
import com.example.quickbid.quickbid.dto.response.CatalogoDtos.Pais;
import com.example.quickbid.quickbid.exception.BusinessException;
import com.example.quickbid.quickbid.repository.legacy.PaisRepository;

@Service
public class CatalogoService {
	private final PaisRepository paises;

	public CatalogoService(PaisRepository paises) {
		this.paises = paises;
	}

	@Transactional(readOnly = true)
	public Page<Pais> paises(String q, String buscar, int page, int size) {
		checkPage(page, size);
		String texto = firstSearch(q, buscar);
		var pageable = PageRequest.of(page, size, Sort.by(Sort.Order.asc("nombre").ignoreCase()));
		var result = texto == null ? paises.findAll(pageable) : paises.buscar(texto, pageable);
		return new Page<>(result.getContent().stream().map(this::response).toList(), page, size,
				result.getTotalElements(), result.getTotalPages());
	}

	@Transactional(readOnly = true)
	public Pais pais(Integer id) {
		return paises.findById(id).map(this::response)
				.orElseThrow(() -> new BusinessException(HttpStatus.NOT_FOUND, "País inexistente", "RESOURCE_NOT_FOUND"));
	}

	private Pais response(com.example.quickbid.quickbid.entity.legacy.Pais pais) {
		return new Pais(pais.getNumero(), pais.getNombre(), pais.getNombreCorto(), pais.getNacionalidad());
	}

	private String firstSearch(String q, String buscar) {
		if (q != null && !q.isBlank()) return q.trim();
		if (buscar != null && !buscar.isBlank()) return buscar.trim();
		return null;
	}

	private void checkPage(int page, int size) {
		if (page < 0 || size < 1 || size > 100) {
			throw new BusinessException(HttpStatus.BAD_REQUEST, "Paginación inválida", "INVALID_PAGE");
		}
	}
}
