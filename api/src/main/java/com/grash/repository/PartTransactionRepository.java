package com.grash.repository;

import com.grash.model.PartTransaction;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.Date;
import java.util.List;

public interface PartTransactionRepository extends JpaRepository<PartTransaction, Long> {
    Collection<PartTransaction> findByCompany_Id(Long id);

    Collection<PartTransaction> findByWorkOrder_Id(Long id);

    Collection<PartTransaction> findByPart_Id(Long id);

    Collection<PartTransaction> findByWorkOrder_IdAndPart_Id(Long workOrderId, Long partId);

    @Query("""
                SELECT pt
                FROM PartTransaction pt
                WHERE pt.company.id = :id
                  AND pt.createdAt BETWEEN :start AND :end
                  AND pt.origin IN (com.grash.model.enums.PartTransactionOrigin.CONSUMPTION,
                                    com.grash.model.enums.PartTransactionOrigin.REVERSAL)
            """)
    Collection<PartTransaction> findConsumptionsByCompanyBetween(
            @Param("id") Long id,
            @Param("start") Date start,
            @Param("end") Date end
    );

    List<PartTransaction> findByWorkOrder_IdIn(List<Long> ids);

    @Query("""
            SELECT pt FROM PartTransaction pt
            LEFT JOIN FETCH pt.part
            LEFT JOIN FETCH pt.workOrder
            LEFT JOIN FETCH pt.purchaseOrder
            WHERE pt.company.id = :companyId
            """)
    Page<PartTransaction> findByCompanyForExport(@Param("companyId") Long companyId, Pageable pageable);

    @Query(value = """
            SELECT a.id, a.name, COALESCE(SUM(p.cost * -pt.quantity), 0) AS total_cost
            FROM part_transaction pt
            JOIN work_order wo ON pt.work_order_id = wo.id
            JOIN asset a ON wo.asset_id = a.id
            JOIN part p ON pt.part_id = p.id
            WHERE a.company_id = :companyId
              AND wo.created_at BETWEEN :start AND :end
              AND pt.origin IN ('CONSUMPTION', 'REVERSAL')
            GROUP BY a.id, a.name
            ORDER BY total_cost DESC
            LIMIT :limit
            """, nativeQuery = true)
    List<Object[]> findTopNAssetsByConsumption(@Param("companyId") Long companyId,
                                               @Param("start") Date start,
                                               @Param("end") Date end,
                                               @Param("limit") int limit);

    /**
     * P-16b (D-3): for each part that is not demo, what each demo work order or demo purchase order of the company
     * added to or took from its stock: part, work order, purchase order, net quantity; in the order of the parts.
     */
    @Query(value = """
            SELECT t.part_id, t.work_order_id, t.purchase_order_id, SUM(t.quantity)
            FROM part_transaction t
            JOIN part p ON p.id = t.part_id
            LEFT JOIN work_order w ON w.id = t.work_order_id
            LEFT JOIN purchase_order o ON o.id = t.purchase_order_id
            WHERE t.company_id = :companyId AND NOT p.is_demo AND (w.is_demo OR o.is_demo)
            GROUP BY t.part_id, t.work_order_id, t.purchase_order_id
            ORDER BY t.part_id, t.work_order_id, t.purchase_order_id
            """, nativeQuery = true)
    List<Object[]> findNetOfDemoDocumentsOnPartsNotDemo(@Param("companyId") Long companyId);

    /**
     * P-16b (D-3, PPB-28): the movements of parts that are not demo stop citing a demo work order, which the demo
     * purge deletes; their description keeps its folio.
     */
    @Modifying
    @Query(value = """
            UPDATE part_transaction t SET work_order_id = NULL,
                description = trim(COALESCE(t.description, '') || ' [demo work order '
                    || COALESCE(w.custom_id, '#' || w.id) || ' purged]')
            FROM work_order w, part p
            WHERE w.id = t.work_order_id AND w.is_demo AND p.id = t.part_id AND NOT p.is_demo
              AND t.company_id = :companyId
            """, nativeQuery = true)
    void detachFromDemoWorkOrders(@Param("companyId") Long companyId);

    /**
     * P-16b (D-3, PPB-28): as {@link #detachFromDemoWorkOrders}, for demo purchase orders; the description keeps
     * their name and identifier.
     */
    @Modifying
    @Query(value = """
            UPDATE part_transaction t SET purchase_order_id = NULL,
                description = trim(COALESCE(t.description, '') || ' [demo purchase order ' || o.name || ' (#'
                    || o.id || ') purged]')
            FROM purchase_order o, part p
            WHERE o.id = t.purchase_order_id AND o.is_demo AND p.id = t.part_id AND NOT p.is_demo
              AND t.company_id = :companyId
            """, nativeQuery = true)
    void detachFromDemoPurchaseOrders(@Param("companyId") Long companyId);

    /**
     * P-16b (D-3, D-P16b-1): deletes the movements of the demo parts that belong to the demo data: demo movements
     * and those that cite a demo work order or purchase order. Any other movement of a demo part is kept and rejects
     * the purge (D-P16-6).
     */
    @Modifying
    @Query(value = """
            DELETE FROM part_transaction t USING part p
            WHERE p.id = t.part_id AND p.is_demo AND t.company_id = :companyId
              AND (t.is_demo
                   OR t.work_order_id IN (SELECT id FROM work_order WHERE is_demo AND company_id = :companyId)
                   OR t.purchase_order_id IN (SELECT id FROM purchase_order WHERE is_demo AND company_id = :companyId))
            """, nativeQuery = true)
    void deleteDemoMovements(@Param("companyId") Long companyId);
}
