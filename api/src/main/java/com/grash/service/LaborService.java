package com.grash.service;

import com.grash.dto.LaborPatchDTO;
import com.grash.dto.license.LicenseEntitlement;
import com.grash.exception.CustomException;
import com.grash.mapper.LaborMapper;
import com.grash.model.Labor;
import com.grash.model.User;
import com.grash.model.enums.AuditSource;
import com.grash.model.enums.EntityType;
import com.grash.model.enums.TimeStatus;
import com.grash.repository.LaborRepository;
import com.grash.utils.Helper;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

import jakarta.persistence.EntityManager;
import jakarta.transaction.Transactional;

import java.util.Collection;
import java.util.Date;
import java.util.Optional;
import java.util.concurrent.TimeUnit;

@Service
@RequiredArgsConstructor
public class LaborService {
    private final LaborRepository laborRepository;

    private final CompanyService companyService;
    private final TimeCategoryService timeCategoryService;
    private final UserService userService;
    private final WorkOrderService workOrderService;
    private final LaborMapper laborMapper;
    private final EntityManager em;
    private final LicenseService licenseService;
    private final DeletionService deletionService;

    @Transactional
    public Labor create(Labor labor) {
        if (!licenseService.hasEntitlement(LicenseEntitlement.TIME_TRACKING))
            throw new CustomException("You need a license to create a labor", HttpStatus.FORBIDDEN);
        updateHourlyRateIfNeeded(labor);
        Labor savedLabor = laborRepository.saveAndFlush(labor);
        em.refresh(savedLabor);
        return savedLabor;
    }

    private void updateHourlyRateIfNeeded(Labor labor) {
        if (labor.getHourlyRate() <= 0L && labor.getAssignedTo() != null) {
            User assignedUser =
                    userService.findById(labor.getAssignedTo().getId()).orElseThrow(() -> new CustomException("User " +
                            "not found", HttpStatus.NOT_FOUND));
            if (assignedUser.getRate() > 0L) labor.setHourlyRate(assignedUser.getRate());
        }
    }

    @Transactional
    public Labor update(Long id, LaborPatchDTO labor) {
        if (laborRepository.existsById(id)) {
            Labor savedLabor = laborRepository.findById(id).get();
            Labor labor1 = laborMapper.updateLabor(savedLabor, labor);
            updateHourlyRateIfNeeded(labor1);
            Labor updatedLabor = laborRepository.saveAndFlush(labor1);
            em.refresh(updatedLabor);
            return updatedLabor;
        } else throw new CustomException("Not found", HttpStatus.NOT_FOUND);
    }

    public Labor save(Labor labor) {
        return laborRepository.save(labor);
    }

    public Collection<Labor> getAll() {
        return laborRepository.findAll();
    }

    public void delete(Long id) {
        laborRepository.deleteById(id);
    }

    /**
     * P-12 (E-6, PPB-23): removes a labor entry of an open work order and records it, in one transaction.
     */
    @Transactional
    public void delete(Labor labor, User actor) {
        deletionService.delete(AuditTarget.childOf(EntityType.LABOR, labor.getId(), null, labor.getWorkOrder()),
                actor, AuditSource.API, null, () -> laborRepository.deleteById(labor.getId()));
    }

    public Optional<Labor> findById(Long id) {
        return laborRepository.findById(id);
    }

    public Collection<Labor> findByWorkOrder(Long id) {
        return laborRepository.findByWorkOrder_Id(id);
    }

    public Labor stop(Labor labor) {
        // INC-02 (E-02): the time of a stopped timer is already in its duration
        if (labor.getStatus() == TimeStatus.STOPPED) return labor;
        labor.setStatus(TimeStatus.STOPPED);
        labor.setDuration(labor.getDuration() + Helper.getDateDiff(labor.getStartedAt(), new Date(), TimeUnit.SECONDS));
        return save(labor);
    }
}

