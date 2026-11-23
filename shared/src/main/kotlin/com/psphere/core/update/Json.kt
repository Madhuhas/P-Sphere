package com.psphere.core.update

/**
 * A small JSON reader, enough for GitHub's release list. The shared core has no dependencies by
 * design, so this replaces a JSON library. Objects become maps, arrays lists, numbers Double or
 * Long, and null stays null.
 */
internal object Json {
    class Error(message: String) : Exception(message)

    fun parse(text: String): Any? {
        val r = Reader(text)
        val v = r.value()
        r.ws()
        if (r.i != text.length) throw Error("Unexpected text at ${r.i}")
        return v
    }

    private class Reader(val s: String) {
        var i = 0

        fun ws() { while (i < s.length && s[i].isWhitespace()) i++ }

        fun value(): Any? {
            ws()
            if (i >= s.length) throw Error("Unexpected end")
            return when (val c = s[i]) {
                '{' -> obj()
                '[' -> arr()
                '"' -> str()
                't' -> word("true", true)
                'f' -> word("false", false)
                'n' -> word("null", null)
                else -> if (c == '-' || c.isDigit()) num() else throw Error("Unexpected '$c' at $i")
            }
        }

        private fun word(w: String, v: Any?): Any? {
            if (!s.startsWith(w, i)) throw Error("Unexpected text at $i")
            i += w.length
            return v
        }

        private fun obj(): Map<String, Any?> {
            val m = LinkedHashMap<String, Any?>()
            i++ // {
            ws()
            if (s.getOrNull(i) == '}') { i++; return m }
            while (true) {
                ws()
                if (s.getOrNull(i) != '"') throw Error("Expected a name at $i")
                val k = str()
                ws()
                if (s.getOrNull(i) != ':') throw Error("Expected ':' at $i")
                i++
                m[k] = value()
                ws()
                when (s.getOrNull(i)) {
                    ',' -> i++
                    '}' -> { i++; return m }
                    else -> throw Error("Expected ',' or '}' at $i")
                }
            }
        }

        private fun arr(): List<Any?> {
            val l = ArrayList<Any?>()
            i++ // [
            ws()
            if (s.getOrNull(i) == ']') { i++; return l }
            while (true) {
                l += value()
                ws()
                when (s.getOrNull(i)) {
                    ',' -> i++
                    ']' -> { i++; return l }
                    else -> throw Error("Expected ',' or ']' at $i")
                }
            }
        }

        private fun str(): String {
            val b = StringBuilder()
            i++ // opening quote
            while (true) {
                if (i >= s.length) throw Error("Unterminated string")
                when (val c = s[i++]) {
                    '"' -> return b.toString()
                    '\\' -> {
                        if (i >= s.length) throw Error("Unterminated string")
                        when (val e = s[i++]) {
                            '"', '\\', '/' -> b.append(e)
                            'b' -> b.append('\b')
                            'f' -> b.append('\u000C')
                            'n' -> b.append('\n')
                            'r' -> b.append('\r')
                            't' -> b.append('\t')
                            'u' -> {
                                if (i + 4 > s.length) throw Error("Bad escape")
                                b.append(s.substring(i, i + 4).toInt(16).toChar())
                                i += 4
                            }
                            else -> throw Error("Bad escape '\\$e'")
                        }
                    }
                    else -> b.append(c)
                }
            }
        }

        private fun num(): Any {
            val start = i
            if (s[i] == '-') i++
            while (i < s.length && (s[i].isDigit() || s[i] in ".eE+-")) i++
            val t = s.substring(start, i)
            return t.toLongOrNull() ?: t.toDoubleOrNull() ?: throw Error("Bad number '$t'")
        }
    }
}
