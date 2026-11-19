package com.grash.service;

import com.grash.advancedsearch.SearchCriteria;
import com.grash.factory.StorageServiceFactory;
import com.grash.model.Company;
import com.grash.model.User;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.messaging.simp.SimpMessageSendingOperations;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * I-4 / I-5: the export publishes the signed URL of a complete file, or an error and no URL.
 */
@ExtendWith(MockitoExtension.class)
class AsyncExportServiceTest {

    @Mock
    private XlsxExportService xlsxExportService;
    @Mock
    private StorageServiceFactory storageServiceFactory;
    @Mock
    private StorageService storageService;
    @Mock
    private SimpMessageSendingOperations messagingTemplate;
    @InjectMocks
    private AsyncExportService asyncExportService;

    private User user;
    private final SearchCriteria criteria = new SearchCriteria();

    @BeforeEach
    void setUp() {
        Company company = new Company();
        company.setId(5L);
        user = new User();
        user.setCompany(company);
    }

    @Test
    void workOrders_publishesTheSignedUrlOfTheXlsxFile() throws IOException {
        byte[] bytes = {1, 2, 3};
        when(xlsxExportService.exportWorkOrders(user, criteria)).thenReturn(bytes);
        when(storageServiceFactory.getStorageService()).thenReturn(storageService);
        when(storageService.uploadAndSign(any(), eq("5/exports/u1/work-orders"))).thenReturn("https://signed");

        asyncExportService.exportWorkOrders(user, criteria, "u1");

        ArgumentCaptor<MultipartFile> file = ArgumentCaptor.forClass(MultipartFile.class);
        verify(storageService).uploadAndSign(file.capture(), eq("5/exports/u1/work-orders"));
        assertEquals("Work Orders.xlsx", file.getValue().getOriginalFilename());
        assertEquals("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
                file.getValue().getContentType());
        assertArrayEquals(bytes, file.getValue().getBytes());
        verify(messagingTemplate).convertAndSend("/exports/u1", "https://signed");
    }

    // T-10
    @Test
    void workOrders_writeFailure_publishesAnErrorAndNoUrl() throws IOException {
        when(xlsxExportService.exportWorkOrders(user, criteria)).thenThrow(new IOException("disk full"));

        asyncExportService.exportWorkOrders(user, criteria, "u1");

        verify(messagingTemplate).convertAndSend(eq("/exports/u1"), startsWith("error:"));
        verifyNoMoreInteractions(messagingTemplate);
        verifyNoInteractions(storageServiceFactory);
    }

    // C-3: the detail of the failure only goes to the log
    @Test
    void failure_publishesAGenericError() throws IOException {
        when(xlsxExportService.exportWorkOrders(user, criteria))
                .thenThrow(new IllegalArgumentException("No property 'password' found for type 'User'"));

        asyncExportService.exportWorkOrders(user, criteria, "u1");

        verify(messagingTemplate).convertAndSend("/exports/u1", "error: export failed");
    }

    // T-10 (I-4)
    @Test
    void workOrders_incompleteFile_publishesAnErrorAndNoUrl() throws IOException {
        when(xlsxExportService.exportWorkOrders(user, criteria))
                .thenThrow(new IllegalStateException("Export aborted"));

        asyncExportService.exportWorkOrders(user, criteria, "u1");

        verify(messagingTemplate).convertAndSend(eq("/exports/u1"), startsWith("error:"));
        verifyNoMoreInteractions(messagingTemplate);
        verifyNoInteractions(storageServiceFactory);
    }

    // RF-10
    @Test
    void requests_publishesTheSignedUrlOfTheXlsxFile() throws IOException {
        byte[] bytes = {4, 5};
        when(xlsxExportService.exportRequests(user, criteria)).thenReturn(bytes);
        when(storageServiceFactory.getStorageService()).thenReturn(storageService);
        when(storageService.uploadAndSign(any(), eq("5/exports/u2/requests"))).thenReturn("https://signed-r");

        asyncExportService.exportRequests(user, criteria, "u2");

        ArgumentCaptor<MultipartFile> file = ArgumentCaptor.forClass(MultipartFile.class);
        verify(storageService).uploadAndSign(file.capture(), eq("5/exports/u2/requests"));
        assertEquals("Requests.xlsx", file.getValue().getOriginalFilename());
        assertArrayEquals(bytes, file.getValue().getBytes());
        verify(messagingTemplate).convertAndSend("/exports/u2", "https://signed-r");
    }

    // T-10
    @Test
    void requests_writeFailure_publishesAnErrorAndNoUrl() throws IOException {
        when(xlsxExportService.exportRequests(user, criteria)).thenThrow(new IOException("disk full"));

        asyncExportService.exportRequests(user, criteria, "u2");

        verify(messagingTemplate).convertAndSend(eq("/exports/u2"), startsWith("error:"));
        verifyNoMoreInteractions(messagingTemplate);
        verifyNoInteractions(storageServiceFactory);
    }
}
