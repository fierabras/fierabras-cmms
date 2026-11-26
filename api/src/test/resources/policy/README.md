# Extract of the persistence and deletion policy

These files are an extract, in English, of the persistence and deletion policy of the fork (PPB) and of its two
tables: the classification of the tables and the rules of the foreign keys. The policy stays the source: these
files are generated from it and are never edited by hand, except `exceptions.csv`.

| File | One row per | Columns |
|---|---|---|
| `tables.csv` | table of the schema | `table`, `class` |
| `foreign-keys.csv` | foreign key, identified by its child table and its first child column | `child_table`, `child_column`, `parent_table`, `target_rule`, `relation`, `exception` |
| `exceptions.csv` | approved exception `EX-nn` (PPB-29) | `id`, `rule`, `target`, `reason`, `approved_on`, `valid_until` |

Values:

- `class`: `ORGANIZATION`, `CONFIGURATION`, `HISTORICAL_RECORD`, `TECHNICAL_RECORD`, `TEMPLATE`, `CATALOG`, `FILE`,
  `ROOT_ENTITY`, `COMPOSITION_CHILD`, `ASSOCIATION_TABLE`.
- `target_rule`: `NO ACTION`, `CASCADE`, `SET NULL`, `RESTRICT`.
- `relation`: `COMPOSITION`, `REFERENCE`, `ASSOCIATION`, `TECHNICAL`.
- `exception`: empty, or the `EX-nn` that allows a rule other than the one of its case (PPB-21).

The tests in `com.grash.policy` (`PolicyExtractTest`) and `com.grash.integration.SchemaPolicyIntegrationTest`
compare the catalog of the database migrated by Liquibase with these files. A changeset that adds a table or a
foreign key needs its row in the policy first; then the extract is generated again.
