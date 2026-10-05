package com.example.quickbid.quickbid;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.Test;

import com.example.quickbid.quickbid.service.BusinessExpirationScheduler;
import com.example.quickbid.quickbid.service.ConsignmentService;
import com.example.quickbid.quickbid.service.MedioPagoService;
import com.example.quickbid.quickbid.service.NotificationCleanupService;
import com.example.quickbid.quickbid.service.PurchaseService;

class BusinessExpirationSchedulerTests {
	@Test void procesaServiciosExistentesYConsolidaResultado() {
		MedioPagoService paymentMethods = mock(MedioPagoService.class);
		PurchaseService purchases = mock(PurchaseService.class);
		ConsignmentService consignments = mock(ConsignmentService.class);
		NotificationCleanupService notifications = mock(NotificationCleanupService.class);
		when(paymentMethods.expireVerified()).thenReturn(1);
		when(purchases.expireDueFines()).thenReturn(2);
		when(purchases.abandonDueExtraPayments()).thenReturn(3);
		when(consignments.expireDueReturns(null)).thenReturn(4);
		when(notifications.cleanupOld()).thenReturn(5);

		var result = new BusinessExpirationScheduler(paymentMethods, purchases, consignments, notifications)
				.processDueExpirations();

		assertEquals(15, result.total());
		verify(paymentMethods).expireVerified();
		verify(purchases).expireDueFines();
		verify(purchases).abandonDueExtraPayments();
		verify(consignments).expireDueReturns(null);
		verify(notifications).cleanupOld();
	}
}
