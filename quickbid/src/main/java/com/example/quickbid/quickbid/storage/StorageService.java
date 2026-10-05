package com.example.quickbid.quickbid.storage;

import java.io.InputStream;

public interface StorageService {

	StoredFile store(String originalFilename, String contentType, InputStream content);

	InputStream load(String storagePath);

	boolean exists(String storagePath);
}
