-- P-21 (C-10): one labor timer (labor.logged) per technician and work order, before uq_labor_timer.
-- Run by the changeset 2026_11_26_1795743983_labor_timer_unique and by LaborTimerIntegrationTest.
--
-- Of the repeated timers of a technician on a work order, the running one with the lowest id stays the timer; when
-- none runs, the one with the lowest id. The others become additional time entries: logged = false and stopped
-- (status 0), with their duration and hourly rate unchanged, so the time and cost of the work order do not change
-- and no row is deleted. The time a repeated timer had been running without recording it is not added: it is the
-- time the double submit counted twice.
UPDATE labor repeated
SET logged = false,
    status = 0
FROM (SELECT id,
             ROW_NUMBER() OVER (PARTITION BY work_order_id, assigned_to_id
                 ORDER BY CASE WHEN status = 1 THEN 0 ELSE 1 END, id) AS position
      FROM labor
      WHERE logged
        AND assigned_to_id IS NOT NULL) ranked
WHERE repeated.id = ranked.id
  AND ranked.position > 1;
