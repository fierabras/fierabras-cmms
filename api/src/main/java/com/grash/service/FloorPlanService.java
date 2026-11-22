package com.grash.service;

import com.grash.dto.FloorPlanPatchDTO;
import com.grash.exception.CustomException;
import com.grash.mapper.FloorPlanMapper;
import com.grash.model.FloorPlan;
import com.grash.model.Location;
import com.grash.model.User;
import com.grash.model.enums.AuditSource;
import com.grash.model.enums.EntityType;
import com.grash.repository.FloorPlanRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import jakarta.persistence.EntityManager;

import java.util.Collection;
import java.util.Optional;

@Service
@RequiredArgsConstructor
public class FloorPlanService {
    private final FloorPlanRepository floorPlanRepository;
    private final FileService fileService;
    private final LocationService locationService;
    private final FloorPlanMapper floorPlanMapper;
    private final EntityManager em;
    private final DeletionService deletionService;

    @Transactional
    public FloorPlan create(FloorPlan floorPlan) {
        FloorPlan savedFloorPlan = floorPlanRepository.saveAndFlush(floorPlan);
        em.refresh(savedFloorPlan);
        return savedFloorPlan;
    }

    @Transactional
    public FloorPlan update(Long id, FloorPlanPatchDTO floorPlan) {
        if (floorPlanRepository.existsById(id)) {
            FloorPlan savedFloorPlan = floorPlanRepository.findById(id).get();
            FloorPlan updatedFloorPlan =
                    floorPlanRepository.saveAndFlush(floorPlanMapper.updateFloorPlan(savedFloorPlan, floorPlan));
            em.refresh(updatedFloorPlan);
            return updatedFloorPlan;
        } else throw new CustomException("Not found", HttpStatus.NOT_FOUND);
    }

    public Collection<FloorPlan> getAll() {
        return floorPlanRepository.findAll();
    }

    /**
     * P-14 (PPB-14, PPB-23): deletes the floor plan and records it in the audit log with the folio of its location, in
     * one transaction. Its image is kept, without references: it can be deleted afterwards.
     */
    @Transactional
    public void delete(FloorPlan floorPlan, User actor) {
        Long id = floorPlan.getId();
        Location location = floorPlan.getLocation();
        deletionService.delete(new AuditTarget(EntityType.FLOOR_PLAN, id, location.getCustomId(), floorPlan.getName(),
                        location.getCompany().getId()), actor, AuditSource.API, null,
                () -> floorPlanRepository.deleteById(id));
    }

    public Optional<FloorPlan> findById(Long id) {
        return floorPlanRepository.findById(id);
    }


    public Collection<FloorPlan> findByLocation(Long id) {
        return floorPlanRepository.findByLocation_Id(id);
    }
}

