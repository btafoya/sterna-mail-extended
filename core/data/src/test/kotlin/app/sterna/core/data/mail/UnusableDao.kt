package app.sterna.core.data.mail

import java.lang.reflect.Proxy

/**
 * A DAO that exists and refuses to answer, for the JVM tests that build a [MailRepository]: a path
 * asserted to touch no database fails here instead of quietly passing on an empty answer. The Room
 * DAOs are interfaces, so a [Proxy] implements them all without a stub per test.
 */
internal inline fun <reified T : Any> unusableDao(): T =
    Proxy.newProxyInstance(T::class.java.classLoader, arrayOf(T::class.java)) { _, method, _ ->
        if (method.name == "toString") {
            "unusable ${T::class.java.simpleName}"
        } else {
            error("${T::class.java.simpleName}.${method.name} was called by a path that should not touch the database.")
        }
    } as T
