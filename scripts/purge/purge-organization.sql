-- Fierabras CMMS: purge of one organization, outside the application (PPB-28).
--
-- Deletes the organization and every row of it, including the rows that have no company_id and so are not deleted
-- in cascade with it: work order revisions and history, meter readings, notifications, user settings and statistics,
-- shift configurations, the subscription and the revision records. Nothing of another organization is changed.
--
-- Run it only with a verified backup and with the backend stopped (see the purge procedure):
--
--   psql -v ON_ERROR_STOP=1 -v company=<company id> -f purge-organization.sql
--
-- It runs in one transaction: on any error nothing is deleted. It ends with a check that no row with the
-- company_id of the organization is left in any table; if one is, everything is rolled back.
-- The scheduler jobs of its preventive maintenances (qrtz_*) are left: they do nothing once the schedule is gone.

BEGIN;

CREATE TEMP TABLE purge_company ON COMMIT DROP AS
    SELECT id FROM company WHERE id = :company;

DO $$
BEGIN
    IF (SELECT count(*) FROM purge_company) <> 1 THEN
        RAISE EXCEPTION 'purge-organization: the company does not exist';
    END IF;
END $$;

-- The rows of the organization that the cascade from company does not reach, kept before they lose their link
CREATE TEMP TABLE purge_user ON COMMIT DROP AS
    SELECT id, user_settings_id, app_stats_id, shift_configuration_id FROM own_user WHERE company_id = :company;

CREATE TEMP TABLE purge_subscription ON COMMIT DROP AS
    SELECT subscription_id AS id FROM company WHERE id = :company AND subscription_id IS NOT NULL;

-- Work order revisions: those of its work orders, and any that cites one of its rows (protected references)
CREATE TEMP TABLE purge_revision ON COMMIT DROP AS
    SELECT r.id, r.rev FROM work_order_aud r
    WHERE r.id IN (SELECT id FROM work_order WHERE company_id = :company)
       OR r.primary_user_id IN (SELECT id FROM purge_user)
       OR r.completed_by_id IN (SELECT id FROM purge_user)
       OR r.team_id IN (SELECT id FROM team WHERE company_id = :company)
       OR r.location_id IN (SELECT id FROM location WHERE company_id = :company)
       OR r.asset_id IN (SELECT id FROM asset WHERE company_id = :company)
       OR r.image_id IN (SELECT id FROM file WHERE company_id = :company)
       OR r.signature_id IN (SELECT id FROM file WHERE company_id = :company)
       OR r.parent_preventive_maintenance_id IN (SELECT id FROM preventive_maintenance WHERE company_id = :company)
       OR r.parent_request_id IN (SELECT id FROM request WHERE company_id = :company)
       OR r.category_id IN (SELECT c.id FROM work_order_category c
                            JOIN company_settings s ON s.id = c.company_settings_id WHERE s.company_id = :company);

-- 1. Historical and technical rows without company_id that cite rows of the organization
DELETE FROM work_order_history
WHERE work_order_id IN (SELECT id FROM work_order WHERE company_id = :company)
   OR user_id IN (SELECT id FROM purge_user);

DELETE FROM work_order_aud r USING purge_revision p WHERE r.id = p.id AND r.rev = p.rev;

DELETE FROM reading WHERE meter_id IN (SELECT id FROM meter WHERE company_id = :company);

DELETE FROM notification WHERE user_id IN (SELECT id FROM purge_user);

-- 2. The organization, and everything that is deleted in cascade with it
DELETE FROM company WHERE id = :company;

-- 3. The rows that only the organization used, now without it
DELETE FROM user_settings WHERE id IN (SELECT user_settings_id FROM purge_user);
DELETE FROM user_app_stats WHERE id IN (SELECT app_stats_id FROM purge_user);
DELETE FROM shift_configuration WHERE id IN (SELECT shift_configuration_id FROM purge_user);
DELETE FROM subscription WHERE id IN (SELECT id FROM purge_subscription);
DELETE FROM revinfo
WHERE rev IN (SELECT rev FROM purge_revision)
  AND NOT EXISTS (SELECT 1 FROM work_order_aud a WHERE a.rev = revinfo.rev);

-- 4. Check: no row with the company_id of the organization is left
DO $$
DECLARE
    target bigint := (SELECT id FROM purge_company);
    item record;
    left_rows bigint;
BEGIN
    FOR item IN SELECT c.table_name FROM information_schema.columns c
                JOIN information_schema.tables t ON t.table_schema = c.table_schema AND t.table_name = c.table_name
                WHERE c.table_schema = current_schema() AND c.column_name = 'company_id'
                  AND t.table_type = 'BASE TABLE'
    LOOP
        EXECUTE format('SELECT count(*) FROM %I WHERE company_id = $1', item.table_name) INTO left_rows USING target;
        IF left_rows > 0 THEN
            RAISE EXCEPTION 'purge-organization: % rows of the company left in %', left_rows, item.table_name;
        END IF;
    END LOOP;
    RAISE NOTICE 'purge-organization: company % purged', target;
END $$;

COMMIT;
