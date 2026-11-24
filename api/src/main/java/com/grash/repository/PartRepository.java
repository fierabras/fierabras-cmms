package com.grash.repository;

import com.grash.model.Part;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface PartRepository extends JpaRepository<Part, Long>, JpaSpecificationExecutor<Part> {
    Collection<Part> findByCompany_Id(@Param("x") Long id);

    Optional<Part> findByIdAndCompany_Id(Long id, Long companyId);

    List<Part> findByIdInAndCompany_Id(List<Long> ids, Long companyId);

    Optional<Part> findByNameIgnoreCaseAndCompany_Id(String name, Long companyId);

    @Query("SELECT p FROM Part p " +
            "LEFT JOIN FETCH p.category " +
            "LEFT JOIN FETCH p.image " +
            "WHERE p.company.id = :companyId")
    Page<Part> findByCompanyForExport(@Param("companyId") Long companyId, Pageable pageable);

    Optional<Part> findByBarcodeAndCompany_Id(String barcode, Long companyId);

    void deleteByCompany_IdAndIsDemoTrue(Long companyId);

    @Query("SELECT CASE WHEN COUNT(p) > :threshold THEN true ELSE false END " +
            "FROM Part p WHERE p.company.id = :companyId")
    boolean hasMoreThan(@Param("companyId") Long companyId, @Param("threshold") Long threshold);

    /**
     * P-16: whether a line of a work order cites the part.
     */
    @Query("SELECT COUNT(q) > 0 FROM PartQuantity q WHERE q.part.id = :id AND q.workOrder IS NOT NULL")
    boolean isOnAWorkOrder(@Param("id") Long id);

    /**
     * P-16: whether a line of a purchase order cites the part.
     */
    @Query("SELECT COUNT(q) > 0 FROM PartQuantity q WHERE q.part.id = :id AND q.purchaseOrder IS NOT NULL")
    boolean isOnAPurchaseOrder(@Param("id") Long id);

    @Query("SELECT COUNT(a) > 0 FROM Part p JOIN p.assets a WHERE p.id = :id AND a.archived = true")
    boolean isOnArchivedAsset(@Param("id") Long id);

    @Query("SELECT COUNT(pm) > 0 FROM Part p JOIN p.preventiveMaintenances pm WHERE p.id = :id "
            + "AND pm.schedule.disabled = true")
    boolean isOnDisabledPreventiveMaintenance(@Param("id") Long id);
}
