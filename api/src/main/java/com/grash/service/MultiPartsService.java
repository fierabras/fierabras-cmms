package com.grash.service;

import com.grash.dto.MultiPartsPatchDTO;
import com.grash.exception.CustomException;
import com.grash.mapper.MultiPartsMapper;
import com.grash.model.MultiParts;
import com.grash.model.User;
import com.grash.model.enums.AuditSource;
import com.grash.model.enums.EntityType;
import com.grash.repository.MultiPartsRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import jakarta.persistence.EntityManager;

import java.util.Collection;
import java.util.Optional;

@Service
@RequiredArgsConstructor
public class MultiPartsService {
    private final MultiPartsRepository multiPartsRepository;
    private final CompanyService companyService;
    private final MultiPartsMapper multiPartsMapper;
    private final EntityManager em;
    private final PartService partService;
    private final DeletionService deletionService;

    @Transactional
    public MultiParts create(MultiParts multiParts) {
        MultiParts savedMultiParts = multiPartsRepository.saveAndFlush(multiParts);
        em.refresh(savedMultiParts);
        return savedMultiParts;
    }

    @Transactional
    public MultiParts update(Long id, MultiPartsPatchDTO multiPartsPatchDTO) {
        if (multiPartsRepository.existsById(id)) {
            MultiParts savedMultiParts = multiPartsRepository.findById(id).get();
            MultiParts updatedMultiParts =
                    multiPartsRepository.saveAndFlush(multiPartsMapper.updateMultiParts(savedMultiParts,
                            multiPartsPatchDTO));
            em.refresh(updatedMultiParts);
            return updatedMultiParts;
        } else throw new CustomException("Not found", HttpStatus.NOT_FOUND);
    }

    public Collection<MultiParts> getAll() {
        return multiPartsRepository.findAll();
    }

    /**
     * P-16 (PPB-17, PPB-23): a set of parts is a template; it is always deleted, with its associations, and recorded
     * in the audit log. The work orders it was used on keep their lines and their movements: they never cite it.
     */
    @Transactional
    public void delete(MultiParts set, User actor) {
        deletionService.delete(new AuditTarget(EntityType.MULTI_PARTS, set.getId(), null, set.getName(),
                        set.getCompany().getId()), actor, AuditSource.API, null,
                () -> multiPartsRepository.deleteById(set.getId()));
    }

    public Optional<MultiParts> findById(Long id) {
        return multiPartsRepository.findById(id);
    }

    public Collection<MultiParts> findByCompany(Long id) {
        return multiPartsRepository.findByCompany_Id(id);
    }

}

