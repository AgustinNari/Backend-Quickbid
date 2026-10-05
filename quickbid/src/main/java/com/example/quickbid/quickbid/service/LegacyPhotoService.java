package com.example.quickbid.quickbid.service;

import java.util.List;

import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.example.quickbid.quickbid.exception.BusinessException;
import com.example.quickbid.quickbid.storage.FileDownload;

@Service
public class LegacyPhotoService {
	private final JdbcTemplate jdbc;

	public LegacyPhotoService(JdbcTemplate jdbc) {
		this.jdbc = jdbc;
	}

	@Transactional(readOnly = true)
	public FileDownload catalogPhoto(Integer fotoId) {
		List<Photo> values = jdbc.query("""
				SELECT f.identificador,f.foto FROM fotos f
				WHERE f.identificador=?
				  AND EXISTS (
				      SELECT 1 FROM "itemsCatalogo" i WHERE i.producto=f.producto
				  )
				""", (rs, row) -> new Photo(rs.getInt("identificador"), rs.getBytes("foto")), fotoId);
		if (values.isEmpty() || values.get(0).bytes() == null || values.get(0).bytes().length == 0) {
			throw new BusinessException(HttpStatus.NOT_FOUND, "Foto inexistente", "RESOURCE_NOT_FOUND");
		}
		Photo photo = values.get(0);
		String contentType = ImageContentTypeDetector.detect("foto-" + photo.id(), photo.bytes());
		return new FileDownload("foto-" + photo.id(), contentType, photo.bytes());
	}

	private record Photo(Integer id, byte[] bytes) {
	}
}
