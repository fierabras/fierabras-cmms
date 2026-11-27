package com.grash.repository;

import com.grash.model.Schedule;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

import java.util.Collection;
import java.util.Optional;

public interface ScheduleRepository extends JpaRepository<Schedule, Long> {
    @Query("SELECT s from Schedule s where s.preventiveMaintenance.company.id = :x ")
    Collection<Schedule> findByCompany_Id(@Param("x") Long id);

    void deleteByPreventiveMaintenanceCompany_IdAndIsDemoTrue(Long companyId);

    /**
     * P-24 (E-1): locks the row of the schedule until the end of the transaction, so that two runs of one
     * occurrence take turns. Empty if the schedule does not exist.
     */
    @Query(value = "SELECT id FROM schedule WHERE id = :id FOR UPDATE", nativeQuery = true)
    Optional<Long> lockById(@Param("id") Long id);

    @Query(value = "SELECT s.* FROM schedule s " +
            "LEFT JOIN (" +
            "    SELECT parent_preventive_maintenance_id, " +
            "           COUNT(*) as total, " +
            "           MAX(first_time_to_react) as last_react " +
            "    FROM (" +
            "        SELECT parent_preventive_maintenance_id, first_time_to_react, " +
            "               ROW_NUMBER() OVER (PARTITION BY parent_preventive_maintenance_id ORDER BY created_at " +
            "DESC) as rn " +
            "        FROM work_order " +
            "    ) ranked_wo " +
            "    WHERE rn <= 10 " +
            "    GROUP BY parent_preventive_maintenance_id " +
            ") wo_stats ON s.preventive_maintenance_id = wo_stats.parent_preventive_maintenance_id " +
            "WHERE s.disabled = false " +
            "AND (s.ends_on IS NULL OR s.ends_on > CURRENT_DATE) " +
            "AND (wo_stats.total IS NULL OR wo_stats.total < 10 OR wo_stats.last_react IS NOT NULL)", nativeQuery =
            true)
    Collection<Schedule> findByActive();

    @Modifying
    @Query("""
                update Schedule s
                set s.disabled = true
                where s.preventiveMaintenance.company.id = :companyId
            """)
    void updateDisabledTrueByCompanyId(@Param("companyId") Long companyId);
}
