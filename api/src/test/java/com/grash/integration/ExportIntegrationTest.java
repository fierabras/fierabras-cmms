package com.grash.integration;

import com.grash.advancedsearch.FilterField;
import com.grash.advancedsearch.SearchCriteria;
import com.grash.model.*;
import com.grash.model.enums.*;
import com.grash.repository.*;
import com.grash.service.WorkOrderService;
import com.grash.service.XlsxExportService;
import com.grash.utils.Helper;
import com.grash.utils.XlsxTestUtils;
import jakarta.persistence.EntityManager;
import jakarta.persistence.criteria.JoinType;
import org.dhatim.fastexcel.reader.Cell;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.MessageSource;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Sort;
import org.springframework.transaction.annotation.Transactional;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.util.function.IntPredicate;
import java.util.function.Supplier;
import java.util.stream.IntStream;

import static com.grash.utils.Helper.setCurrentUser;
import static org.junit.jupiter.api.Assertions.*;

/**
 * INC-01, INC-03 and RF-10: the XLSX export holds exactly the records of the list (same user, same criteria, all its
 * pages), in the order of the list, with the columns of the contract.
 */
@Transactional
class ExportIntegrationTest extends AbstractIntegrationTest {

    private static final int WORK_ORDERS = 250;
    private static final int LIST_PAGE_SIZE = 37;

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
    private GeneralPreferencesRepository generalPreferencesRepository;
    @Autowired
    private CurrencyRepository currencyRepository;
    @Autowired
    private CustomerRepository customerRepository;
    @Autowired
    private TeamRepository teamRepository;
    @Autowired
    private LocationRepository locationRepository;
    @Autowired
    private AssetRepository assetRepository;
    @Autowired
    private WorkOrderCategoryRepository workOrderCategoryRepository;
    @Autowired
    private WorkOrderService workOrderService;
    @Autowired
    private XlsxExportService xlsxExportService;
    @Autowired
    private MessageSource messageSource;
    @Autowired
    private EntityManager em;

    // ---------------------------------------------------------------- fixture helpers

    private Company newCompany(String name) {
        SubscriptionPlan plan = subscriptionPlanRepository.save(SubscriptionPlan.builder().name(name + " Plan")
                .monthlyCostPerUser(0).yearlyCostPerUser(0).features(new HashSet<>()).build());
        Subscription subscription = subscriptionRepository.save(Subscription.builder().usersCount(10)
                .subscriptionPlan(plan).build());
        CompanySettings settings = companySettingsRepository.save(new CompanySettings());
        Company company = new Company(name, 10, subscription);
        company.setCompanySettings(settings);
        company = companyRepository.save(company);
        settings.setCompany(company);
        companySettingsRepository.save(settings);
        GeneralPreferences preferences = settings.getGeneralPreferences();
        preferences.setCurrency(currencyRepository.findFirstBy().get());
        preferences.setDateFormat(DateFormat.DDMMYY);
        preferences.setTimeZone("America/Mexico_City");
        generalPreferencesRepository.save(preferences);
        return company;
    }

    private Role newRole(Company company, Set<PermissionEntity> view, Set<PermissionEntity> viewOther) {
        return roleRepository.save(Role.builder().name("Export role").roleType(RoleType.ROLE_CLIENT)
                .code(RoleCode.USER_CREATED).companySettings(company.getCompanySettings())
                .createPermissions(new HashSet<>()).viewPermissions(new HashSet<>(view))
                .viewOtherPermissions(new HashSet<>(viewOther)).editOtherPermissions(new HashSet<>())
                .deleteOtherPermissions(new HashSet<>()).build());
    }

    private User newUser(Company company, Role role, String firstName, String email) {
        User user = new User();
        user.setFirstName(firstName);
        user.setLastName("Export");
        user.setEmail(email);
        user.setUsername(email);
        user.setPassword("encoded");
        user.setRole(role);
        user.setCompany(company);
        user.setEnabled(true);
        user.setSuperAccountRelations(new ArrayList<>());
        user.setUserSettings(new UserSettings());
        return userRepository.save(user);
    }

    private WorkOrder newWorkOrder(Company company, String title) {
        WorkOrder workOrder = new WorkOrder();
        workOrder.setTitle(title);
        workOrder.setStatus(Status.OPEN);
        workOrder.setPriority(Priority.NONE);
        workOrder.setCompany(company);
        workOrder.setAssignedTo(new ArrayList<>());
        workOrder.setCustomers(new ArrayList<>());
        workOrder.setFiles(new ArrayList<>());
        workOrder.setCustomFieldValues(new ArrayList<>());
        return workOrder;
    }

