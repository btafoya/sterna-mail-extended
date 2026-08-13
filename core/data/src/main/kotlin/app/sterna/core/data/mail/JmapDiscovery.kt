package app.sterna.core.data.mail

import app.sterna.core.jmap.JmapException
import app.sterna.core.jmap.model.JmapSession
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.atomic.AtomicReferenceArray
import kotlin.coroutines.coroutineContext

/**
 * What one candidate host answered when it was asked for a JMAP session — the three facts
 * autodiscovery can learn about a host, and nothing else. Deliberately not [Throwable]-shaped:
 * the translation from a session or a failure into one of these three is [probeOfSession] and
 * [probeOfFailure], where it can be run by a test.
 */
sealed interface HostProbe {
    /** The host served a session that carries a mail account: it can be stored as the server. */
    data object Usable : HostProbe
    /** The host answered, and rejected the credentials (HTTP 401/403). */
    data object BadCredentials : HostProbe
    /** Nothing usable: unreachable, not JMAP, no mail account, or still silent. */
    data object NoAnswer : HostProbe
}

/**
 * What a session answer means for autodiscovery.
 *
 * ⛔ A session WITHOUT a mail account is not a mail server. Some servers hand a session to an
 * unauthenticated or unrelated request (see the anonymous-session note in `JmapClient`, #137);
 * storing such a host as the account's server writes the wrong server into the account, and that
 * is only correctable by typing everything in again.
 */
fun probeOfSession(session: JmapSession): HostProbe =
    if (session.mailAccountId() != null) HostProbe.Usable else HostProbe.NoAnswer

/**
 * What a failed probe means for autodiscovery: only the server's own 401/403 is a statement about
 * the credentials. Every other failure — 404, 5xx, DNS, TLS, a socket that timed out — means this
 * candidate said nothing, and must not be reported to the user as a wrong password.
 *
 * ⛔ A coroutine timeout is NOT among them: it arrives as a [CancellationException] and rethrows
 * here like any other cancellation. Wrapping a probe in `withTimeout` therefore costs the whole
 * budget rather than yielding a quick [HostProbe.NoAnswer] — use [withTimeoutOrNull] and map the
 * null yourself if a per-host deadline is ever wanted.
 */
fun probeOfFailure(failure: Throwable): HostProbe {
    // ⛔ Cancellation is not something a host said, so it is not this function's to classify. The
    // old loop caught Throwable, recorded "this candidate is silent", and walked on to the next
    // one after the user had already left the screen. Rethrowing here means no call site can
    // record it by writing a catch-all: the only way past this is to be cancelled too.
    if (failure is CancellationException) throw failure
    return if (failure is JmapException && (failure.httpCode == 401 || failure.httpCode == 403)) {
        HostProbe.BadCredentials
    } else {
        HostProbe.NoAnswer
    }
}

/**
 * Ask ONE host, via [fetchSession], and answer with what it said.
 *
 * ⛔ This exists so that the try/catch is not written at the call site. It used to be, and the whole
 * of `MailRepository.discoverJmapServer` is unreachable from a JVM test (the repository cannot be
 * built there), so `catch (t) { probeOfFailure(t) }` could be replaced by a bare
 * `HostProbe.NoAnswer` with the entire suite still green — which re-swallows cancellation AND loses
 * the 401/403, turning a wrong password into "no server found". Both classifiers and the catch now
 * live in one place a test runs, and the call site is left with nothing to spell but which URL and
 * which credentials it asks with.
 */
suspend fun probeHost(fetchSession: suspend () -> JmapSession): HostProbe =
    try {
        probeOfSession(fetchSession())
    } catch (t: Throwable) {
        probeOfFailure(t)
    }

/**
 * How long the whole of autodiscovery may take, all candidates together.
 *
 * ⛔ This is NOT a per-host timeout and it does not belong in [app.sterna.core.jmap.JmapClient]:
 * that client's 20 s connect + 30 s read also serve sync and push, where waiting is right. Four
 * candidates probed one after another cost up to 200 s of spinner on a domain with no JMAP at all
 * — the sign-in screen is the one place where "no answer yet" must become "no answer" quickly.
 */
const val JMAP_DISCOVERY_BUDGET_MS = 10_000L

