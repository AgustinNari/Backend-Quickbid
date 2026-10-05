package com.example.quickbid.quickbid.security;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

import com.example.quickbid.quickbid.exception.BusinessException;

@Service
public class AuthRateLimitService {

	private final Map<String, ArrayDeque<Instant>> attempts = new ConcurrentHashMap<>();

	public void check(String scope, String subject, int maxAttempts, Duration window) {
		String key = scope + ":" + subject.toLowerCase();
		Instant threshold = Instant.now().minus(window);
		ArrayDeque<Instant> timestamps = attempts.computeIfAbsent(key, ignored -> new ArrayDeque<>());
		synchronized (timestamps) {
			while (!timestamps.isEmpty() && timestamps.peekFirst().isBefore(threshold)) {
				timestamps.removeFirst();
			}
			if (timestamps.size() >= maxAttempts) {
				throw new BusinessException(HttpStatus.TOO_MANY_REQUESTS,
						"Demasiados intentos. Intente nuevamente más tarde", "RATE_LIMITED");
			}
			timestamps.addLast(Instant.now());
		}
	}

	public void clear() {
		attempts.clear();
	}
}
