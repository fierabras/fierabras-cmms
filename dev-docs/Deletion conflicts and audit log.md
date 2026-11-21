# Deletion conflicts and audit log

How a deletion is rejected when something still uses the record, how it is recorded in the audit log, how the web
app shows the rejection, and how to test it. The mechanism was introduced with five deletions (part, API key,
webhook endpoint, role, asset); every other deletion adopts it the same way.

## The pieces

| Piece | Where | What it does |
|---|---|---|
| `ConflictException` | `api/.../exception/` | 409 with a stable `code`, an English `message` and, for `<ENTITY>_IN_USE`, `blockingTypes` |
| `ConflictResponse` | `api/.../dto/` | Body of that 409: `success`, `message`, `code` and `blockingTypes` (only when there are types) |
| `ForeignKeyViolation` | `api/.../exception/` | Finds a foreign key violation (SQLState 23503) in the causes of a failure, with the referencing table |
| `DeletionService` | `api/.../service/` | Checks the dependents, deletes, translates a foreign key violation, writes the audit record |
| `DeletionRule` | `api/.../service/` | The `<ENTITY>_IN_USE` code and message of an entity, and its dependents |
| `AuditLogService` | `api/.../service/` | Writes a record (only inside a transaction) and serves `GET /audit-logs` |
| `EntityType` | `api/.../model/enums/` | Types of the audit log and of `blockingTypes`, each with its table |
| `conflictMessage` | `frontend/src/utils/conflicts.ts` | Turns the 409 into a translated message |

## Reject a deletion with 409

1. Add the entity, and each type that can block it, to `EntityType` (with its table) if they are not there.
2. In the service, make the deletion one transaction and call `DeletionService`:

   ```java
   public static final String LOCATION_IN_USE = "LOCATION_IN_USE";
   private static final String LOCATION_IN_USE_MESSAGE = "This location is used by other records. Archive it "
           + "instead of deleting it";

   @Transactional
   public void delete(Location location, User actor) {
       deletionService.delete(
               new AuditTarget(EntityType.LOCATION, location.getId(), location.getCustomId(), location.getName(),
                       location.getCompany().getId()),
               actor, AuditSource.API,
               DeletionRule.inUse(LOCATION_IN_USE, LOCATION_IN_USE_MESSAGE)
                       .blockedBy(EntityType.ASSET, () -> assetRepository.existsByLocation_Id(location.getId()))
                       .blockedBy(EntityType.LOCATION, () -> locationRepository.existsByParentLocation_Id(location.getId())),
               () -> locationRepository.deleteById(location.getId()));
   }
   ```

3. Make each dependent's foreign key a protected reference (`NO ACTION`) with an index, in a **new** changeset.
   Never edit an existing changeset.
4. Keep the order of the checks: the controller answers 404 (not found) and 403 (no permission) before calling the
   service; the 409 comes last.

What happens then:

| Situation | Answer |
|---|---|
| A dependent exists when the deletion starts | 409 `<ENTITY>_IN_USE` with every blocking type found |
| A dependent appears between the check and the deletion | Its foreign key rejects the deletion; 409 `<ENTITY>_IN_USE` with the type whose table is the referencing table |
| A foreign key of a table that is not in the rule rejects it | 409 `<ENTITY>_IN_USE` without `blockingTypes` |
| A deletion without a rule (`rule` is `null`, or not migrated yet) is rejected by a foreign key | 409 `RECORD_IN_USE`, without `blockingTypes`. The global handler answers the same for any `DELETE` request that fails on a foreign key |

```json
{"success":false,"message":"This asset is used by other records. Archive it instead of deleting it","code":"ASSET_IN_USE","blockingTypes":["WORK_ORDER"]}
```

The body never carries SQL, constraint names, counts or identifiers. A successful answer never has `blockingTypes`.
For a 409 that is not about dependents (for example a closed record), throw `new ConflictException(CODE, message)`.

## Record an operation in the audit log

`DeletionService` records the deletion it performs. To record another operation (retire, restore, reopen, purge),
call `AuditLogService.record(target, actor, action, source, cascadeRows, storageObjects)` from the transactional
method that performs it.

- `record` uses `Propagation.MANDATORY`: called outside a transaction, it fails. The record and the operation are
  committed or rolled back together; a rejected or failed operation leaves no record.
- Record the operation the user asked for, not its internal steps. Rows deleted in cascade go in the summary of
  their parent and get no record of their own.
- `actor` is `null` for a job. `source` is `API`, `IMPORT`, `JOB` or `DEMO_PURGE`.
- Never put a secret in `folio` or `name`: an API key is recorded by its label, a webhook endpoint by its event,
  never by its URL.
- Mail, webhooks, notifications and deletion of stored objects must run only after the commit (`AfterCommit`).

**The cascade summary depends on `track_counts`.** `DeletionService` measures the rows deleted in cascade with the
statistics of the current transaction (`pg_stat_xact_user_tables`), which PostgreSQL only keeps when `track_counts`
is on. It is on by default; if a server turns it off, deletions still work and are still recorded, but the summary
is empty. Check it with `SHOW track_counts;`.

The query is `GET /audit-logs?from&to&action&userId&page&size`: view of `SETTINGS`, own organization only, newest
first. Without parameters it applies the last 30 days, every action and every user, and returns the filter applied.
An invalid filter answers 400 `AUDIT_FILTER_INVALID`.

## Show the rejection in the web app

Use `conflictMessage` where the deletion fails, as the asset detail does:

```ts
import { conflictMessage } from '../../../../utils/conflicts';

const onDeleteFailure = (err) =>
  showSnackBar(conflictMessage(err, t, t('location_remove_failure')), 'error');
```

Add the code to `conflictKeys` in `utils/conflicts.ts` (`LOCATION_IN_USE: 'location_in_use'`) and the keys to
`en.ts` and `es.ts` only (see `frontend/src/i18n/translations/AGENTS.md`): the message of the code and
`entity_type_<TYPE>` for each new type. The user reads, for example, "This asset is in use and cannot be deleted.
Archive it instead. It is used by: Work order."

## Test it

Extend `AbstractIntegrationTest` (see [Run tests](Run%20tests.md)); `DeletionConflictIntegrationTest` and
`AuditLogIntegrationTest` are the examples. `TestRecords` creates the usual records.

| Case | How |
|---|---|
| In use | Create the dependent, call `DELETE` as A: 409, the code, `blockingTypes`, nothing changed, no audit record |
| No permission | Same data, call as T: 403 and no mention of dependents |
| Dependent added between check and deletion | `faults.runBefore("<table>", Operation.DELETE, "<SQL that inserts the dependent>")`: 409, never 500 |
| Allowed | 200 with `{"success":true,"message":"Deleted successfully"}` and one audit record; check `cascade_rows` |
| Audit write fails | `faults.failOn("audit_log", Operation.INSERT)`: the entity still exists and no double received a call |
| Schema | Query `pg_constraint` (`confdeltype = 'a'` is `NO ACTION`) and `pg_index` for the index on the column |

Read the audit log in a test with SQL on `audit_log`, for example
`SELECT entity_type, cascade_rows::text FROM audit_log WHERE company_id = ?`.
