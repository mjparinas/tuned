package com.tunedplayer.app.data.db

/**
 * Binds named SQL parameters (`:name`) the way Room does, for the lists [QuickPagingSource] reads with a raw query:
 * each `:name` outside a quoted literal becomes `?`, a list becomes one `?` per value (an empty list, `IN ()`, matches
 * nothing, as with Room), and a Boolean becomes 1 or 0. The SQL is the same text the DAOs' `@Query` methods use, which
 * Room checks when it compiles, so a mistake in it fails the build rather than the screen.
 */
object SqlArgs {
    /** The SQL with `?` in place of each parameter, and the values in their order. */
    fun bind(
        sql: String,
        args: Map<String, Any?>,
    ): Pair<String, Array<Any?>> {
        val out = StringBuilder(sql.length + 32)
        val values = ArrayList<Any?>()
        var i = 0
        while (i < sql.length) {
            val c = sql[i]
            when {
                c == '\'' -> {
                    i = copyLiteral(sql, i, out)
                }

                c == ':' && i + 1 < sql.length && sql[i + 1].isLetter() -> {
                    var end = i + 1
                    while (end < sql.length && (sql[end].isLetterOrDigit() || sql[end] == '_')) end++
                    val name = sql.substring(i + 1, end)
                    require(args.containsKey(name)) { "No value for :$name" }
                    when (val value = args[name]) {
                        is Collection<*> -> {
                            value.forEachIndexed { n, v ->
                                if (n > 0) out.append(',')
                                out.append('?')
                                values += plain(v)
                            }
                        }

                        else -> {
                            out.append('?')
                            values += plain(value)
                        }
                    }
                    i = end
                }

                else -> {
                    out.append(c)
                    i++
                }
            }
        }
        return out.toString() to values.toTypedArray()
    }

    /** Copies a quoted literal ('it''s' included) as it is, so a colon inside one ('g:0:') is never a parameter. */
    private fun copyLiteral(
        sql: String,
        start: Int,
        out: StringBuilder,
    ): Int {
        out.append('\'')
        var i = start + 1
        while (i < sql.length) {
            out.append(sql[i])
            if (sql[i] == '\'') {
                if (i + 1 < sql.length && sql[i + 1] == '\'') {
                    out.append('\'')
                    i += 2
                    continue
                }
                return i + 1
            }
            i++
        }
        return i
    }

    private fun plain(value: Any?): Any? = when (value) {
        is Boolean -> if (value) 1L else 0L
        is Int -> value.toLong()
        else -> value
    }
}
