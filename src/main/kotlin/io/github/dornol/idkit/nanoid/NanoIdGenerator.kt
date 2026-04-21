package io.github.dornol.idkit.nanoid

import io.github.dornol.idkit.IdGenerator
import java.security.SecureRandom

/**
 * NanoID generator — compact, URL-safe, random string ids.
 *
 * Unlike the other generators in idkit (Snowflake, Flake, UUID v7, ULID), NanoID is **not
 * time-ordered**. Each id is a fixed-length sequence of characters sampled uniformly at
 * random from the configured [alphabet]. This makes NanoID well-suited to public-facing
 * identifiers — short URLs, session tokens, invite codes — where the id should leak no
 * timing information and should be hard to guess.
 *
 * With the default 21-character length and 64-character URL-safe alphabet, the collision
 * probability matches that of UUID v4 (≈ 2¹²² distinct ids; one billion ids per second for
 * ~35 years to reach a 1-in-a-billion collision chance).
 *
 * Thread-safe: the internal [SecureRandom] is thread-safe per the JDK contract (concurrent
 * calls serialize internally, but never corrupt state).
 *
 * @param size the length of each generated id in characters. Must be ≥ 1.
 * @param alphabet the set of characters to sample from. Must contain at least 2 characters.
 *   Duplicate characters in [alphabet] bias the output; it is the caller's responsibility to
 *   pass a deduplicated string.
 *
 * @since 2.1.0
 * @see <a href="https://github.com/ai/nanoid">NanoID project</a>
 */
class NanoIdGenerator(
    val size: Int = 21,
    val alphabet: String = DEFAULT_ALPHABET,
) : IdGenerator<String> {

    init {
        require(size >= 1) { "size must be >= 1, but was $size" }
        require(alphabet.length >= 2) {
            "alphabet must contain at least 2 characters, but was '$alphabet'"
        }
    }

    private val random = SecureRandom()

    override fun nextId(): String {
        val alphabetLen = alphabet.length
        val chars = CharArray(size)
        for (i in 0 until size) {
            chars[i] = alphabet[random.nextInt(alphabetLen)]
        }
        return String(chars)
    }

    companion object {
        /** Default URL-safe alphabet: `A-Z`, `a-z`, `0-9`, `_`, `-` (64 characters). */
        const val DEFAULT_ALPHABET: String =
            "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789_-"
    }
}
