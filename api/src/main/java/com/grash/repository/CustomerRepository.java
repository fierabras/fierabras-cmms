package com.grash.repository;

import com.grash.model.Customer;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;

public interface CustomerRepository extends JpaRepository<Customer, Long>, JpaSpecificationExecutor<Customer> {
    Collection<Customer> findByCompany_Id(Long id);

    List<Customer> findByNameIgnoreCaseAndCompany_Id(String name, Long companyId);

    void deleteByCompany_IdAndIsDemoTrue(Long companyId);

    List<Customer> findByCompany_IdAndIdIn(Long id, List<Long> ids);

    // P-12b (PPB-02, §3.3 c): associations whose other end is closed or retired; no foreign key backs them

    @Query("SELECT COUNT(w) > 0 FROM WorkOrder w JOIN w.customers c WHERE c.id = :id "
            + "AND (w.status = com.grash.model.enums.Status.COMPLETE OR w.archived = true)")
    boolean isOnClosedOrArchivedWorkOrder(@Param("id") Long id);

    @Query("SELECT COUNT(r) > 0 FROM Request r JOIN r.customers c WHERE c.id = :id "
            + "AND (r.workOrder IS NOT NULL OR r.cancelled = true)")
    boolean isOnApprovedOrRejectedRequest(@Param("id") Long id);

    @Query("SELECT COUNT(a) > 0 FROM Asset a JOIN a.customers c WHERE c.id = :id AND a.archived = true")
    boolean isOnArchivedAsset(@Param("id") Long id);

    @Query("SELECT COUNT(p) > 0 FROM PreventiveMaintenance p JOIN p.customers c WHERE c.id = :id "
            + "AND p.schedule.disabled = true")
    boolean isOnDisabledPreventiveMaintenance(@Param("id") Long id);
}
