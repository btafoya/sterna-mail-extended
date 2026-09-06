package app.sterna.ui.connect

import app.sterna.core.data.autoconfig.MailAutoconfigResult
import app.sterna.core.jmap.Jmap
import app.sterna.core.jmap.OAuthMetadata
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.coroutines.coroutineContext

/**
 * HOW the address step asks — the two things the ViewModel cannot be made to prove, because it is
 * an `AndroidViewModel` on the app container and no test in this module can build one: how long the
 * OAuth search may take, and that the step's two probes run at the SAME TIME.
 *
 * Both are executed by tests on a virtual clock, with fake probes.
 */

/**
 * How long the OAuth search may take, all candidates together.
 *
 * This is NOT a per-host timeout, and it does not belong in `OAuthClient`: its 20 s connect +
 * 30 s read also serve the token polls, where waiting is right. Four candidates asked one after
 * another cost up to 200 s — tolerable behind a button nobody taps by accident, unacceptable on the
 * path every address now takes, and the exact wheel the JMAP cascade has just been given a budget
 * to remove ([app.sterna.core.data.mail.JMAP_DISCOVERY_BUDGET_MS], same value, same reason).
 */
const val OAUTH_DISCOVERY_BUDGET_MS = 10_000L

/**
 * WHICH hosts the default path is allowed to ask how the MAIL signs in: the mail-named hosts of
 * the address' domain, and NOT the bare domain.
 *
 * The bare domain ALSO SERVES THE ORGANISATION'S WEBSITE, and that is the whole of the reason:
 * its `/.well-known/oauth-authorization-server` may be that site's identity provider — the social
 * network hosted there, or whatever another host it redirects to publishes — and nothing in the
 * document says which of the two it speaks for. It cannot be attributed to the mail server, so it
 * is not read as one. Taken as a verdict at the address step it replaced the password field with
 * "Open the sign-in page" onto a login form belonging to another service entirely: no persona on
 * the bench could be added by the default path any more.
 *
 * NOT "the bare domain is the website" — it is very often the mail server too. RFC 8620 § 2.2
 * publishes the JMAP session at `/.well-known/jmap` on exactly this host, and `MailRepository`
 * still asks it FIRST when it looks for the server. This rule is deliberately narrower than the
 * belief that would justify it: one host, two roles, so one of its two answers is refused.
 *
 * The cost is real and is accepted: a server publishing its OAuth on the bare domain — the
 * commonest shape for a self-hosted JMAP server — is no longer recognised by the default path.
 * Its reader gets the password field, which works unless the server is OAuth-only, and the manual
 * form's OAuth button remains the door. The other way round — believing the bare domain — is the
 * defect measured on the bench, and it costs an account that cannot be added at all.
 *
 * Derived from [Jmap.autodiscoverHosts] rather than rebuilt: the address normalisation (case,
 * trailing dot, malformed addresses) is stated once, and the two lists cannot drift apart. What is
 * removed is the bare domain, i.e. the one candidate the others are subdomains of.
 *
 * NOT for the manual fallback's OAuth button ([ConnectViewModel.connectOAuth]), which keeps all
 * four candidates: there the reader has ASSERTED that her server does OAuth, and may have typed
 * its host. It is the only door left for an OAuth published on the bare domain, and narrowing it
 * would answer "this server doesn't support OAuth sign-in" to a sign-in that worked.
 */
fun mailOAuthCandidates(email: String): List<String> {
    val hosts = Jmap.autodiscoverHosts(email)
    val bareDomain = hosts.firstOrNull() ?: return emptyList()
    return hosts.filter { it.endsWith(".$bareDomain") }
}