    private User reload(User user) {
        User reloaded = userRepository.findById(user.getId()).get();
        setCurrentUser(reloaded);
        return reloaded;
    }

    private String message(String key, Company company) {
        return messageSource.getMessage(key, null, Helper.getLocale(company));
    }

    private static FilterField filter(String field, String operation, Object value, List<Object> values) {
        return FilterField.builder().field(field).operation(operation).value(value).values(values).build();
    }

    // ---------------------------------------------------------------- work orders

    private List<Long> listedWorkOrderIds(User user, Supplier<SearchCriteria> criteria) {
        return listedWorkOrderIds(user, criteria, LIST_PAGE_SIZE);
    }

    private List<Long> listedWorkOrderIds(User user, Supplier<SearchCriteria> criteria, int pageSize) {
        User current = reload(user);
        List<Long> ids = new ArrayList<>();
        int page = 0;
        Page<WorkOrder> result;
        do {
            SearchCriteria pageCriteria = criteria.get();
            pageCriteria.setPageNum(page++);
            pageCriteria.setPageSize(pageSize);
            result = workOrderService.findBySearchCriteria(workOrderService.getSearchCriteria(current,
                    pageCriteria));
            result.forEach(workOrder -> ids.add(workOrder.getId()));
        } while (result.hasNext());
        return new ArrayList<>(new LinkedHashSet<>(ids));
    }

