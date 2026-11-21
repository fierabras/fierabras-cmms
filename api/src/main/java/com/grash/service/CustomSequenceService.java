package com.grash.service;

import com.grash.model.Company;
import jakarta.persistence.EntityManager;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import jakarta.transaction.Transactional;

/**
 * Hands out the folio numbers of a company: one counter per folio type, all in the single {@code custom_sequence}
 * row of the company (P-20).
 * <p>
 * Each number is taken with one statement that creates the row on first use ({@code company_id} is unique) and
 * otherwise increments only its own counter. The row stays locked until the transaction of the creation ends, so
 * two creations of a company never get the same number, and a creation that fails gives its number back.
 */
@Service
@RequiredArgsConstructor
public class CustomSequenceService {

    private enum Counter {
        WORK_ORDER("work_order_sequence"),
        ASSET("asset_sequence"),
        PREVENTIVE_MAINTENANCE("preventive_maintenance_sequence"),
        LOCATION("location_sequence"),
        REQUEST("request_sequence");

        /**
         * A counter holds the next number to hand out (1 in a new row). A new row starts this counter at 2 and
         * returns 1. The id comes from the sequence that Hibernate used for this table.
         */
        private final String nextNumberSql;

        Counter(String column) {
            this.nextNumberSql = "INSERT INTO custom_sequence (id, company_id, " + column + ") "
                    + "VALUES (nextval('custom_sequence_seq'), ?, 2) "
                    + "ON CONFLICT (company_id) DO UPDATE SET " + column + " = custom_sequence." + column + " + 1 "
                    + "RETURNING " + column + " - 1";
        }
    }

    private final JdbcTemplate jdbcTemplate;
    private final EntityManager entityManager;

    @Transactional
    public Long getNextWorkOrderSequence(Company company) {
        return next(Counter.WORK_ORDER, company);
    }

    @Transactional
    public Long getNextAssetSequence(Company company) {
        return next(Counter.ASSET, company);
    }

    @Transactional
    public Long getNextPreventiveMaintenanceSequence(Company company) {
        return next(Counter.PREVENTIVE_MAINTENANCE, company);
    }

    @Transactional
    public Long getNextLocationSequence(Company company) {
        return next(Counter.LOCATION, company);
    }

    @Transactional
    public Long getNextRequestSequence(Company company) {
        return next(Counter.REQUEST, company);
    }

    private Long next(Counter counter, Company company) {
        // The statement bypasses Hibernate: pending changes, such as a company created in this transaction, are
        // written first, as Hibernate did when the counter was an entity
        entityManager.flush();
        return jdbcTemplate.queryForObject(counter.nextNumberSql, Long.class, company.getId());
    }
}
