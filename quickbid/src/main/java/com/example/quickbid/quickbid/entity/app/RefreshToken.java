package com.example.quickbid.quickbid.entity.app;

import java.time.OffsetDateTime;

import jakarta.persistence.*;

@Entity
@Table(name = "app_refresh_tokens")
public class RefreshToken {
	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	@Column(name = "cuenta_id", nullable = false)
	private Long cuentaId;

	@Column(name = "token_hash", nullable = false, unique = true)
	private String tokenHash;

	@Column(name = "expires_at", nullable = false)
	private OffsetDateTime expiresAt;

	@Column(name = "revoked_at")
	private OffsetDateTime revokedAt;

	@Column(name = "created_at", nullable = false)
	private OffsetDateTime createdAt;

	protected RefreshToken() {
	}

	public RefreshToken(Long cuenta, String hash, int days) {
		cuentaId = cuenta;
		tokenHash = hash;
		createdAt = OffsetDateTime.now();
		expiresAt = createdAt.plusDays(days);
	}

	public Long getCuentaId() {
		return cuentaId;
	}

	public boolean valid() {
		return revokedAt == null && expiresAt.isAfter(OffsetDateTime.now());
	}

	public void revoke() {
		revokedAt = OffsetDateTime.now();
	}
}
