package com.grash.service;

import com.grash.dto.imports.AssetImportDTO;
import com.grash.dto.imports.ImportResponse;
import com.grash.dto.imports.LocationImportDTO;
import com.grash.dto.imports.MeterImportDTO;
import com.grash.dto.imports.PartImportDTO;
import com.grash.dto.imports.PreventiveMaintenanceImportDTO;
import com.grash.dto.imports.WorkOrderImportDTO;
import com.grash.exception.CustomException;
import com.grash.model.*;
import com.grash.model.enums.PermissionEntity;
import com.grash.model.enums.PlanFeatures;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.messaging.simp.SimpMessageSendingOperations;

import java.util.Arrays;
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

    // P-19b (H2-04): a row with an id updates an existing record, so it takes edit-other on the entity.

    @ParameterizedTest
    @EnumSource(EntityImport.class)
    void rowWithId_withoutEditOther_rejectsTheWholeFileAndImportsNothing(EntityImport entityImport) {
        user.setRole(role(entityImport.entity, false));

        entityImport.run(asyncImportService, user, null, 7L);

        verify(messagingTemplate).convertAndSend("/imports/uuid", "error: Access Denied");
        verifyNoInteractions(importService);
    }

    @ParameterizedTest
    @EnumSource(EntityImport.class)
    void rowWithId_withEditOther_isImported(EntityImport entityImport) {
        user.setRole(role(entityImport.entity, true));
        ImportResponse response = ImportResponse.builder().updated(1).build();
        entityImport.stub(importService, response);

        entityImport.run(asyncImportService, user, 7L);

        verify(messagingTemplate).convertAndSend("/imports/uuid", (Object) response);
    }

    @ParameterizedTest
    @EnumSource(EntityImport.class)
    void rowsWithoutId_withoutEditOther_areImported(EntityImport entityImport) {
        user.setRole(role(entityImport.entity, false));
        ImportResponse response = ImportResponse.builder().build();
        entityImport.stub(importService, response);

        entityImport.run(asyncImportService, user, null, null);

        verify(messagingTemplate).convertAndSend("/imports/uuid", (Object) response);
    }

    private static Role role(PermissionEntity entity, boolean editOther) {
        return Role.builder()
                .createPermissions(new HashSet<>(Set.of(entity)))
                .editOtherPermissions(editOther ? new HashSet<>(Set.of(entity)) : new HashSet<>())
                .build();
    }

    enum EntityImport {
        ASSETS(PermissionEntity.ASSETS) {
            void run(AsyncImportService service, User user, Long... ids) {
                service.importAssets(user, Arrays.stream(ids)
                        .map(id -> AssetImportDTO.builder().id(id).name("Asset").build()).toList(), "uuid");
            }

            void stub(ImportService importService, ImportResponse response) {
                when(importService.importAssets(anyList(), any(Company.class))).thenReturn(response);
            }
        },
        LOCATIONS(PermissionEntity.LOCATIONS) {
            void run(AsyncImportService service, User user, Long... ids) {
                service.importLocations(user, Arrays.stream(ids)
                        .map(id -> LocationImportDTO.builder().id(id).name("Location").build()).toList(), "uuid");
            }

            void stub(ImportService importService, ImportResponse response) {
                when(importService.importLocations(anyList(), any(Company.class))).thenReturn(response);
            }
        },
        METERS(PermissionEntity.METERS) {
            void run(AsyncImportService service, User user, Long... ids) {
                service.importMeters(user, Arrays.stream(ids)
                        .map(id -> MeterImportDTO.builder().id(id).name("Meter").build()).toList(), "uuid");
            }

            void stub(ImportService importService, ImportResponse response) {
                when(importService.importMeters(anyList(), any(Company.class))).thenReturn(response);
            }
        },
        PARTS(PermissionEntity.PARTS_AND_MULTIPARTS) {
            void run(AsyncImportService service, User user, Long... ids) {
                service.importParts(user, Arrays.stream(ids)
                        .map(id -> PartImportDTO.builder().id(id).name("Part").build()).toList(), "uuid");
            }

            void stub(ImportService importService, ImportResponse response) {
                when(importService.importParts(anyList(), any(Company.class), any(User.class))).thenReturn(response);
            }
        },
        PREVENTIVE_MAINTENANCES(PermissionEntity.PREVENTIVE_MAINTENANCES) {
            void run(AsyncImportService service, User user, Long... ids) {
                service.importPreventiveMaintenances(user, Arrays.stream(ids)
                        .<PreventiveMaintenanceImportDTO>map(id -> PreventiveMaintenanceImportDTO.builder().id(id).name("PM").title("PM").build())
                        .toList(), "uuid");
            }

            void stub(ImportService importService, ImportResponse response) {
                when(importService.importPreventiveMaintenances(anyList(), any(Company.class))).thenReturn(response);
            }
        };

        private final PermissionEntity entity;

        EntityImport(PermissionEntity entity) {
            this.entity = entity;
        }

        abstract void run(AsyncImportService service, User user, Long... ids);

        abstract void stub(ImportService importService, ImportResponse response);
    }
}
