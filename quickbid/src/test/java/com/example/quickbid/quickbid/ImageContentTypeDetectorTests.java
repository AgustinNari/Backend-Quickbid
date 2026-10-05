package com.example.quickbid.quickbid;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import com.example.quickbid.quickbid.service.ImageContentTypeDetector;

class ImageContentTypeDetectorTests {
	@Test
	void detectsCommonImageTypesByMagicBytes() {
		assertEquals("image/png", ImageContentTypeDetector.detect("x.bin",
				new byte[] {(byte) 0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A}));
		assertEquals("image/jpeg", ImageContentTypeDetector.detect("x.bin",
				new byte[] {(byte) 0xFF, (byte) 0xD8, (byte) 0xFF, 0x11}));
		assertEquals("image/webp", ImageContentTypeDetector.detect("x.bin",
				new byte[] {'R', 'I', 'F', 'F', 1, 2, 3, 4, 'W', 'E', 'B', 'P'}));
	}

	@Test
	void fallsBackToSupportedExtensionAndOctetStream() {
		assertEquals("image/png", ImageContentTypeDetector.detect("01.PNG", new byte[] {1, 2, 3}));
		assertEquals("image/jpeg", ImageContentTypeDetector.detect("01.jpeg", new byte[] {1, 2, 3}));
		assertEquals("image/webp", ImageContentTypeDetector.detect("01.webp", new byte[] {1, 2, 3}));
		assertEquals("application/octet-stream", ImageContentTypeDetector.detect("01.txt", new byte[] {1, 2, 3}));
		assertTrue(ImageContentTypeDetector.isSupported("01.png", new byte[] {1, 2, 3}));
	}
}
