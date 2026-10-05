package com.example.quickbid.quickbid.service;

import java.util.Locale;
import java.util.Set;

public final class ImageContentTypeDetector {
	private static final Set<String> SUPPORTED = Set.of("image/png", "image/jpeg", "image/webp");

	private ImageContentTypeDetector() {
	}

	public static String detect(String filename, byte[] bytes) {
		String byMagic = detectByMagic(bytes);
		if (byMagic != null) return byMagic;
		String byExtension = detectByExtension(filename);
		return byExtension == null ? "application/octet-stream" : byExtension;
	}

	public static String detectByMagic(byte[] bytes) {
		if (bytes == null || bytes.length < 4) return null;
		if (bytes.length >= 8
				&& unsigned(bytes[0]) == 0x89 && bytes[1] == 0x50 && bytes[2] == 0x4E && bytes[3] == 0x47
				&& bytes[4] == 0x0D && bytes[5] == 0x0A && bytes[6] == 0x1A && bytes[7] == 0x0A) {
			return "image/png";
		}
		if (bytes.length >= 3 && unsigned(bytes[0]) == 0xFF && unsigned(bytes[1]) == 0xD8
				&& unsigned(bytes[2]) == 0xFF) {
			return "image/jpeg";
		}
		if (bytes.length >= 12
				&& bytes[0] == 'R' && bytes[1] == 'I' && bytes[2] == 'F' && bytes[3] == 'F'
				&& bytes[8] == 'W' && bytes[9] == 'E' && bytes[10] == 'B' && bytes[11] == 'P') {
			return "image/webp";
		}
		return null;
	}

	public static boolean isSupported(String filename, byte[] bytes) {
		return SUPPORTED.contains(detect(filename, bytes));
	}

	private static String detectByExtension(String filename) {
		if (filename == null) return null;
		String lower = filename.toLowerCase(Locale.ROOT);
		if (lower.endsWith(".png")) return "image/png";
		if (lower.endsWith(".jpg") || lower.endsWith(".jpeg")) return "image/jpeg";
		if (lower.endsWith(".webp")) return "image/webp";
		return null;
	}

	private static int unsigned(byte value) {
		return value & 0xFF;
	}
}
