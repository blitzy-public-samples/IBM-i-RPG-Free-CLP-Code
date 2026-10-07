package com.democorp.customermaster.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;

import java.sql.BatchUpdateException;
import java.sql.SQLException;
import java.time.Duration;
import java.util.Optional;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.UncategorizedSQLException;

/**
 * Specifies {@link ProblemFactory#findSqlState(Throwable)}, the server-side counterpart of SQLProblem's
 * {@code GET DIAGNOSTICS ... RETURNED_SQLSTATE} [Service_Pgms/SRV_SQL.SQLRPGLE:36-39]. Both mandatory
 * 500 DEM9999 ERROR lines log its result beside the {@code errorId}, so a state the driver reported
 * anywhere behind the failure must be found.
 *
 * <p><b>What is walked.</b> Every throwable's {@link Throwable#getCause()} and every
 * {@link SQLException}'s {@link SQLException#getNextException()}, breadth-first, the next exception
 * queued ahead of the cause. The first non-blank state in that order wins, trimmed, and an
 * {@code SQLException}'s own state comes before anything it links to. Each throwable is inspected at
 * most once by identity, so cycles through either link end, and at most {@value #NODE_BOUND} throwables
 * are inspected.
 *
 * <p><b>Cycles.</b> {@link LinkedFailure} and {@link LinkedSqlException} override the two links so a
 * test can close a loop that the JDK setters refuse or cannot express; every cyclic case runs under a
 * preemptive timeout, so a walk that failed to end fails the test instead of hanging the build.
 *
 * <p>Pure JUnit 5 and AssertJ: no Spring context, no database, no Docker.
 */
@DisplayName("ProblemFactory.findSqlState: SQLSTATE for the 500 ERROR log line")
final class ProblemFactorySqlStateTest {

    /** The number of throwables {@code findSqlState} inspects at most; mirrors its private bound. */
    private static final int NODE_BOUND = 20;

    /** How long a cyclic walk may take before the test declares it non-terminating. */
    private static final Duration CYCLE_TIMEOUT = Duration.ofSeconds(5);

    @Test
    @DisplayName("null yields empty")
    void nullYieldsEmpty() {
        assertThat(ProblemFactory.findSqlState(null)).isEmpty();
    }

    @Test
    @DisplayName("a failure with no SQLException behind it yields empty")
    void noSqlExceptionYieldsEmpty() {
        RuntimeException failure = new IllegalStateException("outer", new IllegalArgumentException("inner"));

        assertThat(ProblemFactory.findSqlState(failure)).isEmpty();
    }

    @Nested
    @DisplayName("own state and cause chain")
    final class OwnStateAndCauses {

        @Test
        @DisplayName("an SQLException's own state is returned")
        void ownState() {
            assertThat(ProblemFactory.findSqlState(new SQLException("duplicate", "23505"))).contains("23505");
        }

        @Test
        @DisplayName("the returned state is trimmed")
        void stateIsTrimmed() {
            assertThat(ProblemFactory.findSqlState(new SQLException("lock", "  55P03 "))).contains("55P03");
        }

        @Test
        @DisplayName("a state on the cause behind a Spring DataAccessException is returned")
        void stateBehindDataAccessException() {
            SQLException driver = new SQLException("duplicate key", "23505");
            RuntimeException failure = new RuntimeException("write failed",
                    new DataIntegrityViolationException("insert", driver));

            assertThat(ProblemFactory.findSqlState(failure)).contains("23505");
        }

        @Test
        @DisplayName("null and blank states are skipped for a stateful cause")
        void blankStatesSkipped() {
            SQLException stateful = new SQLException("too long", "22001");
            SQLException blank = new SQLException("blank", "   ", stateful);
            SQLException empty = new SQLException("empty", "", blank);
            SQLException none = new SQLException("none", (String) null, empty);

            assertThat(ProblemFactory.findSqlState(none)).contains("22001");
        }