    private byte[] exportWorkOrders(User user, SearchCriteria criteria) {
        User current = reload(user);
        try {
            return xlsxExportService.exportWorkOrders(current, workOrderService.getSearchCriteria(current, criteria));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private List<Long> exportedIds(byte[] bytes) {
        List<List<String>> rows = XlsxTestUtils.readText(bytes, 1);
        return rows.subList(1, rows.size()).stream().map(row -> Long.valueOf(row.get(0))).toList();
    }

    @Nested
    class WorkOrderListParity {

        private Company company;
        private User admin;
        private User technician;
        private List<User> assignees;
        private final List<Long> idsByIndex = new ArrayList<>();

        @BeforeEach
        void setUp() {
            company = newCompany("ExportCo");
            Role adminRole = newRole(company, Set.of(PermissionEntity.WORK_ORDERS),
                    Set.of(PermissionEntity.WORK_ORDERS));
            Role technicianRole = newRole(company, Set.of(PermissionEntity.WORK_ORDERS), Set.of());
            admin = newUser(company, adminRole, "Zoe", "admin@export.test");
            technician = newUser(company, technicianRole, "Tito", "tech@export.test");
            assignees = List.of(newUser(company, technicianRole, "Ana", "ana@export.test"),
                    newUser(company, technicianRole, "Beto", "beto@export.test"),
                    newUser(company, technicianRole, "Carla", "carla@export.test"));
            setCurrentUser(admin);
            Date base = Date.from(Instant.parse("2026-01-01T12:00:00Z"));
            for (int i = 0; i < WORK_ORDERS; i++) {
                WorkOrder workOrder = newWorkOrder(company, String.format("WO %03d %s", i, i % 5 == 0 ? "bomba" :
                        "motor"));
                workOrder.setDescription("Descripción " + i);
                workOrder.setStatus(Status.values()[i % 4]);
                workOrder.setPriority(Priority.values()[(i / 3) % 4]);
                workOrder.setArchived(i % 10 == 0);
                workOrder.setDueDate(Date.from(base.toInstant().plus(i, ChronoUnit.DAYS)));
                workOrder.setPrimaryUser(i % 3 == 0 ? null : assignees.get(i % 3));
                if (i % 4 == 0) workOrder.getAssignedTo().addAll(assignees);
                else if (i % 4 == 1) workOrder.getAssignedTo().addAll(assignees.subList(0, 2));
                else if (i % 4 == 2) workOrder.getAssignedTo().add(assignees.get(1));
                if (i % 7 == 0) workOrder.getAssignedTo().add(technician);
                idsByIndex.add(workOrderRepository.save(workOrder).getId());
            }
            // the company of a new record is the one of the current user
            Company other = newCompany("OtherCo");
            setCurrentUser(newUser(other, newRole(other, Set.of(PermissionEntity.WORK_ORDERS),
                    Set.of(PermissionEntity.WORK_ORDERS)), "Otto", "other@export.test"));
            for (int i = 0; i < 5; i++) workOrderRepository.save(newWorkOrder(other, "Other WO " + i));
            setCurrentUser(admin);
            em.flush();
            em.clear();
        }

        private List<Long> idsWhere(IntPredicate predicate) {
            return IntStream.range(0, WORK_ORDERS).filter(predicate).mapToObj(idsByIndex::get).sorted().toList();
        }

        private void assertSameAsList(User user, Supplier<SearchCriteria> criteria, List<Long> expectedSet) {
            assertSameAsList(user, criteria, expectedSet, LIST_PAGE_SIZE);
        }

        private void assertSameAsList(User user, Supplier<SearchCriteria> criteria, List<Long> expectedSet,
                                      int listPageSize) {
            List<Long> listed = listedWorkOrderIds(user, criteria, listPageSize);
            List<Long> exported = exportedIds(exportWorkOrders(user, criteria.get()));

            assertEquals(listed, exported, "file sequence = list sequence");
            assertEquals(exported.size(), new HashSet<>(exported).size(), "no repeated rows");
            assertEquals(expectedSet, exported.stream().sorted().toList(), "only the records of the filter");
        }

        // T-01
        @Test
        void noFilter_exportsEveryWorkOrderOfTheList() {
            assertSameAsList(admin, SearchCriteria::new, idsWhere(i -> true));
        }

        // T-02
        @Test
        void eachFilter_exportsTheSameRecordsAsTheList() {
            Map<String, Supplier<FilterField>> filters = new LinkedHashMap<>();
            filters.put("status", () -> filter("status", "in", "", List.of(Status.OPEN, Status.IN_PROGRESS)));
            filters.put("priority", () -> filter("priority", "in", "", List.of(Priority.HIGH)));
            filters.put("archived", () -> filter("archived", "eq", false, new ArrayList<>()));
            filters.put("search", () -> FilterField.builder().field("title").operation("cn").value("bomba")
                    .values(new ArrayList<>()).alternatives(List.of(filter("description", "cn", "bomba",
                            new ArrayList<>()))).build());
            filters.put("assignedTo", () -> FilterField.builder().field("assignedTo").operation("inm")
                    .joinType(JoinType.LEFT).value("").values(List.of(assignees.get(0).getId(),
                            assignees.get(1).getId())).build());
            filters.put("dueDateFrom", () -> filter("dueDate", "ge",
                    Date.from(Instant.parse("2026-02-20T00:00:00Z")), new ArrayList<>()));
            filters.put("dueDateTo", () -> filter("dueDate", "le",
                    Date.from(Instant.parse("2026-04-10T00:00:00Z")), new ArrayList<>()));
            Map<String, IntPredicate> expected = Map.of(
                    "status", i -> i % 4 <= 1,
                    "priority", i -> (i / 3) % 4 == 3,
                    "archived", i -> i % 10 != 0,
                    "search", i -> i % 5 == 0,
                    "assignedTo", i -> i % 4 != 3,
                    "dueDateFrom", i -> i >= 50,
                    "dueDateTo", i -> i < 99);

            filters.forEach((name, filterField) -> {
                Supplier<SearchCriteria> criteria = () -> {
                    SearchCriteria searchCriteria = new SearchCriteria();
                    searchCriteria.getFilterFields().add(filterField.get());
                    return searchCriteria;
                };
                List<Long> expectedIds = idsWhere(expected.get(name));
                assertFalse(expectedIds.isEmpty(), name);
                assertSameAsList(admin, criteria, expectedIds, listPageSizeFor(name));
            });

            Supplier<SearchCriteria> combined = () -> {
                SearchCriteria searchCriteria = new SearchCriteria();
                List.of("status", "assignedTo", "search", "archived", "dueDateFrom", "dueDateTo")
                        .forEach(name -> searchCriteria.getFilterFields().add(filters.get(name).get()));
                return searchCriteria;
            };
            assertSameAsList(admin, combined, idsWhere(i -> i % 4 <= 1 && i % 5 == 0 && i % 10 != 0
                    && i >= 50 && i < 99), listPageSizeFor("assignedTo"));
        }

        /**
         * TD-EXP-06: when an {@code inm} filter repeats rows, the paged list loses records (Hibernate merges the
         * repeated rows of a page and Spring Data takes the smaller page as the last one). The export is compared
         * with the list read in one page, which holds them all, and with the expected set of the fixture.
         */
        private int listPageSizeFor(String filterName) {
            return filterName.equals("assignedTo") ? 10_000 : LIST_PAGE_SIZE;
        }

        // TD-EXP-06: characterization of the list defect, outside the scope of this sprint
        @Test
        void pagedListWithRepeatingJoin_losesRecords_butTheExportDoesNot() {
            Supplier<SearchCriteria> criteria = () -> {
                SearchCriteria searchCriteria = new SearchCriteria();
                searchCriteria.getFilterFields().add(FilterField.builder().field("assignedTo").operation("inm")
                        .joinType(JoinType.LEFT).value("").values(List.of(assignees.get(0).getId(),
                                assignees.get(1).getId())).build());
                return searchCriteria;
            };
            List<Long> expected = idsWhere(i -> i % 4 != 3);

            assertTrue(listedWorkOrderIds(admin, criteria).size() < expected.size());
            assertEquals(expected, exportedIds(exportWorkOrders(admin, criteria.get())).stream().sorted().toList());
        }

        // T-03
        @Test
        void eachOrder_exportsInTheSequenceOfTheList() {
            List<String> fields = List.of("title", "priority", "status", "primaryUser.firstName", "dueDate", "id");
            for (String field : fields) {
                for (Sort.Direction direction : Sort.Direction.values()) {
                    Supplier<SearchCriteria> criteria = () -> {
                        SearchCriteria searchCriteria = new SearchCriteria();
                        searchCriteria.setSortField(field);
                        searchCriteria.setDirection(direction);
                        return searchCriteria;
                    };
                    List<Long> listed = listedWorkOrderIds(admin, criteria);
                    List<Long> exported = exportedIds(exportWorkOrders(admin, criteria.get()));
                    assertEquals(WORK_ORDERS, exported.size(), field + " " + direction);
                    assertEquals(listed, exported, field + " " + direction);
                }
            }
        }

        // T-03: ties are broken by ascending id
        @Test
        void tiesAreBrokenByAscendingId() {
            SearchCriteria criteria = new SearchCriteria();
            criteria.setSortField("priority");
            criteria.setDirection(Sort.Direction.DESC);
            List<Long> exported = exportedIds(exportWorkOrders(admin, criteria));

            List<Long> expected = new ArrayList<>();
            for (int priority = 3; priority >= 0; priority--) {
                int value = priority;
                expected.addAll(idsWhere(i -> (i / 3) % 4 == value));
            }
            assertEquals(expected, exported);
        }

        // T-05
        @Test
        void userWithoutViewOther_exportsOnlyWhatTheirListShows() {
            assertSameAsList(technician, SearchCriteria::new, idsWhere(i -> i % 7 == 0));
        }

        // T-05 (INV-10)
        @Test
        void otherCompanyRecords_areNeverExported() {
            List<Long> exported = exportedIds(exportWorkOrders(admin, new SearchCriteria()));
            List<Long> otherCompany = em.createQuery("SELECT w.id FROM WorkOrder w WHERE w.company.id <> :id",
                    Long.class).setParameter("id", company.getId()).getResultList();

            assertFalse(otherCompany.isEmpty());
            assertTrue(Collections.disjoint(exported, otherCompany));
        }
    }

    // T-08
    @Nested
    class WorkOrderColumns {

        private static final List<String> HEADER_KEYS = List.of("ID", "Title", "Status", "Priority", "Description",
                "Due_Date", "Estimated_Duration", "Requires_Signature", "Category", "Location_Name", "Team_Name",
                "Primary_User_Email", "Assigned_To_Emails", "Asset_Name", "Completed_By_Email", "Completed_On",
                "Archived", "Feedback", "Customers", "Created_At");

        @Test
        void completeAndBareWorkOrders_keepThe20ColumnsAndValues() {
            Company company = newCompany("ColumnsCo");
            Role role = newRole(company, Set.of(PermissionEntity.WORK_ORDERS), Set.of(PermissionEntity.WORK_ORDERS));
            User admin = newUser(company, role, "Zoe", "cols-admin@export.test");
            User ana = newUser(company, role, "Ana", "cols-ana@export.test");
            User beto = newUser(company, role, "Beto", "cols-beto@export.test");
            setCurrentUser(admin);

            WorkOrderCategory category = new WorkOrderCategory();
            category.setName("Eléctrica");
            category.setCompanySettings(company.getCompanySettings());
            category = workOrderCategoryRepository.save(category);
            Location location = new Location();
            location.setName("Planta Norte");
            location.setCompany(company);
            location = locationRepository.save(location);
            Team team = new Team();
            team.setName("Equipo A");
            team.setCompany(company);
            team.setUsers(new ArrayList<>());
            team = teamRepository.save(team);
            Asset asset = new Asset();
            asset.setName("Bomba 1");
            asset.setCompany(company);
            asset.setStatus(AssetStatus.OPERATIONAL);
            asset.setFiles(new ArrayList<>());
            asset.setCustomers(new ArrayList<>());
            asset.setTeams(new ArrayList<>());
            asset.setParts(new ArrayList<>());
            asset.setCustomFieldValues(new ArrayList<>());
            asset = assetRepository.save(asset);
            Customer acme = new Customer();
            acme.setName("ACME");
            acme.setCompany(company);
            acme = customerRepository.save(acme);
            Customer beta = new Customer();
            beta.setName("Beta");
            beta.setCompany(company);
            beta = customerRepository.save(beta);

            WorkOrder complete = newWorkOrder(company, "Revisión de bomba");
            complete.setStatus(Status.COMPLETE);
            complete.setPriority(Priority.HIGH);
            complete.setDescription("Cambiar sello");
            // 03:00 UTC is still the previous day in Mexico City
            complete.setDueDate(Date.from(Instant.parse("2026-01-15T03:00:00Z")));
            complete.setEstimatedDuration(2.5);
            complete.setRequiredSignature(true);
            complete.setCategory(category);
            complete.setLocation(location);
            complete.setTeam(team);
            complete.setPrimaryUser(ana);
            complete.getAssignedTo().addAll(List.of(ana, beto));
            complete.setAsset(asset);
            complete.setCompletedBy(beto);
            complete.setCompletedOn(Date.from(Instant.parse("2026-02-01T18:00:00Z")));
            complete.setFeedback("Sin fugas");
            complete.getCustomers().addAll(List.of(acme, beta));
            complete = workOrderRepository.save(complete);
            WorkOrder bare = workOrderRepository.save(newWorkOrder(company, "Vacía"));
            em.flush();
            em.clear();
            Date createdAt = workOrderRepository.findById(complete.getId()).get().getCreatedAt();
            Date bareCreatedAt = workOrderRepository.findById(bare.getId()).get().getCreatedAt();
            DateFormat dateFormat = DateFormat.DDMMYY;
            String timeZone = "America/Mexico_City";

            byte[] bytes = exportWorkOrders(admin, new SearchCriteria());

            assertEquals(20, XlsxTestUtils.headerCount(bytes));
            List<List<Cell>> rows = XlsxTestUtils.read(bytes, 20);
            List<List<String>> text = XlsxTestUtils.readText(bytes, 20);
            assertEquals(3, rows.size());
            assertEquals(HEADER_KEYS.stream().map(key -> message(key, company)).toList(), text.get(0));

            List<String> first = text.get(1);
            assertEquals(Arrays.asList(String.valueOf(complete.getId()), "Revisión de bomba",
                    message("COMPLETE", company), message("HIGH", company), "Cambiar sello", "14/01/26"),
                    first.subList(0, 6));
            assertEquals(0, new BigDecimal("2.5").compareTo((BigDecimal) rows.get(1).get(6).getValue()));
            assertEquals(Arrays.asList(message("Yes", company), "Eléctrica", "Planta Norte", "Equipo A",
                    "cols-ana@export.test"), first.subList(7, 12));
            assertEquals(Set.of("cols-ana@export.test", "cols-beto@export.test"), Set.of(first.get(12).split(",")));
            assertEquals(Arrays.asList("Bomba 1", "cols-beto@export.test", "01/02/26", message("No", company),
                    "Sin fugas"), first.subList(13, 18));
            assertEquals(Set.of("ACME", "Beta"), Set.of(first.get(18).split(",")));
            assertEquals(Helper.getFormattedDate(createdAt, dateFormat, timeZone), first.get(19));

            List<String> second = text.get(2);
            assertEquals(Arrays.asList(String.valueOf(bare.getId()), "Vacía", message("OPEN", company),
                    message("NONE", company), null, null), second.subList(0, 6));
            assertEquals(0, BigDecimal.ZERO.compareTo((BigDecimal) rows.get(2).get(6).getValue()));
            assertEquals(Arrays.asList(message("No", company), null, null, null, null, null, null, null, null,
                    message("No", company), null, null), second.subList(7, 19));
            assertEquals(Helper.getFormattedDate(bareCreatedAt, dateFormat, timeZone), second.get(19));
        }
    }
}
