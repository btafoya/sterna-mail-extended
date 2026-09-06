package app.sterna.pgp

import android.content.Context
import org.openintents.openpgp.util.OpenPgpProviderUtil

/** Which OpenPGP provider Sterna talks to — pure functions over package names, and NOT in the
 *  engine, which needs a Context and a live binder. The engine used to carry a two-entry literal
 *  list of OpenKeychain packages, so a device whose only provider was something else had every PGP
 *  feature silently dead. */
object PgpProviders {

    /** OpenKeychain, the provider Sterna shipped against and the one already-set-up users have. */
    const val OPENKEYCHAIN = "org.sufficientlysecure.keychain"

    /** OpenKeychain's debug build; a separate package, and a legitimate provider on a dev device. */
    const val OPENKEYCHAIN_DEBUG = "org.sufficientlysecure.keychain.debug"

    /** The providers of [packages] Sterna may bind, deduplicated and ordered by package name. The
     *  allow-list check is the api's own, which rejects APG; the ordering only keeps the answer
     *  independent of what order the package manager returned. */
    fun eligible(packages: List<String>): List<String> =
        packages.filter { OpenPgpProviderUtil.isProviderAllowed(it) }.distinct().sorted()

    /** The provider to use when the user has expressed no preference, or `null`. OpenKeychain
     *  FIRST, not the first in the sort: every existing user with PGP set up uses it, and
     *  `com.pgpony.android` sorts before it, so a bare alphabetical pick would move those users onto
     *  a provider their keys are not in. The `.debug` variant is a fallback for the same reason. */
    fun preferredDefault(installed: List<String>): String? = when {
        installed.contains(OPENKEYCHAIN) -> OPENKEYCHAIN
        installed.contains(OPENKEYCHAIN_DEBUG) -> OPENKEYCHAIN_DEBUG
        else -> installed.minOrNull()
    }

    /** The provider to bind, given what is [installed] and what the user [chosen]. A choice is
     *  honoured ONLY while its package is still installed: returning an uninstalled one would leave
     * PGP dead while another provider sits right there, the very defect #151 reports. The choice
     *  comes FIRST, or the picker is decorative. */
    fun resolve(installed: List<String>, chosen: String?): String? =
        chosen?.takeIf { installed.contains(it) } ?: preferredDefault(installed)

    /**
     * Whether picking [picked] must drop every account's OpenPGP signing key, given the provider
     * [inUse] at the moment of the tap. `inUse == null` is NOT a switch — the caller does not know
     * what is in use, and clearing a signing key is irreversible. Erasing on `null` is the defect
     * this function was extracted to make executable: a tap landing before the screen had resolved
     * anything wiped every account's keys for a choice that changed nothing.
     */
    fun switchErasesKeys(inUse: String?, picked: String): Boolean =
        inUse != null && inUse != picked

    /**
     * The package to write down as the chosen provider. It exists for the user who has exactly ONE
     * OpenPGP app: no row is offered, so nothing would ever be stored, and the day they install a
     * second app Sterna would change provider without a word, their signing key then an id the new
     * provider never emitted.
     *
     * A [stored] choice is NEVER rewritten, and this is the dangerous half: [resolve] falls back
     * to the default whenever the chosen package is momentarily absent. Never an erasure either:
     * pinning writes down the provider ALREADY in use.
     */
    fun providerToPin(stored: String?, resolved: String?): String? =
        if (stored == null) resolved else null

    /** Whether a connection bound to [boundTo] may serve a call aimed at [target] — only the same
     *  package. The engine keeps its binding for the process lifetime, so without this a provider
     *  switched in Settings changes nothing until the app is killed. */
    fun bindingReusable(boundTo: String?, target: String): Boolean =
        boundTo != null && boundTo == target

    /** The eligible providers installed on this device. The only part of this file touching Android. */
    fun installed(context: Context): List<String> =
        eligible(OpenPgpProviderUtil.getOpenPgpProviderPackages(context))
}
