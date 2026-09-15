package app.sterna.ui.compose

import java.util.UUID

/**
 * The body of a mailto: link or a share, on its way to the composer that opens on it. It does not
 * travel in the navigation route: a long enough body makes the route match no destination, and a
 * route is copied into the activity's saved state with the back stack.
 *
 * Memory only, so a process death empties it; the composer then gets no body back, and its resume
 * slot restores the text it had already landed. One entry per token and each taken once: a share
 * that arrives with a composer open stacks a second composer, and each must take its own body.
 */
class LinkBodyHandOff {
    private val bodies = mutableMapOf<String, String>()

    /** Parks [body] under a fresh token. A blank body parks nothing and yields no token. */
    @Synchronized
    fun put(body: String): String? {
        if (body.isBlank()) return null
        val token = UUID.randomUUID().toString()
        bodies[token] = body
        return token
    }

    /** The body parked under [token], removed as it is handed over; null for a token unknown here. */
    @Synchronized
    fun take(token: String?): String? = token?.let { bodies.remove(it) }
}

/**
 * One composer entry's claim on its body: taken from [handOff] on the first call, answered from here
 * after that. Held by the entry's ViewModel, because the hand-off gives a body only once and a
 * rotation rebuilds the screen, not the ViewModel: a claim held by the composition would come back
 * empty. Null for no token, or one the hand-off does not know.
 */
class LinkBodyClaim(private val handOff: LinkBodyHandOff) {
    private var claimed = false
    private var body: String? = null

    fun body(token: String?): String? {
        if (!claimed) {
            claimed = true
            body = handOff.take(token)
        }
        return body
    }
}
