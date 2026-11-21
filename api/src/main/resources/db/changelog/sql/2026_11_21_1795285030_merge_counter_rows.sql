-- P-20 (C-08): one folio counter row per company, before custom_sequence.company_id becomes unique.
-- Run by the changeset 2026_11_21_1795285030_unique_folios-counter_rows and by FolioIntegrationTest.

-- 1. Repeated counter rows of a company are merged: the oldest row keeps the highest value of each counter
UPDATE custom_sequence keep
SET work_order_sequence             = m.work_order_sequence,
    asset_sequence                  = m.asset_sequence,
    preventive_maintenance_sequence = m.preventive_maintenance_sequence,
    location_sequence               = m.location_sequence,
    request_sequence                = m.request_sequence
FROM (SELECT company_id,
             MIN(id)                              AS keep_id,
             MAX(work_order_sequence)             AS work_order_sequence,
             MAX(asset_sequence)                  AS asset_sequence,
             MAX(preventive_maintenance_sequence) AS preventive_maintenance_sequence,
             MAX(location_sequence)               AS location_sequence,
             MAX(request_sequence)                AS request_sequence
      FROM custom_sequence
      GROUP BY company_id
      HAVING COUNT(*) > 1) m
WHERE keep.id = m.keep_id;

DELETE
FROM custom_sequence extra
    USING custom_sequence keep
WHERE keep.company_id = extra.company_id
  AND keep.id < extra.id;

-- 2. Every company gets a counter row. The id comes from the sequence Hibernate used for this table.
INSERT INTO custom_sequence (id, company_id)
SELECT nextval('custom_sequence_seq'), c.id
FROM company c
WHERE NOT EXISTS (SELECT 1 FROM custom_sequence cs WHERE cs.company_id = c.id);
