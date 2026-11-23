package com.grash.service;

import com.grash.advancedsearch.SearchCriteria;
import com.grash.advancedsearch.SpecificationBuilder;
import com.grash.dto.license.LicenseEntitlement;
import com.grash.dto.requestPortal.RequestPortalPatchDTO;
import com.grash.dto.requestPortal.RequestPortalPostDTO;
import com.grash.exception.CustomException;
import com.grash.mapper.RequestPortalMapper;
import com.grash.model.Asset;
import com.grash.model.RequestPortal;
import com.grash.model.RequestPortalField;
import com.grash.model.User;
import com.grash.model.enums.AuditSource;
import com.grash.model.enums.EntityType;
import com.grash.model.enums.PermissionEntity;
import com.grash.model.enums.PlanFeatures;
import com.grash.repository.RequestPortalRepository;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Service
@Transactional
@RequiredArgsConstructor
public class RequestPortalService {
    private final RequestPortalRepository requestPortalRepository;
    private final RequestPortalMapper requestPortalMapper;
    private final LicenseService licenseService;
    private final DeletionService deletionService;
    private final AssetService assetService;

    public static final String REQUEST_PORTAL_IN_USE = "REQUEST_PORTAL_IN_USE";
    private static final String REQUEST_PORTAL_IN_USE_MESSAGE = "This request portal has received requests and "
            + "cannot be deleted";

    public RequestPortal create(@Valid RequestPortalPostDTO requestPortalReq, User user) {
        if (!user.getCompany().getSubscription().getSubscriptionPlan().getFeatures().contains(PlanFeatures.REQUEST_PORTAL) || !user.getRole().getViewPermissions().contains(PermissionEntity.SETTINGS) || !licenseService.hasEntitlement(LicenseEntitlement.REQUEST_PORTAL))
            throw new CustomException("Access denied", HttpStatus.FORBIDDEN);
        // P-14b (E-3, PPB-08): a field does not cite an archived asset
        assetService.rejectRetired(List.of(), fieldAssets(requestPortalReq.getFields()));
        RequestPortal requestPortal =
                requestPortalMapper.fromPostDto(requestPortalReq);
        requestPortal.setUuid(UUID.randomUUID().toString());
        requestPortal.getFields().forEach(field -> field.setRequestPortal(requestPortal));

        return requestPortalRepository.save(requestPortal);
    }


    private static List<Asset> fieldAssets(Collection<RequestPortalField> fields) {
        return fields == null ? List.of() : fields.stream().map(RequestPortalField::getAsset).toList();
    }

    public List<RequestPortal> getAll() {
        return requestPortalRepository.findAll();
    }

    /**
     * P-13 (PPB-02, PPB-14, PPB-23): a portal that received requests is not deleted; otherwise it goes with its
     * fields and the deletion is recorded.
     */
    public void delete(RequestPortal requestPortal, User actor) {
        Long id = requestPortal.getId();
        deletionService.delete(new AuditTarget(EntityType.REQUEST_PORTAL, id, null, requestPortal.getTitle(),
                        requestPortal.getCompany().getId()), actor, AuditSource.API,
                DeletionRule.inUse(REQUEST_PORTAL_IN_USE, REQUEST_PORTAL_IN_USE_MESSAGE)
                        .blockedBy(EntityType.REQUEST, () -> deletionService.isCited("request", "request_portal_id", id)),
                () -> requestPortalRepository.delete(requestPortal));
    }

    public Optional<RequestPortal> findById(Long id) {
        return requestPortalRepository.findById(id);
    }

    public RequestPortal update(Long id, RequestPortalPatchDTO requestPortalPatchDTO, User user) {
        RequestPortal savedRequestPortal =
                requestPortalRepository.findById(id).orElseThrow(() -> new CustomException("Not found",
                        HttpStatus.NOT_FOUND));
        // P-14b (E-3, PPB-08): a field does not cite an archived asset anew; those the fields already cite are kept
        assetService.rejectRetired(fieldAssets(savedRequestPortal.getFields()),
                fieldAssets(requestPortalPatchDTO.getFields()));
        RequestPortal newRequestPortal = requestPortalMapper.updateRequestPortal(savedRequestPortal,
                requestPortalPatchDTO);
        newRequestPortal.getFields().forEach(field -> field.setRequestPortal(savedRequestPortal));
        return requestPortalRepository.save(newRequestPortal);
    }

    public Page<RequestPortal> findBySearchCriteria(SearchCriteria searchCriteria) {
        SpecificationBuilder<RequestPortal> builder = new SpecificationBuilder<>();
        searchCriteria.getFilterFields().forEach(builder::with);
        Pageable page = PageRequest.of(searchCriteria.getPageNum(), searchCriteria.getPageSize(),
                searchCriteria.getDirection(), searchCriteria.getSortField());
        return requestPortalRepository.findAll(builder.build(), page);
    }

    public Optional<RequestPortal> findByUuidByUser(String uuid) {
        return requestPortalRepository.findByUuid(uuid).map(requestPortal -> {
            if (requestPortal.getCompany().getSubscription().getSubscriptionPlan().getFeatures().contains(PlanFeatures.REQUEST_PORTAL) && licenseService.hasEntitlement(LicenseEntitlement.REQUEST_PORTAL))
                return requestPortal;
            else return null;
        });
    }
}
