package com.grash.service;

import com.grash.dto.PartQuantityPatchDTO;
import com.grash.exception.CustomException;
import com.grash.mapper.PartQuantityMapper;
import com.grash.model.PartQuantity;
import com.grash.model.User;
import com.grash.model.enums.AuditSource;
import com.grash.model.enums.EntityType;
import com.grash.repository.PartQuantityRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Collection;
import java.util.Optional;

@Service
@RequiredArgsConstructor
public class PartQuantityService {
    private final PartQuantityRepository partQuantityRepository;
    private final CompanyService companyService;
    private final PartService partService;
    private final PurchaseOrderService purchaseOrderService;
    private final WorkOrderService workOrderService;
    private final PartQuantityMapper partQuantityMapper;
    private final DeletionService deletionService;

    public PartQuantity create(PartQuantity PartQuantity) {
        return partQuantityRepository.save(PartQuantity);
    }

    public PartQuantity update(Long id, PartQuantityPatchDTO partQuantity) {
        if (partQuantityRepository.existsById(id)) {
            PartQuantity savedPartQuantity = partQuantityRepository.findById(id).get();
            return partQuantityRepository.save(partQuantityMapper.updatePartQuantity(savedPartQuantity, partQuantity));
        } else throw new CustomException("Not found", HttpStatus.NOT_FOUND);
    }

    public Collection<PartQuantity> getAll() {
        return partQuantityRepository.findAll();
    }

    public void delete(Long id) {
        partQuantityRepository.deleteById(id);
    }

    /**
     * P-12 (E-6, PPB-23): removes a part consumption of an open work order and records it, in one transaction. The
     * stock is handled by the caller, as before (P-16b). P-16 (D-P16-3): a line of a pending purchase order is
     * removed and recorded the same way; the purchase order has no folio.
     */
    @Transactional
    public void delete(PartQuantity partQuantity, User actor) {
        String name = partQuantity.getPart().getName();
        AuditTarget target = partQuantity.getWorkOrder() == null
                ? new AuditTarget(EntityType.PART_QUANTITY, partQuantity.getId(), null, name,
                partQuantity.getCompany().getId())
                : AuditTarget.childOf(EntityType.PART_QUANTITY, partQuantity.getId(), name, partQuantity.getWorkOrder());
        deletionService.delete(target, actor, AuditSource.API, null,
                () -> partQuantityRepository.deleteById(partQuantity.getId()));
    }

    public Optional<PartQuantity> findById(Long id) {
        return partQuantityRepository.findById(id);
    }

    public Collection<PartQuantity> findByCompany(Long id) {
        return partQuantityRepository.findByCompany_Id(id);
    }

    public Collection<PartQuantity> findByWorkOrder(Long id) {
        return partQuantityRepository.findByWorkOrder_Id(id);
    }

    public Collection<PartQuantity> findByPart(Long id) {
        return partQuantityRepository.findByPart_Id(id);
    }

    public Collection<PartQuantity> findByPurchaseOrder(Long id) {
        return partQuantityRepository.findByPurchaseOrder_Id(id);
    }


    public void save(PartQuantity partQuantity) {
        partQuantityRepository.save(partQuantity);
    }
}
