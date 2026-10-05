package com.example.quickbid.quickbid.repository.app;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;

import com.example.quickbid.quickbid.entity.app.CategoriaPuntos;

public interface CategoriaPuntosRepository extends JpaRepository<CategoriaPuntos, String> {
	List<CategoriaPuntos> findAllByOrderByPuntosMinimosAsc();
}
