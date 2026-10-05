package com.example.quickbid.quickbid.repository.app;

import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;

import com.example.quickbid.quickbid.entity.app.PasswordResetToken;

public interface PasswordResetTokenRepository extends JpaRepository<PasswordResetToken, Long> {
	Optional<PasswordResetToken> findByTokenHash(String hash);
}
