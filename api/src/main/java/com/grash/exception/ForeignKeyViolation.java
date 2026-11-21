package com.grash.exception;

import org.postgresql.util.PSQLException;
import org.postgresql.util.ServerErrorMessage;

import java.sql.SQLException;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Optional;
import java.util.Set;

/**
 * A foreign key that rejected a statement (SQLState 23503), found anywhere in the causes of a failure.
 *
 * @param referencingTable the table whose rows still point to the deleted one, as reported by PostgreSQL;
 *                         {@code null} if the driver did not report it
 */
public record ForeignKeyViolation(String referencingTable) {

    private static final String FOREIGN_KEY_VIOLATION = "23503";

    public static Optional<ForeignKeyViolation> in(Throwable failure) {
        Set<Throwable> seen = Collections.newSetFromMap(new IdentityHashMap<>());
        for (Throwable cause = failure; cause != null && seen.add(cause); cause = cause.getCause()) {
            // A batch reports the statement that failed as the next exception of the batch exception
            for (SQLException sqlException = cause instanceof SQLException first ? first : null;
                 sqlException != null; sqlException = sqlException.getNextException()) {
                if (FOREIGN_KEY_VIOLATION.equals(sqlException.getSQLState())) {
                    return Optional.of(new ForeignKeyViolation(tableOf(sqlException)));
                }
            }
        }
        return Optional.empty();
    }

    private static String tableOf(SQLException exception) {
        if (exception instanceof PSQLException psqlException) {
            ServerErrorMessage message = psqlException.getServerErrorMessage();
            return message == null ? null : message.getTable();
        }
        return null;
    }
}
