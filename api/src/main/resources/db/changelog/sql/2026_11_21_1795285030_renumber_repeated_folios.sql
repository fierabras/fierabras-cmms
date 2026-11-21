-- P-20 (C-08): no repeated folio per company and type, before (company_id, custom_id) becomes unique.
-- Needs one counter row per company (sql/2026_11_21_1795285030_merge_counter_rows.sql runs first).
-- Run by the changeset 2026_11_21_1795285030_unique_folios-folios and by FolioIntegrationTest.
-- No record is deleted and no folio that is not repeated changes.
DO
$body$
DECLARE
    t RECORD;
BEGIN
    FOR t IN SELECT *
             FROM (VALUES ('work_order', 'WO', 'work_order_sequence'),
                          ('asset', 'A', 'asset_sequence'),
                          ('location', 'L', 'location_sequence'),
                          ('request', 'R', 'request_sequence'),
                          ('preventive_maintenance', 'PM', 'preventive_maintenance_sequence')) AS v(tbl, prefix, counter)
        LOOP
            -- 1. Each counter goes above the highest folio in the product format (prefix and digits), so that
            --    the new folios below, and the next ones, never take an existing folio
            EXECUTE format(
                    'UPDATE custom_sequence cs SET %1$I = m.max_number + 1
                     FROM (SELECT company_id, MAX(substr(custom_id, %3$s)::bigint) AS max_number
                           FROM %2$I WHERE custom_id ~ %4$L GROUP BY company_id) m
                     WHERE cs.company_id = m.company_id AND cs.%1$I <= m.max_number',
                    t.counter, t.tbl, length(t.prefix) + 1, '^' || t.prefix || '[0-9]{1,18}$');
            -- 2. In each group of repeated folios the oldest record keeps the folio; the others take new folios
            --    from the counter, in the format of the product (at least six digits, as %06d in Java)
            EXECUTE format(
                    'WITH ranked AS (SELECT id, company_id,
                                            ROW_NUMBER() OVER (PARTITION BY company_id, custom_id
                                                               ORDER BY created_at NULLS LAST, id) AS position
                                     FROM %2$I WHERE custom_id IS NOT NULL),
                          extra AS (SELECT id, company_id,
                                           ROW_NUMBER() OVER (PARTITION BY company_id ORDER BY id) AS k
                                    FROM ranked WHERE position > 1),
                          renumbered AS (UPDATE %2$I r
                                         SET custom_id = %3$L || lpad((cs.%1$I + e.k - 1)::text,
                                                                     greatest(6, length((cs.%1$I + e.k - 1)::text)),
                                                                     ''0'')
                                         FROM extra e JOIN custom_sequence cs ON cs.company_id = e.company_id
                                         WHERE r.id = e.id
                                         RETURNING r.company_id)
                     UPDATE custom_sequence cs SET %1$I = cs.%1$I + n.total
                     FROM (SELECT company_id, COUNT(*) AS total FROM renumbered GROUP BY company_id) n
                     WHERE cs.company_id = n.company_id',
                    t.counter, t.tbl, t.prefix);
        END LOOP;
END
$body$;
