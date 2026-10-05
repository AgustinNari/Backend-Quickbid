package com.example.quickbid.quickbid.entity.app;

import jakarta.persistence.*;

@Entity
@Table(name = "app_categoria_puntos")
public class CategoriaPuntos {
	@Id
	@Column(length = 10)
	private String categoria;

	@Column(name = "puntos_minimos", nullable = false)
	private Integer puntosMinimos;

	@Column(nullable = false)
	private Integer orden;

	protected CategoriaPuntos() {
	}

	public String getCategoria() {
		return categoria;
	}

	public Integer getPuntosMinimos() {
		return puntosMinimos;
	}

	public Integer getOrden() {
		return orden;
	}
}
