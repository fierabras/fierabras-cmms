package com.grash.integration;

import com.grash.model.*;
import com.grash.model.enums.*;
import com.grash.repository.*;
import com.grash.service.WorkOrderService;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.transaction.annotation.Transactional;

import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * INC-01 step 0: characterization of the work order export before the fix. It runs the paging loop of the CSV
 * exporter (pages of 100 over {@code findByCompanyForExport}, no ORDER BY, fetch join on the assignees) and records
 * which work orders it returns.
 */
@Transactional
class Inc01ExportCharacterizationIntegrationTest extends AbstractIntegrationTest {

    private static final int WORK_ORDERS = 260;

    @Autowired
    private WorkOrderRepository workOrderRepository;
    @Autowired
    private UserRepository userRepository;
    @Autowired
    private CompanyRepository companyRepository;
    @Autowired
    private RoleRepository roleRepository;
    @Autowired
    private CompanySettingsRepository companySettingsRepository;
    @Autowired
    private SubscriptionRepository subscriptionRepository;
    @Autowired
    private SubscriptionPlanRepository subscriptionPlanRepository;
    @Autowired
    private WorkOrderService workOrderService;
    @Autowired
    private EntityManager em;

    @Test
    void currentExportPaging_withMultipleAssignees() {
        SubscriptionPlan plan = subscriptionPlanRepository.save(SubscriptionPlan.builder().name("Step0 Plan")
                .monthlyCostPerUser(0).yearlyCostPerUser(0).features(new HashSet<>()).build());
        Subscription subscription = subscriptionRepository.save(Subscription.builder().usersCount(10)
                .subscriptionPlan(plan).build());
        CompanySettings settings = companySettingsRepository.save(new CompanySettings());
        Company company = new Company("Step0Company", 10, subscription);
        company.setCompanySettings(settings);
        company = companyRepository.save(company);
        settings.setCompany(company);
        companySettingsRepository.save(settings);
        Role role = roleRepository.save(Role.builder().name("Step0").roleType(RoleType.ROLE_CLIENT)
                .code(RoleCode.ADMIN).companySettings(settings).build());

        List<User> users = new ArrayList<>();
        for (int i = 0; i < 4; i++) {
            User user = new User();
            user.setFirstName("U" + i);
            user.setLastName("Step0");
            user.setEmail("step0-" + i + "@test.com");
            user.setUsername("step0-" + i);
            user.setPassword("encoded");
            user.setRole(role);
            user.setCompany(company);
            user.setEnabled(true);
            user.setUserSettings(new UserSettings());
            users.add(userRepository.save(user));
        }

        Set<Long> createdIds = new HashSet<>();
        for (int i = 0; i < WORK_ORDERS; i++) {
            WorkOrder workOrder = new WorkOrder();
            workOrder.setTitle("Step0 WO " + i);
            workOrder.setStatus(Status.OPEN);
            workOrder.setPriority(Priority.NONE);
            workOrder.setCompany(company);
            // every third work order has 3 assignees, the next one 2, the other none
            int assignees = i % 3 == 0 ? 3 : i % 3 == 1 ? 2 : 0;
            workOrder.setAssignedTo(new ArrayList<>(users.subList(0, assignees)));
            createdIds.add(workOrderRepository.save(workOrder).getId());
        }
        em.flush();
        em.clear();

        List<Long> exported = new ArrayList<>();
        int pages = 0;
        long reportedTotal;
        Page<WorkOrder> result;
        do {
            result = workOrderService.findByCompanyForExport(company.getId(), PageRequest.of(pages, 100));
            result.getContent().forEach(workOrder -> exported.add(workOrder.getId()));
            reportedTotal = result.getTotalElements();
            em.clear();
            pages++;
        } while (result.hasNext());

        Set<Long> distinct = new LinkedHashSet<>(exported);
        Set<Long> missing = new HashSet<>(createdIds);
        missing.removeAll(distinct);
        System.out.printf("INC-01 step 0: created=%d pages=%d reportedTotal=%d exportedRows=%d distinct=%d " +
                        "duplicates=%d missing=%d%n", createdIds.size(), pages, reportedTotal, exported.size(),
                distinct.size(), exported.size() - distinct.size(), missing.size());

        // Observed 19/11/2026 (Hibernate 6.4): HHH90003004, the collection fetch makes Hibernate page in memory, so
        // every page reloads the whole set and the loop returns it complete. C2 does not reproduce; INC-01 comes from
        // C1 (no filters, no order).
        assertEquals(WORK_ORDERS, createdIds.size());
        assertEquals(WORK_ORDERS, exported.size());
        assertEquals(WORK_ORDERS, distinct.size());
        assertTrue(missing.isEmpty());
    }
}
