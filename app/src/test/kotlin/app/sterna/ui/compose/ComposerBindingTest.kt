package app.sterna.ui.compose

import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** When a photo returned by the camera may be posed (#233): only once `prepare` has bound the composer. */
class ComposerBindingTest {

    @Test fun beforePrepareOpensItTheWaitRunsOut() = runTest {
        assertFalse(ComposerBinding().awaitSettled(1_000))
    }

    @Test fun openWithNothingPendingIsSettledAtOnce() = runTest {
        val binding = ComposerBinding().apply { open() }
        assertTrue(binding.awaitSettled(1_000))
    }

    @Test fun aTrackedJobHoldsTheBindingUntilItCompletes() = runTest {
        val binding = ComposerBinding()
        val restore = Job()
        binding.track(restore)
        binding.open()
        assertFalse("a restore still running must hold the pose", binding.awaitSettled(1_000))
        restore.complete()
        assertTrue(binding.awaitSettled(1_000))
    }

    @Test fun aJobThatFailsReleasesItToo() = runTest {
        val binding = ComposerBinding().apply { open() }
        val refresh = Job()
        binding.track(refresh)
        refresh.completeExceptionally(IllegalStateException("provider gone"))
        assertTrue(binding.awaitSettled(1_000))
    }

    @Test fun aPoseWaitingBeforePrepareGoesThroughOnceBound() = runTest {
        val binding = ComposerBinding()
        val sentinel = Job()
        val waiting = async { binding.awaitSettled(10_000) }
        binding.track(sentinel)
        binding.open()
        sentinel.complete()
        assertTrue(waiting.await())
    }

    @Test fun completedJobsWithoutOpenAreNotEnough() = runTest {
        val binding = ComposerBinding()
        val job = Job()
        binding.track(job)
        job.complete()
        assertFalse(binding.awaitSettled(1_000))
    }
}
