package com.grash.integration.support;

import com.grash.model.ApiKey;
import com.grash.model.Asset;
import com.grash.model.AssetDowntime;
import com.grash.model.Company;
import com.grash.model.Location;
import com.grash.model.Part;
import com.grash.model.RequestPortal;
import com.grash.model.RequestPortalField;
import com.grash.model.Role;
import com.grash.model.User;
import com.grash.model.UserInvitation;
import com.grash.model.WebhookEndpoint;
import com.grash.model.WorkOrder;
import com.grash.model.enums.AssetStatus;
import com.grash.model.enums.PermissionEntity;
import com.grash.model.enums.PortalFieldType;
import com.grash.model.enums.Priority;
import com.grash.model.enums.RoleCode;
import com.grash.model.enums.RoleType;
import com.grash.model.enums.Status;
import com.grash.model.enums.webhook.WebhookEvent;
import com.grash.repository.ApiKeyRepository;
import com.grash.repository.AssetDowntimeRepository;
import com.grash.repository.AssetRepository;
import com.grash.repository.LocationRepository;
import com.grash.repository.PartRepository;
import com.grash.repository.RequestPortalRepository;
import com.grash.repository.RoleRepository;
import com.grash.repository.UserInvitationRepository;
import com.grash.repository.WebhookEndpointRepository;
import com.grash.repository.WorkOrderRepository;
import org.springframework.boot.test.context.TestComponent;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.ArrayList;
import java.util.Date;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * Creates and commits the records that the deletion and audit log tests start from. Every record belongs to the
 * given organization; names are unique. Removing the organization ({@link TestOrganizations#delete}) removes them.
 */
@TestComponent
public class TestRecords {

    private final AssetRepository assetRepository;
    private final AssetDowntimeRepository assetDowntimeRepository;
    private final PartRepository partRepository;
    private final WorkOrderRepository workOrderRepository;
    private final ApiKeyRepository apiKeyRepository;
    private final WebhookEndpointRepository webhookEndpointRepository;
    private final RoleRepository roleRepository;
    private final UserInvitationRepository userInvitationRepository;
    private final LocationRepository locationRepository;
    private final RequestPortalRepository requestPortalRepository;
    private final JdbcTemplate jdbcTemplate;

    public TestRecords(AssetRepository assetRepository, AssetDowntimeRepository assetDowntimeRepository,
                       PartRepository partRepository, WorkOrderRepository workOrderRepository,
                       ApiKeyRepository apiKeyRepository, WebhookEndpointRepository webhookEndpointRepository,
                       RoleRepository roleRepository, UserInvitationRepository userInvitationRepository,
                       LocationRepository locationRepository, RequestPortalRepository requestPortalRepository,
                       JdbcTemplate jdbcTemplate) {
        this.assetRepository = assetRepository;
        this.assetDowntimeRepository = assetDowntimeRepository;
        this.partRepository = partRepository;
        this.workOrderRepository = workOrderRepository;
        this.apiKeyRepository = apiKeyRepository;
        this.webhookEndpointRepository = webhookEndpointRepository;
        this.roleRepository = roleRepository;
        this.userInvitationRepository = userInvitationRepository;
        this.locationRepository = locationRepository;
        this.requestPortalRepository = requestPortalRepository;
        this.jdbcTemplate = jdbcTemplate;
    }

    /**
     * Deletes the request portals of the organizations: {@code request_portal.company_id} is {@code NO ACTION},
     * so they block {@link TestOrganizations#delete}. Call it before that. P-13: {@code request.request_portal_id}
     * is {@code NO ACTION} too, so the requests of the organizations are unlinked from their portal first.
     */
    public void deleteRequestPortals(TestOrganizations.CommonData data) {
        for (Company company : List.of(data.o1().company(), data.o2().company())) {
            jdbcTemplate.update("UPDATE request SET request_portal_id = NULL WHERE company_id = ?", company.getId());
            jdbcTemplate.update("DELETE FROM request_portal_field WHERE company_id = ?", company.getId());
            jdbcTemplate.update("DELETE FROM request_portal WHERE company_id = ?", company.getId());
        }
    }

    public static String unique(String prefix) {
        return prefix + " " + UUID.randomUUID().toString().substring(0, 8);
    }

    public Asset asset(Company company, String customId, boolean demo) {
        Asset asset = new Asset();
        asset.setName(unique("P-10 asset"));
        asset.setCustomId(customId);
        asset.setDemo(demo);
        asset.setCompany(company);
        asset.setStatus(AssetStatus.OPERATIONAL);
        asset.setFiles(new ArrayList<>());
        asset.setCustomers(new ArrayList<>());
        asset.setTeams(new ArrayList<>());
        asset.setParts(new ArrayList<>());
        asset.setCustomFieldValues(new ArrayList<>());
        return assetRepository.saveAndFlush(asset);
    }

    public Asset asset(Company company) {
        return asset(company, null, false);
    }

    public AssetDowntime downtime(Asset asset) {
        AssetDowntime downtime = new AssetDowntime();
        downtime.setAsset(asset);
        downtime.setStartsOn(new Date());
        downtime.setCompany(asset.getCompany());
        return assetDowntimeRepository.saveAndFlush(downtime);
    }

    public WorkOrder workOrder(Company company, Asset asset, Status status, boolean demo) {
        WorkOrder workOrder = new WorkOrder();
        workOrder.setTitle(unique("P-10 WO"));
        workOrder.setStatus(status);
        workOrder.setPriority(Priority.NONE);
        workOrder.setEstimatedDuration(1.0);
        workOrder.setCompany(company);
        workOrder.setAsset(asset);
        workOrder.setDemo(demo);
        workOrder.setAssignedTo(new ArrayList<>());
        workOrder.setCustomers(new ArrayList<>());
        workOrder.setFiles(new ArrayList<>());
        workOrder.setCustomFieldValues(new ArrayList<>());
        return workOrderRepository.saveAndFlush(workOrder);
    }

    /**
     * A part created by {@code creator} (may be {@code null}).
     */
    public Part part(Company company, User creator) {
        Part part = new Part();
        part.setName(unique("P-10 part"));
        part.setCompany(company);
        part.setCreatedBy(creator == null ? null : creator.getId());
        part.setFiles(new ArrayList<>());
        part.setAssignedTo(new ArrayList<>());
        part.setCustomers(new ArrayList<>());
        part.setVendors(new ArrayList<>());
        part.setTeams(new ArrayList<>());
        part.setAssets(new ArrayList<>());
        part.setCustomFieldValues(new ArrayList<>());
        return partRepository.saveAndFlush(part);
    }

    public ApiKey apiKey(User owner) {
        ApiKey apiKey = new ApiKey();
        apiKey.setLabel(unique("P-10 key"));
        apiKey.setCode("p10-secret-" + UUID.randomUUID());
        apiKey.setUser(owner);
        apiKey.setCompany(owner.getCompany());
        return apiKeyRepository.saveAndFlush(apiKey);
    }

    public WebhookEndpoint endpoint(Company company, WebhookEvent event) {
        WebhookEndpoint endpoint = new WebhookEndpoint();
        endpoint.setUrl(WebhookDouble.ENDPOINT_URL + "?token=p10-" + UUID.randomUUID());
        endpoint.setSecret("p10-secret");
        endpoint.setEvent(event);
        endpoint.setCompany(company);
        return webhookEndpointRepository.saveAndFlush(endpoint);
    }

    public Role role(Company company) {
        return roleRepository.saveAndFlush(Role.builder()
                .name(unique("P-10 role"))
                .roleType(RoleType.ROLE_CLIENT)
                .code(RoleCode.USER_CREATED)
                .paid(true)
                .companySettings(company.getCompanySettings())
                .viewPermissions(new HashSet<>(Set.of(PermissionEntity.WORK_ORDERS)))
                .viewOtherPermissions(new HashSet<>())
                .createPermissions(new HashSet<>(Set.of(PermissionEntity.WORK_ORDERS, PermissionEntity.ASSETS)))
                .editOtherPermissions(new HashSet<>())
                .deleteOtherPermissions(new HashSet<>())
                .build());
    }

    public UserInvitation invitation(Role role) {
        return userInvitationRepository.saveAndFlush(UserInvitation.builder()
                .email("p10-invited-" + UUID.randomUUID() + "@example.com").role(role).build());
    }

    public Location location(Company company) {
        Location location = new Location();
        location.setName(unique("P-10 location"));
        location.setCompany(company);
        return locationRepository.saveAndFlush(location);
    }

    /**
     * A request portal with one field that cites {@code location} or {@code asset} (the other is {@code null}).
     */
    public RequestPortal portalCiting(Company company, Location location, Asset asset) {
        RequestPortal portal = new RequestPortal();
        portal.setTitle(unique("P-10 portal"));
        portal.setUuid(UUID.randomUUID().toString());
        portal.setCompany(company);
        RequestPortalField field = new RequestPortalField();
        field.setType(location != null ? PortalFieldType.LOCATION : PortalFieldType.ASSET);
        field.setLocation(location);
        field.setAsset(asset);
        field.setRequestPortal(portal);
        field.setCompany(company);
        portal.getFields().add(field);
        return requestPortalRepository.saveAndFlush(portal);
    }
}
