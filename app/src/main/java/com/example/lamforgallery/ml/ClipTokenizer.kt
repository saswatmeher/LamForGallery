package com.example.lamforgallery.ml
import android.content.Context
import android.util.Log
import java.util.regex.Pattern

/**
 * A Kotlin implementation of the CLIP BPE Tokenizer.
 *
 * This version is designed to be compatible with the GPT-2-style BPE file
 * (the 262k-line file with </w> tokens) that is used by the PicQuery repository.
 *
 * It loads the "bpe_simple_vocab_16e6.txt" file from the assets folder.
 */
class ClipTokenizer(context: Context) {

    // --- Core Properties ---
    private val contextLength = 77
    private val sotToken = "<|startoftext|>"
    private val eotToken = "<|endoftext|>"

    // --- UPDATED REGEX (FIX 1) ---
    // Removed the trailing `|\s+` which was incorrectly matching spaces as separate tokens.
    private val splittingRegex: Pattern = Pattern.compile(
        "'s|'t|'re|'ve|'m|'ll|'d| ?[a-z]+| ?[0-9]+| ?[^\\s\\w]+",
        Pattern.CASE_INSENSITIVE
    )

    private val bpeCache = mutableMapOf<String, List<String>>()
    private val bpeRanks: Map<Pair<String, String>, Int>
    private val byteEncoder: Map<Byte, String>
    private val byteDecoder: Map<String, Byte>
    private val encoder: Map<String, Int> // token -> id
    private val decoder: Map<Int, String> // id -> token

    // --- Constants ---
    companion object {
        private const val BPE_MERGES_FILE = "bpe_simple_vocab_16e6.txt"
        private const val BASE_VOCAB_SIZE = 256
        private const val SPECIAL_TOKENS_SIZE = 2
        // This is the hardcoded size from the PicQuery repo
        private const val VOCAB_SIZE = 49152

        // This is unused, but let's fix it too for consistency
        private val splittingRegex = Regex(
            """<\|startoftext\|>|<\|endoftext\|>|'s|'t|'re|'ve|'m|'ll|'d| ?\p{L}+| ?\p{N}+| ?[^\s\p{L}\p{N}]+""",
            RegexOption.IGNORE_CASE
        )
    }

    /**
     * Reads a plain text file from assets line by line.
     */
    private fun loadAssetLines(context: Context): List<String> {
        return try {
            context.assets.open(BPE_MERGES_FILE).bufferedReader(Charsets.UTF_8).use {
                it.readLines()
            }
        } catch (e: Exception) {
            Log.e("ClipTokenizer", "Error loading asset $BPE_MERGES_FILE: ${e.message}")
            // Fallback in case of error
            listOf(eotToken)
        }
    }

    /**
     * Replicates the `bytes_to_unicode()` function from the original CLIP Python code.
     * Note: This is now *only* used for building the initial 256-char vocab.
     */
    private fun bytesToUnicode(): Map<Byte, String> {
        val bs = (('!'.code .. '~'.code).toList() +
                ('¡'.code .. '¬'.code).toList() +
                ('®'.code .. 'ÿ'.code).toList())

        val cs = bs.toMutableList()
        var n = 0
        for (b in 0..255) {
            if (b !in bs) {
                cs.add(256 + n)
                n++
            }
        }

        val allBytes = (0..255).map { it.toByte() }
        val allChars = cs.map { Character.toString(it) }

        return allBytes.zip(allChars).toMap()
    }

    /**
     * Helper function to get all adjacent pairs from a list.
     */
    private fun getPairs(word: List<String>): Set<Pair<String, String>> {
        if (word.size < 2) return emptySet()
        val pairs = mutableSetOf<Pair<String, String>>()
        for (i in 0 until word.size - 1) {
            pairs.add(word[i] to word[i + 1])
        }
        return pairs
    }


    init {
        // 1. Generate the 256-byte-to-unicode-char mapping
        byteEncoder = bytesToUnicode()
        byteDecoder = byteEncoder.entries.associate { (k, v) -> v to k }

        // 2. Load and parse the BPE merge rules
        val lines = loadAssetLines(context)
        Log.d("ClipTokenizer", "Loaded ${lines.size} lines from asset file.")

        if (lines.isEmpty() || lines.size == 1) {
            throw IllegalStateException("Failed to load BPE vocabulary file, or file is empty.")
        }

        val numMergesNeeded = VOCAB_SIZE - BASE_VOCAB_SIZE - SPECIAL_TOKENS_SIZE
        // Skip the first line (header) and take the required number of merge lines
        val mergeLines = lines.drop(1).take(numMergesNeeded)
        Log.d("ClipTokenizer", "Processing ${mergeLines.size} merge pairs (expected: $numMergesNeeded)")

        val merges = mergeLines.mapNotNull { line ->
            val parts = line.trim().split(" ")
            if (parts.size == 2) {
                // Store the merge pair exactly as it appears in the file
                parts[0] to parts[1]
            } else {
                Log.w("ClipTokenizer", "Skipping malformed merge line: '$line'")
                null
            }
        }

        if (merges.size != numMergesNeeded) {
            Log.w("ClipTokenizer", "Warning: Got ${merges.size} merges, expected $numMergesNeeded")
        }

        // 3. Build BPE ranks
        bpeRanks = merges.mapIndexed { i, pair -> pair to i }.toMap()

        // 4. Build the complete vocabulary list in the EXACT order
        val fullVocab = mutableListOf<String>()

        // Add 256 base tokens (the unicode chars, e.g., "!", "a", "Ġ")
        val baseVocab = (0..255).map { byteEncoder.getValue(it.toByte()) }
        fullVocab.addAll(baseVocab)

        // We must ALSO add all 256 base tokens with the end-of-word suffix,
        // as these are valid tokens (e.g., "a</w>", "b</w>", "¤</w>").
        fullVocab.addAll(baseVocab.map { it + "</w>" })

        // Add all merged tokens (e.g., "re", "the</w>")
        fullVocab.addAll(merges.map { it.first + it.second })

        // Add special tokens
        fullVocab.add(sotToken)
        fullVocab.add(eotToken)

        // 5. Create the final token -> id map
        // Remove duplicates
        encoder = fullVocab.toSet().mapIndexed { i, s -> s to i }.toMap()
        decoder = encoder.entries.associate { (k, v) -> v to k }

        Log.d("ClipTokenizer", "Vocabulary built: ${encoder.size} tokens total")
        Log.d("ClipTokenizer", "SOT token ID: ${encoder[sotToken]}")
        Log.d("ClipTokenizer", "EOT token ID: ${encoder[eotToken]}")
    }