        @Test
        @DisplayName("only blank states yield empty")
        void onlyBlankStatesYieldEmpty() {
            SQLException blank = new SQLException("blank", "  ");
            blank.setNextException(new SQLException("empty", ""));

            assertThat(ProblemFactory.findSqlState(new UncategorizedSQLException("task", "sql", blank)))
                    .isEmpty();
        }
    }

    @Nested
    @DisplayName("next-exception chain")
    final class NextExceptions {

        @Test
        @DisplayName("a stateless SQLException yields the state on its next exception")
        void stateOnlyOnNextException() {
            SQLException batch = new SQLException("batch entry failed");
            batch.setNextException(new SQLException("violates foreign key", "23503"));

            assertThat(ProblemFactory.findSqlState(batch)).contains("23503");
        }

        @Test
        @DisplayName("the next exception's state is found behind a DataAccessException and a batch failure")
        void nextExceptionBehindDataAccessException() {
            BatchUpdateException batch = new BatchUpdateException("batch entry failed", null, new int[0]);
            batch.setNextException(new SQLException("violates check constraint", "23514"));
            RuntimeException failure = new RuntimeException("write failed",
                    new DataIntegrityViolationException("batch", batch));

            assertThat(ProblemFactory.findSqlState(failure)).contains("23514");
        }

        @Test
        @DisplayName("a next chain longer than one is followed past stateless and blank links")
        void nextChainLongerThanOne() {
            SQLException head = new SQLException("head");
            head.setNextException(new SQLException("first", "  "));
            head.setNextException(new SQLException("second"));
            head.setNextException(new SQLException("third", "40001"));

            assertThat(ProblemFactory.findSqlState(head)).contains("40001");
        }

        @Test
        @DisplayName("an SQLException's own state wins over its next exception and cause")
        void ownStateBeforeLinks() {
            SQLException failure = new SQLException("own", "08006", new SQLException("cause", "42P01"));
            failure.setNextException(new SQLException("next", "23505"));

            assertThat(ProblemFactory.findSqlState(failure)).contains("08006");
        }

        @Test
        @DisplayName("a next exception is inspected before the cause")
        void nextBeforeCause() {
            SQLException failure = new SQLException("stateless", (String) null,
                    new SQLException("cause", "42P01"));
            failure.setNextException(new SQLException("next", "23505"));

            assertThat(ProblemFactory.findSqlState(failure)).contains("23505");
        }

        @Test
        @DisplayName("the cause is still inspected when the next chain carries no state")
        void causeAfterStatelessNext() {
            SQLException failure = new SQLException("stateless", (String) null,
                    new SQLException("cause", "42P01"));
            failure.setNextException(new SQLException("next"));

            assertThat(ProblemFactory.findSqlState(failure)).contains("42P01");
        }
    }

    @Nested
    @DisplayName("cycles terminate")
    final class Cycles {

        @Test
        @DisplayName("a self-referencing cause yields empty")
        void selfCause() {
            LinkedFailure failure = new LinkedFailure("self");
            failure.cause = failure;

            assertThat(walk(failure)).isEmpty();
        }

        @Test
        @DisplayName("a two-node cause cycle without a state yields empty")
        void causeCycleWithoutState() {
            LinkedFailure first = new LinkedFailure("first");
            LinkedFailure second = new LinkedFailure("second");
            first.cause = second;
            second.cause = first;

            assertThat(walk(first)).isEmpty();
        }

        @Test
        @DisplayName("a cause cycle through a stateful SQLException yields its state")
        void causeCycleWithState() {
            LinkedFailure outer = new LinkedFailure("outer");
            LinkedSqlException sql = new LinkedSqlException("canceled", "57014");
            outer.cause = sql;
            sql.cause = outer;

            assertThat(walk(outer)).contains("57014");
        }

        @Test
        @DisplayName("a self-referencing next exception yields empty")
        void selfNext() {
            LinkedSqlException failure = new LinkedSqlException("self", null);
            failure.next = failure;

            assertThat(walk(failure)).isEmpty();
        }

