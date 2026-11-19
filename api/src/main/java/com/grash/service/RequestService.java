package com.grash.service;

import com.grash.advancedsearch.FilterField;
import com.grash.advancedsearch.SearchCriteria;
import com.grash.advancedsearch.SpecificationBuilder;
import com.grash.dto.RequestPatchDTO;
import com.grash.dto.RequestPostDTO;
import com.grash.dto.RequestShowDTO;
import com.grash.dto.cutomField.CustomFieldValuePostDTO;
import com.grash.dto.license.LicenseEntitlement;
import com.grash.dto.workOrder.WorkOrderPostDTO;
import com.grash.exception.CustomException;
import com.grash.mapper.RequestMapper;
import com.grash.model.*;
import com.grash.model.enums.CustomFieldEntityType;
import com.grash.model.enums.PermissionEntity;
import com.grash.model.enums.PortalFieldType;
import com.grash.model.enums.Priority;
import com.grash.model.enums.RoleType;
import com.grash.model.enums.webhook.WebhookEvent;

import com.grash.repository.FieldConfigurationRepository;
import com.grash.repository.RequestRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.PessimisticLockingFailureException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import jakarta.persistence.EntityManager;
import jakarta.persistence.criteria.*;

import java.util.*;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class RequestService {
    private final RequestRepository requestRepository;
    private final CompanyService companyService;
    private final FileService fileService;
    private final LocationService locationService;
    private final UserService userService;
    private final TeamService teamService;
    private final AssetService assetService;
    private final WorkOrderService workOrderService;
    private final RequestMapper requestMapper;
    private final EntityManager em;
    private final CustomSequenceService customSequenceService;
    private final LicenseService licenseService;
    private final RequestPortalService requestPortalService;
    private final FieldConfigurationRepository fieldConfigurationRepository;
    private final WebhookDispatchService webhookDispatchService;
    private final CustomFieldValueService customFieldValueService;


    @Transactional
    public Request create(Request request, Company company) {
        if (request instanceof RequestPostDTO requestPostDTO) {
            request = requestMapper.fromPostDTO(requestPostDTO);
            if (!requestPostDTO.getCustomFields().isEmpty()) {
                setRequestCustomFields(request, requestPostDTO.getCustomFields(), company);
            }
        }
        if (request.getAudioDescription() != null && !licenseService.hasEntitlement(LicenseEntitlement.VOICE_NOTES))
            throw new CustomException("You need a license to add voice notes", HttpStatus.FORBIDDEN);
        startPending(request);
        Long nextSequence = customSequenceService.getNextRequestSequence(company);
        request.setCustomId("R" + String.format("%06d", nextSequence));

        Request savedRequest = requestRepository.saveAndFlush(request);
        em.refresh(savedRequest);
        Map<String, Object> webhookPayload = new HashMap<>();
        webhookPayload.put("requestId", savedRequest.getId());
        Object serializedRequest = requestMapper.toShowDto(savedRequest);
        webhookDispatchService.dispatchWebhook(company, WebhookEvent.NEW_REQUEST, webhookPayload,
                "newRequest", serializedRequest, null, null, null, null, null);
        return savedRequest;
    }

    @Transactional
    public Request create(Request request, Company company, RequestPortal requestPortal) {
        if (request.getAudioDescription() != null && !licenseService.hasEntitlement(LicenseEntitlement.VOICE_NOTES))
            throw new CustomException("You need a license to add voice notes", HttpStatus.FORBIDDEN);
        startPending(request);
        Long nextSequence = customSequenceService.getNextRequestSequence(company);
        request.setCustomId("R" + String.format("%06d", nextSequence));
        request.setRequestPortal(requestPortal);
        request.setCompany(requestPortal.getCompany());
        RequestPortalField assetField =
                requestPortal.getFields().stream().filter(field -> field.getType().equals(PortalFieldType.ASSET) && field.getAsset() != null).findFirst().orElse(null);
        RequestPortalField locationField =
                requestPortal.getFields().stream().filter(field -> field.getType().equals(PortalFieldType.LOCATION) && field.getLocation() != null).findFirst().orElse(null);

        if (assetField != null) request.setAsset(assetField.getAsset());
        if (locationField != null) request.setLocation(locationField.getLocation());


        Request savedRequest = requestRepository.saveAndFlush(request);
        em.refresh(savedRequest);
        Map<String, Object> webhookPayload = new HashMap<>();
        webhookPayload.put("requestId", savedRequest.getId());
        Object serializedRequest2 = requestMapper.toShowDto(savedRequest);
        webhookDispatchService.dispatchWebhook(company, WebhookEvent.NEW_REQUEST, webhookPayload,
                "newRequest", serializedRequest2, null, null, null, null, null);
        return savedRequest;
    }

    /**
     * INC-02 (R-02): a new request always starts pending, whatever the client sends. Its id and its review state
     * (rejection and approval) belong to the server; only approve and cancel change the review state afterwards.
     */
    private static void startPending(Request request) {
        request.setId(null);
        request.setCancelled(false);
        request.setCancellationReason(null);
        request.setWorkOrder(null);
    }

    @Transactional
    public Request update(Long id, RequestPatchDTO request, Company company) {
        if (requestRepository.existsById(id)) {
            Request savedRequest = requestRepository.findById(id).get();
            if (!request.getCustomFields().isEmpty()) {
                setRequestCustomFields(savedRequest, request.getCustomFields(), company);
            }
            Request updatedRequest = requestRepository.saveAndFlush(requestMapper.updateRequest(savedRequest, request));
            em.refresh(updatedRequest);
            return updatedRequest;
        } else throw new CustomException("Not found", HttpStatus.NOT_FOUND);
    }

    private void setRequestCustomFields(Request request, List<CustomFieldValuePostDTO> customFieldValuePostDTOS,
                                        Company company) {
        customFieldValueService.setCustomFields(
                request,
                request.getCustomFieldValues(),
                customFieldValuePostDTOS,
                company,
                CustomFieldEntityType.WORK_ORDER,
                cfv -> cfv.setRequest(request)
        );
    }

    public Collection<Request> getAll() {
        return requestRepository.findAll();
    }

    public void delete(Long id) {
        requestRepository.deleteById(id);
    }

    public Optional<Request> findById(Long id) {
        return requestRepository.findById(id);
    }

    public static final String REQUEST_UNDER_REVIEW = "REQUEST_UNDER_REVIEW";
    private static final String REVIEW_LOCK_TIMEOUT = "5s";

    /**
     * INC-02 (R-07): the request to approve or reject, locked until the review transaction ends. A concurrent review
     * of the same request waits and then reads it already reviewed, so only one review wins and only the winner runs
     * the effects (work order, notifications, workflows, webhooks).
     * <p>
     * INC-02 (E-05): the wait is bounded. PostgreSQL ignores the JPA lock timeout, so it is set on the transaction for
     * this lock only; past it the review answers 409 and changes nothing.
     */
    public Optional<Request> findByIdForReview(Long id) {
        em.createNativeQuery("SET LOCAL lock_timeout = '" + REVIEW_LOCK_TIMEOUT + "'").executeUpdate();
        Optional<Request> request;
        try {
            request = requestRepository.findByIdForUpdate(id);
        } catch (PessimisticLockingFailureException e) {
            throw new CustomException("This request is being reviewed by another user, try again",
                    HttpStatus.CONFLICT, REQUEST_UNDER_REVIEW);
        }
        em.createNativeQuery("SET LOCAL lock_timeout = DEFAULT").executeUpdate();
        return request;
    }

    public Collection<Request> findByCompany(Long id) {
        return requestRepository.findByCompany_Id(id);
    }

    public WorkOrder createWorkOrderFromRequest(Request request, User creator) {
        WorkOrderPostDTO workOrder = workOrderService.getWorkOrderFromWorkOrderBase(request);
        if (creator.getCompany().getCompanySettings().getGeneralPreferences().isAutoAssignRequests()) {
            User primaryUser = workOrder.getPrimaryUser();
            workOrder.setPrimaryUser(primaryUser == null ? creator : primaryUser);
        }
        workOrder.setParentRequest(request);
        WorkOrder savedWorkOrder = workOrderService.create(workOrder, creator.getCompany());
        request.setWorkOrder(savedWorkOrder);
        requestRepository.save(request);

        return savedWorkOrder;
    }

    public Request save(Request request) {
        return requestRepository.save(request);
    }

    public Collection<Request> findByCreatedAtBetweenAndCompany(Date date1, Date date2, Long id) {
        return requestRepository.findByCreatedAtBetweenAndCompany_Id(date1, date2, id);
    }

    /**
     * The scope of the request list for the user, shared by the list and its export (RF-10): the company (or the
     * companies of a super account) and, without view other requests, only the own ones.
     *
     * @throws CustomException 403 for a client user without view of requests
     */
    public SearchCriteria getSearchCriteria(User user, SearchCriteria searchCriteria) {
        if (user.getRole().getRoleType().equals(RoleType.ROLE_CLIENT)) {
            if (user.getRole().getViewPermissions().contains(PermissionEntity.REQUESTS)) {
                if (!user.getSuperAccountRelations().isEmpty()) {
                    List<Long> childCompanyIds = user.getSuperAccountRelations().stream()
                            .map(rel -> rel.getChildUser().getCompany().getId())
                            .distinct()
                            .toList();
                    searchCriteria.getFilterFields().add(FilterField.builder()
                            .field("company")
                            .operation("inm")
                            .joinType(JoinType.LEFT)
                            .value("")
                            .values(new ArrayList<>(childCompanyIds))
                            .build());
                } else {
                    searchCriteria.filterCompany(user);
                }
                boolean canViewOthers = user.getRole().getViewOtherPermissions().contains(PermissionEntity.REQUESTS);
                if (!canViewOthers) {
                    searchCriteria.filterCreatedBy(user);
                }
            } else throw new CustomException("Access Denied", HttpStatus.FORBIDDEN);
        }
        return searchCriteria;
    }

    public Page<RequestShowDTO> findBySearchCriteria(SearchCriteria searchCriteria) {
        Pageable page = PageRequest.of(searchCriteria.getPageNum(), searchCriteria.getPageSize(),
                searchCriteria.sortWithIdTiebreak());
        return requestRepository.findAll(getSpecification(searchCriteria), page).map(requestMapper::toShowDto);
    }

    /**
     * The filters of the request list, shared by the list and its export (RF-10). Status and priority have their own
     * predicates.
     */
    public Specification<Request> getSpecification(SearchCriteria searchCriteria) {
        SpecificationBuilder<Request> builder = new SpecificationBuilder<>();
        SearchCriteria searchCriteriaClone = searchCriteria.clone();

        builder.with((Specification<Request>) (requestRoot, query, criteriaBuilder) -> {
            List<Predicate> predicates = new ArrayList<>();
            if (searchCriteriaClone.getFilterFields().stream().anyMatch(filterField -> filterField.getField().equals(
                    "priority"))) {
                List<Priority> priorities = searchCriteriaClone.getFilterFields().stream()
                        .filter(filterField -> filterField.getField().equals("priority"))
                        .findFirst().get().getValues().stream().map(value -> Priority.getPriorityFromString(value.toString())).collect(Collectors.toList());
                if (!priorities.isEmpty()) {
                    Join<Request, WorkOrder> workOrderJoin = requestRoot.join(Request_.workOrder, JoinType.LEFT);
                    predicates.add(criteriaBuilder.or(workOrderJoin.get(WorkOrder_.priority).in(priorities),
                            requestRoot.get(
                            Request_.priority).in(priorities)));
                }
            }

            if (searchCriteriaClone.getFilterFields().stream().anyMatch(filterField -> filterField.getField().equals(
                    "status"))) {
                List<Object> values = searchCriteriaClone.getFilterFields().stream()
                        .filter(filterField -> filterField.getField().equals("status"))
                        .findFirst().get().getValues();
                predicates.add(criteriaBuilder.or(values.stream().map(value -> {
                    if (value instanceof String) {
                        switch (value.toString()) {
                            case "CANCELLED":
                                return criteriaBuilder.equal(requestRoot.get("cancelled"), true);
                            case "APPROVED":
                                return criteriaBuilder.isNotNull(requestRoot.get("workOrder"));
                            case "PENDING":
                                return criteriaBuilder.and(criteriaBuilder.isNull(requestRoot.get("workOrder")),
                                        criteriaBuilder.equal(requestRoot.get("cancelled"), false));
                            default:
                                return null;
                        }
                    }
                    return null;
                }).toArray(Predicate[]::new)));
            }
            return criteriaBuilder.and(predicates.toArray(new Predicate[0]));
        });
        searchCriteria.getFilterFields().stream()
                .filter(filterField -> !filterField.getField().equals("status") && !filterField.getField().equals(
                        "priority"))
                .forEach(builder::with);
        return builder.build();
    }

    /**
     * The requests of the export, with the relations of its columns.
     */
    public List<Request> findAllForExport(List<Long> ids) {
        return requestRepository.findAllForExportByIdIn(ids);
    }

    public boolean isRequestInCompany(Request request, long companyId, boolean optional) {
        if (optional) {
            Optional<Request> optionalRequest = request == null ? Optional.empty() : findById(request.getId());
            return request == null || (optionalRequest.isPresent() && optionalRequest.get().getCompany().getId().equals(companyId));
        } else {
            Optional<Request> optionalRequest = findById(request.getId());
            return optionalRequest.isPresent() && optionalRequest.get().getCompany().getId().equals(companyId);
        }
    }

    /**
     * INC-02 (R-06): the pending requests the user can open, with the rule of the request detail: every one with
     * view on other requests, otherwise only their own.
     */
    public Integer countPending(User user) {
        Long companyId = user.getCompany().getId();
        return user.getRole().getViewOtherPermissions().contains(PermissionEntity.REQUESTS)
                ? requestRepository.countPending(companyId)
                : requestRepository.countPendingByCreatedBy(companyId, user.getId());
    }

    public List<Request> findByCategoryAndCreatedAtBetween(Long id, Date start, Date end) {
        return requestRepository.findByCategory_IdAndCreatedAtBetween(id, start, end);
    }
}