/**
 * Search [candidates] for an OAuth server with [discover], under one global [budgetMs], and answer
 * with the host the sign-in should go to — [chooseOAuthHost]'s pick of what ANSWERED IN TIME.
 *
 * WHICH hosts get asked, and what ends the search, stay [collectOAuthHosts]'s call, and which of
 * the answers wins stays [chooseOAuthHost]'s: this function adds a clock and nothing else. Both are
 * executed against fake probes in `OAuthErrorMessagesTest`, and reimplementing either here would
 * put the ranking (a device flow on `mail.…` beats a code grant on the bare domain) somewhere those
 * tests do not look.
 *
 * **The budget bounds what the user waits for**, not merely what is decided — the lesson of
 * `discoverJmapAmong`, whose shape this follows. The search runs in a job that is a CHILD of the
 * caller (leaving the screen cancels it) but is never JOINED: a real probe sits in a blocking OkHttp
 * `execute()` that ignores cancellation until its own socket gives up, so waiting for it to notice
 * would put the 20–50 s straight back on the wheel. Hence the answers are recorded as they land,
 * and what is in hand at the budget is what the verdict is taken from.
 *
 * [discover] is expected not to throw; anything it does throw is swallowed by [collectOAuthHosts].
 */
suspend fun discoverOAuthAmong(
    candidates: List<String>,
    /**
     * Defaulted on purpose, and production passes nothing, so the budget the app ships with lives
     * in exactly ONE place — [OAUTH_DISCOVERY_BUDGET_MS], which a test pins by NOT passing it.
     * Wired at the call sites it would be a second copy no test reaches.
     */
    budgetMs: Long = OAUTH_DISCOVERY_BUDGET_MS,
    discover: suspend (String) -> OAuthMetadata?,
): Pair<String, OAuthMetadata>? {
    if (candidates.isEmpty()) return null
    // Written by the search as each host answers, read here when the budget is up: the search's own
    // return value is out of reach precisely in the case this exists for.
    val answered = CopyOnWriteArrayList<Pair<String, OAuthMetadata>>()
    val searched = CompletableDeferred<Unit>()
    val probes = Job(coroutineContext[Job])
    val scope = CoroutineScope(coroutineContext + probes)
    try {
        scope.launch {
            collectOAuthHosts(candidates) { host -> discover(host)?.also { answered += host to it } }
            searched.complete(Unit)
        }
        withTimeoutOrNull(budgetMs) { searched.await() }
        return chooseOAuthHost(answered.toList())
    } finally {
        // A request to stop, not a wait for it: a deaf probe dies on OkHttp's own timeouts.
        probes.cancel()
    }
}

/**
 * What one pass over an address learned: what the domain publishes, and how it signs in.
 *
 * [oauth] IS NOT NULLABLE, and that is a fix, not a detail. It used to be — null meaning "this
 * step did not ask" — and the two answers then travelled apart: a settings verdict could be
 * recorded for an address with no OAuth answer beside it, which is a credentials step that draws
 * neither a password field (the server may want a browser) nor a hand-over (nothing says it does).
 * A bare wheel, and no way out for that address: the probe is refused ever after, because a
 * discovered host now fills the form. `null` here is the "found no OAuth" ANSWER, and it lives one
 * level down, inside [OAuthDiscovery.chosen].
 */
internal data class AddressProbe(
    val settings: MailAutoconfigResult,
    val oauth: OAuthDiscovery,
)

/**
 * Ask [settings] and [oauth] CONCURRENTLY, and answer once both have.
 *
 * The concurrency is the point, and it is why this is a function instead of two statements in the
 * ViewModel. Each side carries its own budget (10 s), so chaining them would make the address step
 * cost 20 s in the worst case — for EVERYONE, including the vast majority whose server has no OAuth
 * at all, and on the one step every account added now goes through. Both budgets run on the same
 * wall clock here, so the step is bounded by the slower of the two, not by their sum.
 *
 * And the step waits for BOTH before it moves: the walk steps on the settings verdict, and a
 * credentials step drawn before the OAuth answer landed is a password field for a server that never
 * wanted one — the defect this whole volet removes.
 */
internal suspend fun probeAddressTogether(
    settings: suspend () -> MailAutoconfigResult,
    oauth: suspend () -> OAuthDiscovery,
): AddressProbe = coroutineScope {
    // Both started before either is awaited. `async { … }.await()` written twice in a row, or a
    // plain `settings()` followed by `oauth()`, is the same code one keystroke away and costs both
    // budgets end to end.
    val settingsAnswer = async { settings() }
    val oauthAnswer = async { oauth() }
    AddressProbe(settingsAnswer.await(), oauthAnswer.await())
}
