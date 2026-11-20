package com.grash.integration.support;

import org.springframework.jdbc.core.JdbcTemplate;

import java.util.UUID;
import java.util.regex.Pattern;

/**
 * Makes a database operation fail, or interleaves a statement, at a chosen point, with temporary PostgreSQL
 * triggers. Nothing in {@code src/main} is touched: the fault is in the database the tests own.
 * <p>
 * Every fault is a row-level trigger on one table and one operation. {@link Fault#hits()} counts the rows that
 * reached it, so a test can prove that the fault point was actually crossed. Remove the faults with
 * {@link Fault#close()} or, in an {@code @AfterEach}, {@link #removeAll()}. The injected error message starts with
 * {@value #MESSAGE}.
 * <pre>{@code
 * try (Fault fault = faults.failOn("part_quantity", Operation.INSERT)) {
 *     Response response = api.call(...);   // fails when it inserts the row; its transaction rolls back
 *     assertEquals(1, fault.hits());
 * }
 * }</pre>
 */
public final class FaultInjector {

    public static final String MESSAGE = "P-00 injected fault";

    private static final String PREFIX = "p00_fault_";
    private static final Pattern IDENTIFIER = Pattern.compile("[a-z_][a-z0-9_]*");

    public enum Operation {
        INSERT, UPDATE, DELETE
    }

    public interface Fault extends AutoCloseable {
        /**
         * Rows that reached the fault point so far.
         */
        int hits();

        @Override
        void close();
    }

    private final JdbcTemplate jdbcTemplate;

    public FaultInjector(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    /**
     * The first {@code operation} on a row of {@code table} fails before it is applied.
     */
    public Fault failOn(String table, Operation operation) {
        return failOn(table, operation, 0);
    }

    /**
     * The first {@code rowsBeforeFailure} rows pass; the next one fails before it is applied ("fails halfway").
     */
    public Fault failOn(String table, Operation operation, int rowsBeforeFailure) {
        return rowTrigger(table, operation,
                "IF nextval('{fault}_seq') > " + rowsBeforeFailure + " THEN RAISE EXCEPTION '" + MESSAGE + ": "
                        + operation + " on " + table + "'; END IF;");
    }

    /**
     * The statements succeed, and the transaction fails when it commits ("fails before confirming"). Without an
     * open transaction, the commit is the end of the statement.
     */
    public Fault failAtCommit(String table, Operation operation) {
        String name = create(table, "PERFORM nextval('{fault}_seq'); RAISE EXCEPTION '" + MESSAGE + ": commit after "
                + operation + " on " + table + "';");
        jdbcTemplate.execute("CREATE CONSTRAINT TRIGGER " + name + " AFTER " + operation + " ON " + table
                + " DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION " + name + "()");
        return fault(name, table);
    }

    /**
     * Runs {@code sql} once, in the same transaction, just before the first {@code operation} on a row of
     * {@code table}. Simulates a concurrent change between a check and the operation.
     */
    public Fault runBefore(String table, Operation operation, String sql) {
        return rowTrigger(table, operation,
                "IF nextval('{fault}_seq') = 1 THEN EXECUTE '" + sql.replace("'", "''") + "'; END IF;");
    }

    /**
     * Drops every fault created by any {@code FaultInjector}.
     */
    public void removeAll() {
        String like = PREFIX.replace("_", "\\_") + "%";
        jdbcTemplate.queryForList("SELECT tgname, tgrelid::regclass::text AS tbl FROM pg_trigger WHERE tgname LIKE ?",
                        like)
                .forEach(row -> jdbcTemplate.execute("DROP TRIGGER IF EXISTS " + row.get("tgname") + " ON "
                        + row.get("tbl")));
        jdbcTemplate.queryForList("SELECT proname FROM pg_proc WHERE proname LIKE ?", String.class, like)
                .forEach(name -> jdbcTemplate.execute("DROP FUNCTION IF EXISTS " + name + "()"));
        jdbcTemplate.queryForList("SELECT sequencename FROM pg_sequences WHERE sequencename LIKE ?", String.class, like)
                .forEach(name -> jdbcTemplate.execute("DROP SEQUENCE IF EXISTS " + name));
    }

    private Fault rowTrigger(String table, Operation operation, String check) {
        String name = create(table, check + " IF TG_OP = 'DELETE' THEN RETURN OLD; END IF; RETURN NEW;");
        jdbcTemplate.execute("CREATE TRIGGER " + name + " BEFORE " + operation + " ON " + table
                + " FOR EACH ROW EXECUTE FUNCTION " + name + "()");
        return fault(name, table);
    }

    /**
     * Creates the counter sequence and the trigger function; {@code {fault}} in {@code body} is the fault name.
     */
    private String create(String table, String body) {
        validate(table);
        String name = PREFIX + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
        jdbcTemplate.execute("CREATE SEQUENCE " + name + "_seq");
        jdbcTemplate.execute("CREATE FUNCTION " + name + "() RETURNS trigger LANGUAGE plpgsql AS $p00$ BEGIN "
                + body.replace("{fault}", name) + " END $p00$");
        return name;
    }

    private void validate(String table) {
        if (!IDENTIFIER.matcher(table).matches()
                || !Boolean.TRUE.equals(jdbcTemplate.queryForObject("SELECT to_regclass(?) IS NOT NULL",
                Boolean.class, table))) {
            throw new IllegalArgumentException("not an existing table: " + table);
        }
    }

    private Fault fault(String name, String table) {
        return new Fault() {
            @Override
            public int hits() {
                Integer hits = jdbcTemplate.queryForObject(
                        "SELECT CASE WHEN is_called THEN last_value ELSE 0 END FROM " + name + "_seq", Integer.class);
                return hits == null ? 0 : hits;
            }

            @Override
            public void close() {
                jdbcTemplate.execute("DROP TRIGGER IF EXISTS " + name + " ON " + table);
                jdbcTemplate.execute("DROP FUNCTION IF EXISTS " + name + "()");
                jdbcTemplate.execute("DROP SEQUENCE IF EXISTS " + name + "_seq");
            }
        };
    }
}