    /**
     * Performs the Byte-Pair Encoding (BPE) algorithm on a single "word".
     * THIS IS THE RE-WRITTEN LOGIC (FIX 2)
     */
    private fun performBPE(token: String): List<String> {
        if (bpeCache.containsKey(token)) {
            return bpeCache.getValue(token)
        }

        // --- START MAJOR FIX ---
        // The file you have (e.g., "i n", "th e</w>") does NOT use the
        // byteEncoder `Ġ` logic for spaces. The regex now handles spaces.
        // We will split the word into its plain characters.
        var word: List<String> = token.map { it.toString() }
        if (word.isEmpty()) {
            return emptyList()
        }

        // 2. Add the `</w>` suffix to the *last* char
        val wordMut = word.toMutableList()
        wordMut[wordMut.size - 1] = wordMut.last() + "</w>"
        word = wordMut
        // --- END MAJOR FIX ---

        var pairs = getPairs(word)

        if (pairs.isEmpty()) {
            bpeCache[token] = word
            return word
        }

        while (true) {
            // Find the pair with the lowest rank (highest priority merge)
            val bigram = pairs.minByOrNull { bpeRanks[it] ?: Int.MAX_VALUE }

            if (bigram == null || !bpeRanks.containsKey(bigram)) {
                break // No more valid merges
            }

            val (first, second) = bigram
            val newWord = mutableListOf<String>()
            var i = 0

            while (i < word.size) {
                // Find the next occurrence of 'first'
                val j = word.subList(i, word.size).indexOf(first)

                if (j == -1) {
                    newWord.addAll(word.subList(i, word.size))
                    break
                }

                newWord.addAll(word.subList(i, i + j))
                i += j

                // Check if we can merge
                if (i < word.size - 1 && word[i] == first && word[i + 1] == second) {
                    newWord.add(first + second)
                    i += 2
                } else {
                    newWord.add(word[i])
                    i += 1
                }
            }
            // --- FIX 3 (val -> var) ---
            word = newWord
            if (word.size == 1) {
                break
            }
            pairs = getPairs(word)
        }

        bpeCache[token] = word
        return word
    }

    /**
     * Main public function.
     * Takes a raw string and returns a padded list of 77 token IDs.
     */
    fun tokenize(text: String): List<Int> {
        val sotTokenId = encoder[sotToken]
        val eotTokenId = encoder[eotToken]

        if (sotTokenId == null || eotTokenId == null) {
            Log.e("ClipTokenizer", "SOT or EOT token not found in encoder. This is a fatal error.")
            return List(contextLength) { 0 }
        }

        val tokens = mutableListOf<Int>(sotTokenId)

        // 1. Pre-process the text (lowercase)
        val cleanedText = text.lowercase() // No trim! Let the regex handle spaces

        // 2. Split into "words" using the regex
        val matcher = splittingRegex.matcher(cleanedText)
        val words = mutableListOf<String>()
        while (matcher.find()) {
            val group = matcher.group()
            if (group.isNotEmpty()) {
                words.add(group)
            }
        }

        // 3. Convert each word to BPE tokens
        for (word in words) {
            // The `performBPE` function now correctly handles the word.
            // It will convert "photo" -> ["p","h","o","t","o"] -> ["p","h","o","t","o</w>"]
            // and then merge them up based on "p h", "ph o", etc.
            val bpeTokens = performBPE(word.trim()) // Trim *here* to remove leading spaces

            // 4. Convert BPE tokens to IDs
            for (bpeToken in bpeTokens) {
                val tokenId = encoder[bpeToken]
                if (tokenId != null) {
                    tokens.add(tokenId)
                } else {
                    Log.w("ClipTokenizer", "Token '$bpeToken' NOT FOUND in encoder!")
                    // Fallback: Add unknown characters as their byte-encoded IDs
                    // This is a safety net.
                    bpeToken.toByteArray(Charsets.UTF_8).forEach { byte ->
                        val char = byteEncoder[byte]
                        if (char != null && encoder.containsKey(char)) {
                            encoder[char]?.let { tokens.add(it) }
                        } else if (char != null && encoder.containsKey("$char</w>")) {
                            encoder["$char</w>"]?.let { tokens.add(it) }
                        }
                    }
                }
            }
        }

        // 5. Add the END token
        tokens.add(eotTokenId)

        // 6. Pad the list
        val paddedTokens = tokens.toMutableList()
        while (paddedTokens.size < contextLength) {
            paddedTokens.add(eotTokenId) // Use EOT for padding
        }

        // 7. Truncate and convert to List<Long> for the ONNX model
        return paddedTokens.take(contextLength) //.map { it.toLong() }
    }
}