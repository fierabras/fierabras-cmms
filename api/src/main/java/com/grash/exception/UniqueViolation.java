package com.grash.exception;

import org.postgresql.util.PSQLException;
import org.postgresql.util.ServerErrorMessage;

import java.sql.SQLException;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Optional;
import java.util.Set;

/**
 * A unique constraint that rejected a statement (SQLState 23505), found anywhere in the causes of a failure. The
 * twin of {@link ForeignKeyViolation}.
 *
 * @param constraint the name of the constraint, as reported by PostgreSQL; {@code null} if the driver did not
 *                   report it
 */
public record UniqueViolation(String constraint) {

    private static final String UNIQUE_VIOLATION = "23505";

    public static Optional<UniqueViolation> in(Throwable failure) {
        Set<Throwable> seen = Collections.newSetFromMap(new IdentityHashMap<>());
        for (Throwable cause = failure; cause != null && seen.add(cause); cause = cause.getCause()) {
            // A batch reports the statement that failed as the next exception of the batch exception
            for (SQLException sqlException = cause instanceof SQLException first ? first : null;
                 sqlException != null; sqlException = sqlException.getNextException()) {
                if (UNIQUE_VIOLATION.equals(sqlException.getSQLState())) {
                    return Optional.of(new UniqueViolation(constraintOf(sqlException)));
                }
            }
        }
        return Optional.empty();
    }

    private static String constraintOf(SQLException exception) {
        if (exception instanceof PSQLException psqlException) {
            ServerErrorMessage message = psqlException.getServerErrorMessage();
            return message == null ? null : message.getConstraint();
        }
        return null;
    }
}
