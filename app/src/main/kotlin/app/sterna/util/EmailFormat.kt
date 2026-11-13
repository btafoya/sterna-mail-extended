package app.sterna.util

/** Lightweight email-format check: a non-empty local part, one `@`, no space, and a domain of one or
 *  more non-empty dot-separated labels, so `leo@yggmail` and `root@localhost` pass (#294). */
val EMAIL_REGEX = Regex("^[^@\\s]+@[^@\\s.]+(\\.[^@\\s.]+)*$")

/** True when [address] (trimmed) looks like a well-formed email address. See [EMAIL_REGEX]. */
fun isValidEmail(address: String): Boolean = EMAIL_REGEX.matches(address.trim())
