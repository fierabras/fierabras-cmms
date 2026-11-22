package com.grash.service;

import com.google.cloud.storage.BlobId;
import com.google.cloud.storage.Storage;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * P-17 (E-6): fixes only how {@link GCPService#delete} calls Google Cloud Storage. The real service is not
 * reachable from the tests (no credentials nor emulator).
 */
class GCPServiceTest {

    private final Storage storage = mock(Storage.class);
    private final GCPService service = new GCPService();
    private Object configuredBefore;

    @BeforeEach
    void setUp() {
        ReflectionTestUtils.setField(service, "storage", storage);
        ReflectionTestUtils.setField(service, "gcpBucketName", "p17-bucket");
        configuredBefore = ReflectionTestUtils.getField(GCPService.class, "configured");
        ReflectionTestUtils.setField(GCPService.class, "configured", true);
    }

    @AfterEach
    void tearDown() {
        ReflectionTestUtils.setField(GCPService.class, "configured", configuredBefore);
    }

    @Test
    void delete_deletesTheBlobOfThePathInTheBucket() {
        when(storage.delete(any(BlobId.class))).thenReturn(true);

        service.delete("company 1/p17 file.txt");

        verify(storage).delete(BlobId.of("p17-bucket", "company 1/p17 file.txt"));
    }

    @Test
    void delete_ofAnObjectThatDoesNotExist_isNotAFailure() {
        when(storage.delete(any(BlobId.class))).thenReturn(false);

        assertDoesNotThrow(() -> service.delete("company 1/missing.txt"));
    }
}
