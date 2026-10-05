package com.example.quickbid.quickbid.service;

import java.time.OffsetDateTime;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class NotificationCleanupService {
	private final JdbcTemplate jdbc;

	public NotificationCleanupService(JdbcTemplate jdbc) {
		this.jdbc = jdbc;
	}

	@Transactional
	public int cleanupOld() {
		return jdbc.update("""
				DELETE FROM app_notificaciones
				WHERE (leida = true AND COALESCE(read_at, created_at) < ?)
				   OR (leida = false AND created_at < ?)
				""", OffsetDateTime.now().minusDays(30), OffsetDateTime.now().minusDays(90));
	}
}
