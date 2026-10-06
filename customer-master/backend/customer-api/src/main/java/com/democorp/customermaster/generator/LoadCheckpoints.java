package com.democorp.customermaster.generator;

import org.springframework.stereotype.Component;

/**
 * Callbacks that mark the step boundaries of the generator's load transaction, so tests can pause
 * or fail a load at an exact point.
 *
 * <p>The load replaces LOADCUSTR's {@code truncate custmast} followed by one {@code insert} per row
 * under commitment control {@code *NONE} ({@code 5250_Subfile/LOADCUSTR.SQLRPGLE}, lines 91, 95 and
 * 203) with a single transaction. {@code CustomerLoader.load} runs its steps in this order and calls
 * one checkpoint after each of the three steps that change data:
 * <ol>
 *   <li>{@code SET LOCAL lock_timeout} and {@code CustomerIdAllocator.lockForLoad()}, which takes
 *       {@code LOCK TABLE custmast IN ACCESS EXCLUSIVE MODE};</li>
 *   <li>{@code TRUNCATE custmast}, then {@link #afterTruncate()};</li>
 *   <li>{@code COPY custmast (...) FROM STDIN}, then {@link #afterCopy()};</li>
 *   <li>{@code CustomerIdAllocator.restartAfterLoad(...)}, then {@link #afterSequenceRestart()};</li>
 *   <li>{@code COMMIT}.</li>
 * </ol>
 *
 * <h2>Contract shared by every checkpoint</h2>
 * <ul>
 *   <li><strong>Inside the transaction.</strong> Each method is called on the loading thread while
 *       the load transaction is open and the {@code ACCESS EXCLUSIVE} lock on {@code custmast} is
 *       held, so every concurrent search, read and id allocation is waiting on that lock.</li>
 *   <li><strong>Throwing rolls back.</strong> A {@link RuntimeException} thrown from a checkpoint
 *       propagates out of {@code CustomerLoader.load} and rolls the whole transaction back: the
 *       truncate, the copied rows and the sequence restart are all undone together, so the table
 *       keeps its pre-load rows and the next id is the one it was before the load.</li>
 *   <li><strong>Blocking holds the lock.</strong> An implementation that blocks (for example on a
 *       latch) keeps the transaction open and the lock held for as long as it blocks. Tests use
 *       this to prove that {@code CustomerIdAllocator.next()} waits for the load to commit. A
 *       blocking implementation must return well inside the configured lock timeouts.</li>
 *   <li><strong>No checked exceptions.</strong> The signatures declare none; an implementation that
 *       is interrupted while blocking restores the thread's interrupt flag and throws an unchecked
 *       exception, which rolls the load back as above.</li>
 * </ul>
 *
 * <h2>Production and test beans</h2>
 * <p>Every method is a no-op by default. {@link NoOp} is the production bean, registered by
 * component scanning under every profile (web, {@code test} and {@code generator}), because
 * {@code CustomerLoader} is profile-independent. It is deliberately not {@code @Primary}: a test
 * replaces it with {@code @MockitoBean LoadCheckpoints} or {@code @TestBean}, which replace by
 * type, or by declaring its own {@code @Primary} bean, which then wins injection.
 *
 * <p>Example of a test checkpoint that pauses after the copy until the test releases it:
 * <pre>{@code
 * CountDownLatch reached = new CountDownLatch(1);
 * CountDownLatch release = new CountDownLatch(1);
 * LoadCheckpoints pauseAfterCopy = new LoadCheckpoints() {
 *     @Override
 *     public void afterCopy() {
 *         reached.countDown();
 *         try {
 *             if (!release.await(30, TimeUnit.SECONDS)) {
 *                 throw new IllegalStateException("checkpoint was never released");
 *             }
 *         } catch (InterruptedException e) {
 *             Thread.currentThread().interrupt();
 *             throw new IllegalStateException("interrupted at afterCopy", e);
 *         }
 *     }
 * };
 * }</pre>
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
        // Production loads pass straight through this checkpoint.
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
        // Production loads pass straight through this checkpoint.
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
        // Production loads pass straight through this checkpoint.
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
            // No state: the default methods of LoadCheckpoints are the whole implementation.
        }
    }
}
