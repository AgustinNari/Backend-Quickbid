package com.example.quickbid.quickbid.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(name = "app.expirations.scheduler-enabled", havingValue = "true", matchIfMissing = true)
public class BusinessExpirationScheduler {
	private static final Logger LOGGER = LoggerFactory.getLogger(BusinessExpirationScheduler.class);

	private final MedioPagoService paymentMethods;
	private final PurchaseService purchases;
	private final ConsignmentService consignments;
	private final NotificationCleanupService notifications;

	public BusinessExpirationScheduler(MedioPagoService paymentMethods, PurchaseService purchases,
			ConsignmentService consignments, NotificationCleanupService notifications) {
		this.paymentMethods = paymentMethods;
		this.purchases = purchases;
		this.consignments = consignments;
		this.notifications = notifications;
	}

	@Scheduled(initialDelayString = "${app.expirations.scheduler-initial-delay-ms:60000}",
			fixedDelayString = "${app.expirations.scheduler-delay-ms:3600000}")
	public ExpirationResult processDueExpirations() {
		ExpirationResult result = new ExpirationResult(paymentMethods.expireVerified(), purchases.expireDueFines(),
				purchases.abandonDueExtraPayments(), consignments.expireDueReturns(null), notifications.cleanupOld());
		if (result.total() > 0) {
			LOGGER.info("Business expirations processed paymentMethods={} fines={} purchases={} returns={} notifications={}",
					result.paymentMethods(), result.fines(), result.purchases(), result.returns(), result.notifications());
		} else {
			LOGGER.debug("Business expirations processed with no due records");
		}
		return result;
	}

	public record ExpirationResult(int paymentMethods, int fines, int purchases, int returns, int notifications) {
		public int total() {
			return paymentMethods + fines + purchases + returns + notifications;
		}
	}
}
