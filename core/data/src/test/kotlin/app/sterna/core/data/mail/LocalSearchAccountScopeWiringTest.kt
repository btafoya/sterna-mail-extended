package app.sterna.core.data.mail

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Source lint, not a behaviour test. `MailRepository` needs Room, an Android `Context` and a live
 */
class LocalSearchAccountScopeWiringTest {

    @Test fun `searchIndex hands the DAO the accounts of the scope it is given, still configured, on every call`() {
        // `accountIds` and its size come from one local read inside the function: a literal or a cached
        // list would pass every SQL case, and the sub-account reconcile (#31) changes accounts mid-session.
        // A null scope from `localSearchAccounts` returns before the DAO, whose empty list means every
        // account. Swapped Boolean flags compile; `LocalSearchCriteriaSqlTest` binds them by name.
        assertEquals(
            "MailRepository.searchIndex is no longer, line for line, what this test was written " +
                "against. An INSERTED or LENGTHENED line is what this pin exists to catch: the " +
                "account scope only works if this function fills it on every call.",
            listOf(
                "{",
                "val match = ftsMatch(query)",
                "val accountIds = localSearchAccounts(accountStore.accounts().map { it.id }, scope) ?: return emptyList()",
                "val hits = if (match != null) {",
                "emailFtsDao.search(match, query.unread, query.flagged, query.hasAttachment, query.afterMillis, query.beforeMillis, NOT_SEARCHED_ROLES, accountIds, accountIds.size, limit)",
                "} else {",
                "emailFtsDao.searchWithoutWords(query.unread, query.flagged, query.hasAttachment, query.afterMillis, query.beforeMillis, NOT_SEARCHED_ROLES, accountIds, accountIds.size, limit)",
                "}",
                "return hits.map { it.toEmail() }",
                "}",
            ),
            DaoQuerySource.mailFunctionBody("MailRepository", "searchIndex")
                .lines().map { it.trim() }.filter { it.isNotEmpty() },
        )
    }
}