/**
 * Ask the candidates of [hosts] with [probe], under one global [budgetMs], and answer with the
 * host of LOWEST RANK that yielded a usable session.
 *
 * ⛔ **Rank 0 is asked alone.** The other candidates are guessed subdomains (`mail.`, `jmap.`,
 * `api.`) which may be a wildcard DNS record, a CDN or another team's box, and this probe carries
 * the user's password (or API token) in an Authorization header. A secret sent cannot be unsent, so
 * the rest of the list is only released once rank 0 has answered something other than a session —
 * exactly the guard the old sequential loop gave for free by returning on the first success. The
 * price is accepted: a rank 0 that never answers leaves the others unasked and the screen asks for
 * the server by hand, which loses nothing.
 *
 * ⛔ **The winner is not the fastest, it is the first in [hosts].** The host chosen here is written
 * into the account, so a slow `mail.<domain>` and a quick `jmap.<domain>` must not hand two
 * installations two different servers. Answers are collected into a rank-indexed slate; a rank
 * still pending blocks any verdict taken on the ranks below it ([verdictOnceSettled]).
 *
 * ⛔ **The budget bounds what the user waits for**, not merely what is decided. The probes run in a
 * job that is a CHILD of the caller (cancelling the sign-in cancels them) but that is never JOINED:
 * a real probe sits in a blocking OkHttp `execute()` which ignores cancellation until its own
 * socket gives up, so waiting for the losers to notice would put the 20–50 s back on the spinner —
 * including on the happy path, where rank 0 answered in a fraction of a second.
 *
 * [probe] is expected not to throw; anything it does throw cancels the caller.
 */
suspend fun discoverJmapAmong(
    hosts: List<String>,
    /**
     * ⛔ Defaulted on purpose, and production passes nothing, so the budget the app ships with
     * lives in exactly ONE place — [JMAP_DISCOVERY_BUDGET_MS], which a test pins. Wired explicitly
     * at the call site it was a second copy no test could reach: [MailRepository] could hand this
     * 200_000L and the whole suite stayed green, which is the reported defect back in full. Tests
     * still pass their own budget; that is what the parameter is for.
     */
    budgetMs: Long = JMAP_DISCOVERY_BUDGET_MS,
    probe: suspend (String) -> HostProbe,
): MailRepository.DiscoveryResult {
    if (hosts.isEmpty()) return MailRepository.DiscoveryResult.NotFound
    // Rank-indexed, written by whichever probe finishes; atomic because the probes may land on
    // different threads (fetchSession hops to Dispatchers.IO). A slot is written once, never twice.
    val settled = AtomicReferenceArray<HostProbe>(hosts.size)
    val decided = CompletableDeferred<MailRepository.DiscoveryResult>()
    val probes = Job(coroutineContext[Job])
    val scope = CoroutineScope(coroutineContext + probes)
    try {
        scope.launch {
            settled.set(0, probe(hosts[0]))
            verdictOnceSettled(hosts, settled)?.let { decided.complete(it); return@launch }
            // Rank 0 has spoken and it is not a mail server: only now may the secret go further.
            for (rank in 1 until hosts.size) {
                launch {
                    settled.set(rank, probe(hosts[rank]))
                    verdictOnceSettled(hosts, settled)?.let { decided.complete(it) }
                }
            }
        }
        return withTimeoutOrNull(budgetMs) { decided.await() } ?: verdictAtBudget(hosts, settled)
    } finally {
        // A request to stop, not a wait for it: the losers die on OkHttp's own timeouts.
        probes.cancel()
    }
}

/**
 * The verdict implied by what is known so far, or null while a host that could still outrank the
 * best answer has not spoken: a pending rank blocks everything below it.
 */
private fun verdictOnceSettled(
    hosts: List<String>,
    settled: AtomicReferenceArray<HostProbe>,
): MailRepository.DiscoveryResult? {
    var sawAuthFailure = false
    for (rank in hosts.indices) {
        when (settled.get(rank)) {
            null -> return null
            HostProbe.Usable -> return MailRepository.DiscoveryResult.Found(hosts[rank])
            HostProbe.BadCredentials -> sawAuthFailure = true
            HostProbe.NoAnswer -> {}
        }
    }
    return if (sawAuthFailure) {
        MailRepository.DiscoveryResult.BadCredentials
    } else {
        MailRepository.DiscoveryResult.NotFound
    }
}

/**
 * The verdict when time is up: the lowest rank that DID answer with a session — a probe that
 * finished outranks one that is still silent, whatever the latency.
 *
 * ⛔ Never [MailRepository.DiscoveryResult.BadCredentials] here. A rejection from one candidate
 * while another has not spoken is not proof that the password is wrong: the bare domain is often
 * the organisation's website, which says 401 in 200 ms, and telling the user their password is
 * wrong while the real mail server is still being asked is a screen that lies. A credential
 * verdict is only reached in [verdictOnceSettled], i.e. once every rank has spoken.
 */
private fun verdictAtBudget(
    hosts: List<String>,
    settled: AtomicReferenceArray<HostProbe>,
): MailRepository.DiscoveryResult {
    for (rank in hosts.indices) {
        if (settled.get(rank) == HostProbe.Usable) return MailRepository.DiscoveryResult.Found(hosts[rank])
    }
    return MailRepository.DiscoveryResult.NotFound
}
