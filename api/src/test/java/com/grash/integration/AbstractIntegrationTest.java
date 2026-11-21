package com.grash.integration;

import com.grash.factory.MailServiceFactory;
import com.grash.factory.StorageServiceFactory;
import com.grash.integration.support.MailDouble;
import com.grash.integration.support.StorageDouble;
import com.grash.integration.support.TestOrganizations;
import com.grash.integration.support.TestRecords;
import com.grash.integration.support.WebhookDouble;
import com.grash.service.*;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Import;
import org.springframework.security.core.context.SecurityContextHolder;

import static org.mockito.AdditionalAnswers.delegatesTo;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Base of the integration tests. Mail, file storage and webhooks never leave the test: each test gets new doubles
 * ({@link #mailDouble}, {@link #storageDouble}, {@link #webhookDouble}) that record the calls and can simulate a
 * failure. See {@code dev-docs/Run tests.md}.
 */
@SpringBootTest
@Import({TestOrganizations.class, TestRecords.class})
public abstract class AbstractIntegrationTest extends AbstractTestContainer {

    @MockBean
    protected MailServiceFactory mailServiceFactory;
    @MockBean
    protected StorageServiceFactory storageServiceFactory;

    /**
     * Mockito view of {@link #mailDouble}, for {@code verify(...)}.
     */
    protected MailService mailService;
    protected MailDouble mailDouble;
    protected StorageDouble storageDouble;
    protected WebhookDouble webhookDouble;

    @BeforeEach
    void setUpMocks(ApplicationContext context) {
        mailDouble = new MailDouble();
        mailService = mock(MailService.class, delegatesTo(mailDouble));
        when(mailServiceFactory.getMailService()).thenReturn(mailService);
        storageDouble = new StorageDouble();
        when(storageServiceFactory.getStorageService()).thenReturn(storageDouble);
        webhookDouble = new WebhookDouble();
        webhookDouble.bindTo(context.getBean(WebhookDispatchService.class));
    }

    @AfterEach
    void clearSecurityContext() {
        if (webhookDouble != null) {
            webhookDouble.unbind();
        }
        SecurityContextHolder.clearContext();
    }
}