package dev.reconcilekit.api;

import java.sql.SQLException;
import java.util.ArrayDeque;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Set;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.dao.TransientDataAccessException;
import org.springframework.transaction.CannotCreateTransactionException;
import org.springframework.transaction.TransactionSystemException;

/** Inspect the full exception graph, including the original failure preserved during rollback. */
public final class DatabaseFailures {
    private DatabaseFailures() {}
    public static boolean retryable(Throwable error) {
        Set<Throwable> visited = Collections.newSetFromMap(new IdentityHashMap<>());
        var pending = new ArrayDeque<Throwable>();
        if (error != null) pending.add(error);
        while (!pending.isEmpty()) {
            Throwable cause = pending.removeFirst();
            if (!visited.add(cause)) continue;
            if (cause instanceof TransientDataAccessException || cause instanceof DataAccessResourceFailureException
                    || cause instanceof CannotCreateTransactionException) return true;
            if (cause instanceof SQLException sql) {
                String state = sql.getSQLState();
                if (state != null && (state.startsWith("08") || state.equals("55P03") || state.equals("40P01") || state.equals("40001")
                        || state.equals("57P01") || state.equals("57P02") || state.equals("57P03"))) return true;
                if (sql.getNextException() != null) pending.addLast(sql.getNextException());
            }
            if (cause instanceof TransactionSystemException transaction) {
                // Spring stores the application exception separately if rollback itself fails.
                if (transaction.getApplicationException() != null) pending.addLast(transaction.getApplicationException());
                if (transaction.getOriginalException() != null) pending.addLast(transaction.getOriginalException());
            }
            if (cause.getCause() != null) pending.addLast(cause.getCause());
            for (Throwable suppressed : cause.getSuppressed()) pending.addLast(suppressed);
        }
        return false;
    }
}
