package com.grash.service;

import com.grash.dto.imports.ImportResponse;
import com.grash.dto.imports.WorkOrderImportDTO;
import com.grash.exception.CustomException;
import com.grash.model.*;
import com.grash.model.enums.PermissionEntity;
import com.grash.model.enums.PlanFeatures;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.messaging.simp.SimpMessageSendingOperations;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.startsWith;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class AsyncImportServiceTest {

    @Mock
    private ImportService importService;
    @Mock
    private CompanyService companyService;
    @Mock
    private IntercomService intercomService;
    @Mock
    private SimpMessageSendingOperations messagingTemplate;

    @InjectMocks
    private AsyncImportService asyncImportService;

    private User user;
    private Company company;
    private final List<WorkOrderImportDTO> rows = List.of(WorkOrderImportDTO.builder().title("WO").build());

    @BeforeEach
    void setUp() {
        SubscriptionPlan plan = new SubscriptionPlan();
        plan.setFeatures(new HashSet<>(Set.of(PlanFeatures.IMPORT_CSV)));
        Subscription subscription = new Subscription();
        subscription.setSubscriptionPlan(plan);
        company = new Company();
        company.setId(1L);
        company.setSubscription(subscription);
        Role role = Role.builder()
                .createPermissions(new HashSet<>(Set.of(PermissionEntity.WORK_ORDERS)))
                .build();
        user = new User();
        user.setId(1L);
        user.setRole(role);
        user.setCompany(company);
    }

    @Test
    void importWorkOrders_usesTheImportValidatedForTheRequester() {
        // INC-02 (T-11): a user import must go through the checks of the closing rule, with the importer.
        when(importService.importWorkOrders(rows, company, user)).thenReturn(ImportResponse.builder().build());

        asyncImportService.importWorkOrders(user, rows, "uuid");

        verify(importService).importWorkOrders(rows, company, user);
        verify(importService, never()).importWorkOrders(anyList(), any(Company.class));
    }

    @Test
    void importWorkOrders_rejectedRows_reportErrorAndImportNothing() {
        when(importService.importWorkOrders(rows, company, user))
                .thenThrow(new CustomException("You are not allowed to import completed work orders",
                        HttpStatus.FORBIDDEN));

        asyncImportService.importWorkOrders(user, rows, "uuid");

        verify(messagingTemplate).convertAndSend(eq("/imports/uuid"), startsWith("error: "));
    }
}
