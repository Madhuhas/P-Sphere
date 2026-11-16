package com.psphere.desktop.smart

import java.io.File

/**
 * CLIP's byte-pair tokenizer: turns a search like "dog on the beach" into the token ids the text
 * model reads. A straight port of OpenAI's simple_tokenizer, using vocab.json and merges.txt.
 */
class ClipTokenizer(vocabJson: String, mergesTxt: String) {
    private val encoder: Map<String, Int> = parseVocab(vocabJson)
    private val ranks: Map<String, Int> = mergesTxt.lineSequence()
        .filter { it.isNotBlank() && !it.startsWith("#version") }
        .withIndex().associate { (i, line) -> line to i }
    private val byteToChar: Array<Char> = bytesToUnicode()
    private val cache = HashMap<String, List<String>>()

    val startId = encoder.getValue("<|startoftext|>")
    val endId = encoder.getValue("<|endoftext|>")

    /** Token ids including the start and end markers, at most [CONTEXT] long. */
    @Synchronized
    fun encode(text: String): LongArray {
        val clean = text.replace(Regex("\\s+"), " ").trim().lowercase()
        val ids = ArrayList<Long>()
        ids += startId.toLong()
        for (m in PATTERN.findAll(clean)) {
            val word = m.value.toByteArray(Charsets.UTF_8).map { byteToChar[it.toInt() and 0xFF] }.joinToString("")
            for (piece in bpe(word)) encoder[piece]?.let { ids += it.toLong() }
        }
        val body = ids.take(CONTEXT - 1)
        return (body + endId.toLong()).toLongArray()
    }

    private fun bpe(token: String): List<String> {
        cache[token]?.let { return it }
        // Every character is a symbol; the last one carries the end-of-word marker.
        var word = token.dropLast(1).map { it.toString() } + (token.last() + "</w>")
        while (word.size > 1) {
            var best: Pair<Int, Int>? = null // (rank, position)
            for (i in 0 until word.size - 1) {
                val r = ranks[word[i] + " " + word[i + 1]] ?: continue
                if (best == null || r < best.first) best = r to i
            }
            if (best == null) break
            val first = word[best.second]
            val second = word[best.second + 1]
            val merged = ArrayList<String>(word.size)
            var i = 0
            while (i < word.size) {
                if (i < word.size - 1 && word[i] == first && word[i + 1] == second) { merged += first + second; i += 2 }
                else { merged += word[i]; i++ }
            }
            word = merged
        }
        if (cache.size < 10_000) cache[token] = word
        return word
    }

    companion object {
        const val CONTEXT = 77
        private val PATTERN = Regex(
            "<\\|startoftext\\|>|<\\|endoftext\\|>|'s|'t|'re|'ve|'m|'ll|'d|[\\p{L}]+|[\\p{N}]|[^\\s\\p{L}\\p{N}]+",
            RegexOption.IGNORE_CASE,
        )

        fun load(dir: File) = ClipTokenizer(File(dir, "vocab.json").readText(), File(dir, "merges.txt").readText())

        /** GPT-2/CLIP's reversible map from bytes to printable characters. */
        private fun bytesToUnicode(): Array<Char> {
            val bs = ArrayList<Int>()
            bs += ('!'.code..'~'.code); bs += ('¡'.code..'¬'.code); bs += ('®'.code..'ÿ'.code)
            val cs = ArrayList(bs)
            var n = 0
            for (b in 0..255) if (b !in bs) { bs += b; cs += 256 + n; n++ }
            val out = Array(256) { ' ' }
            bs.forEachIndexed { i, b -> out[b] = cs[i].toChar() }
            return out
        }

        /** Reads a flat {"token": id, ...} JSON object without a JSON library. */
        internal fun parseVocab(json: String): Map<String, Int> {
            val map = HashMap<String, Int>(60_000)
            var i = json.indexOf('{') + 1
            val sb = StringBuilder()
            while (i < json.length) {
                while (i < json.length && json[i] != '"' && json[i] != '}') i++
                if (i >= json.length || json[i] == '}') break
                i++ // opening quote
                sb.setLength(0)
                while (json[i] != '"') {
                    val c = json[i]
                    if (c == '\\') {
                        when (val e = json[i + 1]) {
                            'u' -> { sb.append(json.substring(i + 2, i + 6).toInt(16).toChar()); i += 6; continue }
                            'n' -> sb.append('\n'); 't' -> sb.append('\t'); 'r' -> sb.append('\r')
                            'b' -> sb.append('\b'); 'f' -> sb.append('\u000C')
                            else -> sb.append(e)
                        }
                        i += 2
                    } else { sb.append(c); i++ }
                }
                i++ // closing quote
                while (json[i] != ':') i++
                i++
                while (json[i].isWhitespace()) i++
                val start = i
                while (i < json.length && (json[i].isDigit() || json[i] == '-')) i++
                map[sb.toString()] = json.substring(start, i).toInt()
            }
            return map
        }
    }
}
