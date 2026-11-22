package com.grash.integration.support;

import com.grash.model.File;
import com.grash.service.StorageService;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * In-memory file storage double: records every call, named after the {@link StorageService} method, and keeps the
 * uploaded objects by path. When {@link StorageService} gains a method (for example, a delete), implement it here
 * with {@link #record(String, Object...)} so that tests can assert on it.
 */
public class StorageDouble extends CallRecorder implements StorageService {

    private static final String SIGNED_URL_PREFIX = "https://storage.test/";

    private final Map<String, byte[]> objects = new ConcurrentHashMap<>();

    @Override
    public String upload(MultipartFile file, String folder) {
        record("upload", file.getOriginalFilename(), folder);
        try {
            return store(file.getBytes(), file.getOriginalFilename(), folder);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    @Override
    public String upload(byte[] data, String fileName, String folder) {
        record("upload", fileName, folder);
        return store(data, fileName, folder);
    }

    @Override
    public byte[] download(String filePath) {
        record("download", filePath);
        byte[] data = objects.get(filePath);
        if (data == null) {
            throw new IllegalStateException("StorageDouble has no object at " + filePath);
        }
        return data;
    }

    @Override
    public byte[] download(File file) {
        return download(file.getPath());
    }

    @Override
    public String generateSignedUrl(File file, long expirationMinutes) {
        return generateSignedUrl(file.getPath(), expirationMinutes);
    }

    @Override
    public String generateSignedUrl(String filePath, long expirationMinutes) {
        record("generateSignedUrl", filePath, expirationMinutes);
        return SIGNED_URL_PREFIX + filePath;
    }

    @Override
    public void delete(String filePath) {
        record("delete", filePath);
        objects.remove(filePath);
    }

    public boolean contains(String filePath) {
        return objects.containsKey(filePath);
    }

    private String store(byte[] data, String fileName, String folder) {
        String path = folder + "/" + UUID.randomUUID() + "-" + fileName;
        objects.put(path, data);
        return path;
    }
}
