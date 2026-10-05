package com.example.quickbid.quickbid.storage;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.Map;
import java.util.UUID;

import org.springframework.stereotype.Service;

@Service
public class LocalStorageService implements StorageService {
	private static final Map<String, String> EXTENSIONS = Map.of(
			"image/png", ".png",
			"image/webp", ".webp",
			"image/jpeg", ".jpg",
			"application/pdf", ".pdf");
	private final Path root;
	private final Path realRoot;

	public LocalStorageService(StorageProperties properties) throws IOException {
		root = properties.storagePath().toAbsolutePath().normalize();
		Files.createDirectories(root);
		realRoot = root.toRealPath();
	}

	public StoredFile store(String originalFilename, String contentType, InputStream content) {
		try {
			byte[] bytes = content.readAllBytes();
			String name = UUID.randomUUID() + EXTENSIONS.getOrDefault(contentType, ".bin");
			Path target = root.resolve(name).normalize();
			if (!target.startsWith(root)) throw new IllegalArgumentException("Invalid path");
			Files.write(target, bytes, StandardOpenOption.CREATE_NEW);
			String checksum = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
			return new StoredFile(name, checksum, bytes.length);
		} catch (Exception exception) {
			throw new IllegalStateException("No se pudo almacenar el archivo", exception);
		}
	}

	public InputStream load(String storagePath) {
		try {
			Path path = resolvedExistingPath(storagePath);
			return Files.newInputStream(path);
		} catch (IOException | IllegalArgumentException exception) {
			throw new IllegalStateException(exception);
		}
	}

	@Override
	public boolean exists(String storagePath) {
		try {
			return Files.isRegularFile(resolvedExistingPath(storagePath));
		} catch (IOException | IllegalArgumentException exception) {
			return false;
		}
	}

	private Path resolvedExistingPath(String storagePath) throws IOException {
		if (storagePath == null || storagePath.isBlank()) throw new IllegalArgumentException("Invalid path");
		Path candidate = root.resolve(storagePath).normalize();
		if (!candidate.startsWith(root)) throw new IllegalArgumentException("Invalid path");
		Path realPath = candidate.toRealPath();
		if (!realPath.startsWith(realRoot)) throw new IllegalArgumentException("Invalid path");
		return realPath;
	}
}
