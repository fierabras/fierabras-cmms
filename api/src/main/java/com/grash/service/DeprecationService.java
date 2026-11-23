package com.grash.service;

import com.grash.dto.DeprecationPatchDTO;
import com.grash.exception.CustomException;
import com.grash.mapper.DeprecationMapper;
import com.grash.model.Deprecation;
import com.grash.model.User;
import com.grash.model.enums.AuditSource;
import com.grash.model.enums.EntityType;
import com.grash.repository.AssetRepository;
import com.grash.repository.DeprecationRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import jakarta.persistence.EntityManager;

import java.util.Collection;
import java.util.Optional;

@Service
@RequiredArgsConstructor
public class DeprecationService {
    private final DeprecationRepository deprecationRepository;
    private final CompanyService companyService;
    private final DeprecationMapper deprecationMapper;
    private final DeletionService deletionService;
    private final EntityManager em;
    private final AssetRepository assetRepository;

    public Deprecation create(Deprecation Deprecation) {
        return deprecationRepository.save(Deprecation);
    }

    public Deprecation update(Long id, DeprecationPatchDTO deprecation) {
        if (deprecationRepository.existsById(id)) {
            rejectIfAssetRetired(id);
            Deprecation savedDeprecation = deprecationRepository.findById(id).get();
            return deprecationRepository.save(deprecationMapper.updateDeprecation(savedDeprecation, deprecation));
        } else throw new CustomException("Not found", HttpStatus.NOT_FOUND);
    }

    public Collection<Deprecation> getAll() {
        return deprecationRepository.findAll();
    }

    /**
     * P-14 (PPB-14, PPB-23): deletes the depreciation and records it in the audit log with the folio of the asset that
     * points to it, if any, in one transaction. The asset keeps existing: asset.deprecation_id is SET NULL.
     */
    @Transactional
    public void delete(Deprecation deprecation, User actor) {
        Long id = deprecation.getId();
        rejectIfAssetRetired(id);
        // the asset points to its depreciation (asset.deprecation_id); this service cannot use AssetService
        String folio = (String) em.createNativeQuery("SELECT max(custom_id) FROM asset WHERE deprecation_id = ?1")
                .setParameter(1, id).getSingleResult();
        deletionService.delete(new AuditTarget(EntityType.DEPRECATION, id, folio, null,
                        deprecation.getCompany().getId()), actor, AuditSource.API, null,
                () -> deprecationRepository.deleteById(id));
    }

    /**
     * P-14b (E-5, PPB-15, CA-PPB-123): the depreciation of an archived asset does not change. The asset points to its
     * depreciation, so the assets that cite it are read here (this service cannot use AssetService).
     */
    private void rejectIfAssetRetired(Long id) {
        assetRepository.findByDeprecation_Id(id).forEach(AssetStateGuard::check);
    }

    public Optional<Deprecation> findById(Long id) {
        return deprecationRepository.findById(id);
    }

}
