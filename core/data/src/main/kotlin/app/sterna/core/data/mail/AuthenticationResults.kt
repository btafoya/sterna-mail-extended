package app.sterna.core.data.mail

/**
 * What the receiving server says of a message's sender in `Authentication-Results` (RFC 8601),
 * reduced to the methods that failed (#276). Pure: the account's trusted names come from the caller.
 */
object AuthenticationResults {
    const val DMARC = "dmarc"
    const val DKIM = "dkim"
    const val SPF = "spf"

    private const val PASS = "pass"
    private const val FAIL = "fail"
    private const val NONE = "none"

    /**
     * [headers] newest first, the order both protocols hand them over. Only the topmost instance
     * whose `authserv-id` is trusted is read: RFC 8601 § 5 has the server delete instances bearing
     * its own name, never the others, so anything below it may be the sender's own writing. An
     * empty list means "nothing to say", including when no instance is trusted: saying nothing
     * beats saying something wrong.
     */
    fun failuresOf(headers: List<Pair<String, String>>, trustedNames: Set<String>): List<String> {
        val trusted = trustedNames.map(::normalisedName).filter { it.isNotEmpty() }
        val value = headers.firstOrNull { (name, value) ->
            name.trim().equals("Authentication-Results", ignoreCase = true) && isTrusted(authservIdOf(value), trusted)
        }?.second ?: return emptyList()
        return failuresIn(resultsOf(value))
    }

    /**
     * Only `fail` counts. `softfail`, `neutral`, `temperror` and the rest are "not verified", not
     * "failed". Without a DMARC verdict, a broken DKIM signature next to a passing SPF is the mark
     * of a mailing list, not of a forger, and stays silent.
     */
    private fun failuresIn(results: List<Pair<String, String>>): List<String> {
        val dmarc = results.firstOrNull { it.first == DMARC }?.second
        val dkim = results.filter { it.first == DKIM }.map { it.second }
        val spf = results.firstOrNull { it.first == SPF }?.second
        val failed = when (dmarc) {
            FAIL -> true
            null, NONE -> dkim.none { it == PASS } && spf != PASS && (dkim.any { it == FAIL } || spf == FAIL)
            else -> false
        }
        if (!failed) return emptyList()
        return listOfNotNull(
            DMARC.takeIf { dmarc == FAIL },
            DKIM.takeIf { dkim.any { it == FAIL } },
            SPF.takeIf { spf == FAIL },
        )
    }

    /** Equal to a trusted name, or under it. A single-label name is matched only exactly: a bare
     *  `org` would otherwise trust every `.org` server there is. */
    private fun isTrusted(authservId: String, trusted: List<String>): Boolean =
        authservId.isNotEmpty() &&
            trusted.any { authservId == it || ('.' in it && authservId.endsWith(".$it")) }

    private fun normalisedName(name: String): String = name.trim().lowercase().removeSuffix(".")

    /** The first atom, before the version that may follow it after a space. */
    private fun authservIdOf(value: String): String =
        segmentsOf(value).firstOrNull()
            ?.trim()?.split(WHITESPACE)?.firstOrNull()
            ?.substringBefore('/')?.trim('"')
            ?.let(::normalisedName)
            .orEmpty()

    /** `(method, result)` for each `method[/version]=result …` after the `authserv-id`; a lone
     *  `none` has no `=` and yields nothing. */
    private fun resultsOf(value: String): List<Pair<String, String>> =
        segmentsOf(value).drop(1).mapNotNull { segment ->
            val equals = segment.indexOf('=').takeIf { it > 0 } ?: return@mapNotNull null
            val method = segment.substring(0, equals).substringBefore('/').trim().lowercase()
            val result = segment.substring(equals + 1).trim().split(WHITESPACE).first().lowercase()
            (method to result).takeIf { method.isNotEmpty() && result.isNotEmpty() }
        }

    /**
     * Comments dropped, then split on `;`, both outside quoted strings: a comment or a quoted
     * `reason` may hold a `;` that opens nothing. Unbalanced parentheses swallow the rest.
     */
    private fun segmentsOf(value: String): List<String> {
        val out = mutableListOf<String>()
        val current = StringBuilder()
        var quoted = false
        var escaped = false
        var depth = 0
        for (c in value) {
            when {
                escaped -> { if (depth == 0) current.append(c); escaped = false }
                c == '\\' && (quoted || depth > 0) -> { if (depth == 0) current.append(c); escaped = true }
                depth > 0 -> when (c) {
                    '(' -> depth++
                    ')' -> depth--
                }
                c == '"' -> { quoted = !quoted; current.append(c) }
                quoted -> current.append(c)
                c == '(' -> { depth++; current.append(' ') }
                c == ';' -> { out.add(current.toString()); current.clear() }
                else -> current.append(c)
            }
        }
        out.add(current.toString())
        return out
    }

    private val WHITESPACE = Regex("\\s+")
}
