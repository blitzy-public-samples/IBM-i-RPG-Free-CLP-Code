package com.democorp.customermaster.generator;

import org.springframework.stereotype.Component;

/**
 * Callbacks that mark the step boundaries of the generator's load transaction, so tests can pause
 * or fail a load at an exact point.
 *
 * <p>The load replaces LOADCUSTR's {@code truncate custmast} followed by one {@code insert} per row
 * under commitment control {@code *NONE} ({@code 5250_Subfile/LOADCUSTR.SQLRPGLE}, lines 91, 95 and
 * 203) with a single transaction. {@code CustomerLoader.load} runs its steps in the order
 * {@code CustomerLoader} documents and calls one checkpoint after each of the three steps that
 * change data.
 *
 * <h2>Contract shared by every checkpoint</h2>
 * <ul>
 *   <li><strong>Inside the transaction.</strong> Each method runs on the loading thread inside the
 *       open load transaction, while the {@code ACCESS EXCLUSIVE} lock on {@code custmast} is
 *       held.</li>
 *   <li><strong>Throwing rolls back.</strong> A {@link RuntimeException} propagates out of
 *       {@code CustomerLoader.load} and rolls the whole load back, so the table keeps its pre-load
 *       rows and next id.</li>
 *   <li><strong>Blocking holds the lock.</strong> A blocking implementation keeps the lock for as
 *       long as it blocks and must return well inside the configured lock timeouts.</li>
 *   <li><strong>No checked exceptions.</strong> An implementation interrupted while blocking
 *       restores the thread's interrupt flag and throws an unchecked exception.</li>
 * </ul>
 *
 * <h2>Production and test beans</h2>
 * <p>Every method is a no-op by default. {@link NoOp} is the production bean, registered by
 * component scanning under every profile, because {@code CustomerLoader} is profile-independent.
 * It is deliberately not {@code @Primary}: a test replaces it with
 * {@code @MockitoBean LoadCheckpoints} or {@code @TestBean}, which replace by type, or with its
 * own {@code @Primary} bean.
 */
public interface LoadCheckpoints {

    /**
     * Called after {@code TRUNCATE custmast} and before the {@code COPY} starts.
     *
     * <p>Runs inside the open load transaction while the {@code ACCESS EXCLUSIVE} lock on
     * {@code custmast} is held. A {@link RuntimeException} thrown here rolls back the truncate,
     * so the pre-load rows and the sequence remain as they were. A blocking implementation holds
     * the lock for as long as it blocks.
     *
     * <p>The default implementation does nothing.
     */
    default void afterTruncate() {
    }

    /**
     * Called after the {@code COPY} of the generated rows has ended and before the sequence
     * restart.
     *
     * <p>Runs inside the open load transaction while the {@code ACCESS EXCLUSIVE} lock on
     * {@code custmast} is held. A {@link RuntimeException} thrown here rolls back the copied rows
     * and the truncate together, so the pre-load rows and the pre-load next id remain. A blocking
     * implementation holds the lock, which tests use to show that id allocation waits until the
     * load commits.
     *
     * <p>The default implementation does nothing.
     */
    default void afterCopy() {
    }

    /**
     * Called after {@code CustomerIdAllocator.restartAfterLoad(...)}, the last step before the
     * load transaction commits.
     *
     * <p>Runs inside the open load transaction while the {@code ACCESS EXCLUSIVE} lock on
     * {@code custmast} is held. A {@link RuntimeException} thrown here rolls back the rows and the
     * sequence restart together, including the exhausted branch of a load that ends at id
     * {@code 9999}, so the previous rows and the previous next id return. A blocking
     * implementation holds the lock until it returns.
     *
     * <p>The default implementation does nothing.
     */
    default void afterSequenceRestart() {
    }

    /**
     * The production checkpoints: every step passes through without effect.
     *
     * <p>Registered as bean {@code noOpLoadCheckpoints} under every profile, with no
     * {@code @Profile} and no {@code @Primary}, so a test's replacement takes precedence.
     */
    @Component("noOpLoadCheckpoints")
    public static class NoOp implements LoadCheckpoints {

        /** Creates the no-op checkpoints; the inherited default methods supply the behaviour. */
        public NoOp() {
        }
    }
}