        @Test
        @DisplayName("a two-node next-exception cycle without a state yields empty")
        void nextCycleWithoutState() {
            LinkedSqlException first = new LinkedSqlException("first", null);
            LinkedSqlException second = new LinkedSqlException("second", " ");
            first.next = second;
            second.next = first;

            assertThat(walk(first)).isEmpty();
        }

        @Test
        @DisplayName("a next-exception cycle leading to a cause with a state yields that state")
        void nextCycleWithStatefulCause() {
            LinkedSqlException first = new LinkedSqlException("first", null);
            LinkedSqlException second = new LinkedSqlException("second", null);
            first.next = second;
            second.next = first;
            second.cause = new SQLException("deadlock", "40P01");

            assertThat(walk(first)).contains("40P01");
        }

        /**
         * Runs the walk under a preemptive timeout, so a cycle the walk failed to detect fails the test.
         *
         * @param failure the cyclic failure
         * @return the walk's result
         */
        private Optional<String> walk(Throwable failure) {
            return assertTimeoutPreemptively(CYCLE_TIMEOUT, () -> ProblemFactory.findSqlState(failure));
        }
    }

    @Nested
    @DisplayName("node bound")
    final class Bound {

        @Test
        @DisplayName("a state on the last of " + NODE_BOUND + " causes is found")
        void causeChainAtBound() {
            assertThat(ProblemFactory.findSqlState(causeChain(NODE_BOUND))).contains("23505");
        }

        @Test
        @DisplayName("a state only beyond " + NODE_BOUND + " causes yields empty")
        void causeChainBeyondBound() {
            assertThat(ProblemFactory.findSqlState(causeChain(NODE_BOUND + 1))).isEmpty();
        }

        @Test
        @DisplayName("a state on the last of " + NODE_BOUND + " next exceptions is found")
        void nextChainAtBound() {
            assertThat(ProblemFactory.findSqlState(nextChain(NODE_BOUND))).contains("40P01");
        }

        @Test
        @DisplayName("a state only beyond " + NODE_BOUND + " next exceptions yields empty")
        void nextChainBeyondBound() {
            assertThat(ProblemFactory.findSqlState(nextChain(NODE_BOUND + 1))).isEmpty();
        }

        /**
         * Builds a cause chain of {@code length} throwables whose only state sits on the innermost one.
         *
         * @param length the number of throwables, at least 1
         * @return the outermost throwable
         */
        private Throwable causeChain(int length) {
            Throwable chain = new SQLException("root", "23505");
            for (int i = 1; i < length; i++) {
                chain = new RuntimeException("layer " + i, chain);
            }
            return chain;
        }

        /**
         * Builds a next-exception chain of {@code length} SQLExceptions whose only state sits on the last.
         *
         * @param length the number of exceptions, at least 2
         * @return the head of the chain
         */
        private SQLException nextChain(int length) {
            SQLException head = new SQLException("head");
            for (int i = 1; i < length - 1; i++) {
                head.setNextException(new SQLException("link " + i));
            }
            head.setNextException(new SQLException("last", "40P01"));
            return head;
        }
    }

    /** A runtime exception whose cause is a plain field, so a test can point it anywhere, itself included. */
    private static final class LinkedFailure extends RuntimeException {

        private static final long serialVersionUID = 1L;

        /** The throwable {@link #getCause()} returns; {@code null} until a test assigns it. */
        private Throwable cause;

        LinkedFailure(String message) {
            super(message);
        }

        @Override
        public Throwable getCause() {
            return cause;
        }
    }

    /** An SQLException whose next exception and cause are plain fields, so a test can close a loop. */
    private static final class LinkedSqlException extends SQLException {

        private static final long serialVersionUID = 1L;

        /** The exception {@link #getNextException()} returns; {@code null} until a test assigns it. */
        private SQLException next;

        /** The throwable {@link #getCause()} returns; {@code null} until a test assigns it. */
        private Throwable cause;

        LinkedSqlException(String reason, String sqlState) {
            super(reason, sqlState);
        }

        @Override
        public SQLException getNextException() {
            return next;
        }

        @Override
        public Throwable getCause() {
            return cause;
        }
    }
}
