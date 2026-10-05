package com.example.quickbid.quickbid.storage;

public record FileDownload(String filename, String contentType, byte[] content) {
}
