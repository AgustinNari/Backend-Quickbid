package com.example.quickbid.quickbid.service;

import java.security.SecureRandom;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

@Service
public class PaymentFailureSimulationService {
	private final SecureRandom random = new SecureRandom();
	private final boolean enabled;
	private final int probabilityPercent;

	public PaymentFailureSimulationService(
			@Value("${app.payment.adjudication-external-failure-enabled:true}") boolean enabled,
			@Value("${app.payment.adjudication-external-failure-probability-percent:1}") int probabilityPercent) {
		this.enabled = enabled;
		this.probabilityPercent = Math.max(0, Math.min(100, probabilityPercent));
	}

	public boolean shouldFailAdjudicationPayment() {
		if (!enabled || probabilityPercent <= 0) return false;
		if (probabilityPercent >= 100) return true;
		return random.nextInt(100) < probabilityPercent;
	}

	public int probabilityPercent() {
		return probabilityPercent;
	}
}
