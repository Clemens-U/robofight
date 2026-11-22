package robofight.fireline.web

import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi
import robofight.fireline.Mission
import robofight.fireline.WebInputLimits

/**
 * `#run=` share permalinks of the web console (docs/FIRELINE-WEB.md §8 "Share
 * links", scope decided in §12a D4): mission id, seed override, firmware name
 * and firmware source, encoded gzip-then-base64url behind the versioned prefix
 * `FLW1-`, always placed in the URL **fragment** so the payload is never sent
 * to the server and never lands in an access log.
 *
 * The link is a reproduction recipe, not a saved game (§8): opening it re-runs
 * the simulation from the decoded descriptor. Decoding re-enters through the
 * same web input gate every other browser path uses (`WebInputLimits`), so a
 * hostile link cannot allocate past the source ceilings.
 *
 * Deterministic, pure Kotlin (wasm-safe): the payload is a fixed big-endian
 * byte layout, the compression is RFC 1950 DEFLATE built only from stored
 * blocks (RFC 1952 section 2.4: the null compression), the CRC is CRC-32 over
 * the uncompressed payload, and the text encoding is `encodeToByteArray()`
 * (UTF-8 by definition in the common stdlib; no `Charsets` on wasm). A stored
 * block is byte-exact reproducible on every target, which is what makes the
 * cross-check with Python's `gzip` (docs/web/verify_permalink.py) meaningful:
 * the pinned fixture permalink is produced by Python and decoded here, and the
 * permalink produced here is decompressed and payload-checked by Python.
 */
object Permalink {

    /** Versioned prefix of §8 ("`FLW1-`"); a link with any other prefix is refused. */
    const val PREFIX = "FLW1-"

    /** Payload format version byte (byte 0 of the uncompressed payload). */
    private const val FORMAT_VERSION = 1

    /** Firmware name ceiling in the payload (bytes of UTF-8). */
    const val MAX_NAME_BYTES = 64

    /** Seed sentinel meaning "the mission's authored seed" (authored seeds are > 0). */
    const val AUTHORED_SEED = 0

    // ------------------------------------------------------------------ encode

    /** Build the full fragment string `#run=FLW1-<base64url>` for one console run. */
    fun fragmentFor(mission: Mission, firmwareName: String, firmwareSource: String, seedOverride: Int = AUTHORED_SEED): String =
        "#" + FRAGMENT_KEY + "=" + encode(mission, firmwareName, firmwareSource, seedOverride)

    /** The console's fragment key (`#run=` of §8). */
    const val FRAGMENT_KEY = "run"

    /** `FLW1-<base64url>` (no `#run=` wrapper). Throws when the inputs exceed the ceilings. */
    fun encode(mission: Mission, firmwareName: String, firmwareSource: String, seedOverride: Int = AUTHORED_SEED): String {
        val nameBytes = firmwareName.encodeToByteArray()
        val srcBytes = firmwareSource.encodeToByteArray()
        require(nameBytes.isNotEmpty() && nameBytes.size <= MAX_NAME_BYTES) {
            "permalink firmware name must be 1..$MAX_NAME_BYTES bytes"
        }
        val problems = WebInputLimits.sourceProblems(firmwareSource)
        require(problems.isEmpty()) { "permalink source refused: ${problems.joinToString(" | ")}" }
        require(seedOverride == AUTHORED_SEED || seedOverride > 0) { "permalink seed override must be > 0" }

        val payload = ArrayList<Byte>(16 + nameBytes.size + srcBytes.size)
        putBE(payload, FORMAT_VERSION, 1)
        putBE(payload, mission.id, 4)
        putBE(payload, seedOverride, 4)
        putBE(payload, nameBytes.size, 2)
        payload.addAll(nameBytes.toList())
        payload.addAll(srcBytes.toList())

        val deflated = deflateStored(payload)
        val gz = ArrayList<Byte>(payload.size + deflated.size + 18)
        gz.add(0x1f.toByte())
        gz.add(0x8b.toByte()) // RFC 1952 magic
        gz.add(8.toByte())    // CM 8: DEFLATE
        gz.add(0.toByte())    // FLG 0: no optional fields
        putBE(gz, 0, 4) // MTIME 0 (fixed: the link must be reproducible)
        gz.add(0.toByte())    // XFL
        gz.add(3.toByte())    // OS 3: Unix
        gz.addAll(deflated)
        putRawLE32(gz, crc32(payload))
        putRawLE32(gz, payload.size) // ISIZE mod 2^32

        return PREFIX + base64UrlNoPadding(gz.toByteArray())
    }

    // ------------------------------------------------------------------ decode

    /** Result of decoding a fragment: either a checked recipe or the refusal reasons. */
    sealed class Decoded {
        data class Ok(
            val missionId: Int,
            val seedOverride: Int,
            val firmwareName: String,
            val firmwareSource: String,
        ) : Decoded()

        data class Refused(val problems: List<String>) : Decoded()
    }

    /** Accepts `#run=FLW1-...`, `run=FLW1-...` or the bare `FLW1-...` form. */
    fun decode(link: String, knownMissionIds: Set<Int>): Decoded {
        var rest = link.trim()
        if (rest.startsWith("#")) rest = rest.substring(1)
        if (rest.startsWith(FRAGMENT_KEY + "=")) rest = rest.substring(FRAGMENT_KEY.length + 1)
        if (!rest.startsWith(PREFIX)) {
            return Decoded.Refused(listOf("not a $PREFIX permalink"))
        }
        val body = rest.substring(PREFIX.length)
        val gz = try {
            base64UrlDecode(body)
        } catch (e: IllegalArgumentException) {
            return Decoded.Refused(listOf("permalink is not valid base64url"))
        }
        if (gz.size < 18 || (gz[0].toInt() and 0xFF) != 0x1f || (gz[1].toInt() and 0xFF) != 0x8b || (gz[2].toInt() and 0xFF) != 8) {
            return Decoded.Refused(listOf("permalink is not a gzip stream"))
        }
        val deflated = gz.copyOfRange(10, gz.size - 8)
        val expectedCrc = readLE(gz, gz.size - 8, 4)
        val expectedSize = readLE(gz, gz.size - 4, 4)
        val payload = try {
            inflateStored(deflated)
        } catch (e: IllegalStateException) {
            return Decoded.Refused(listOf(e.message ?: "permalink deflate stream refused"))
        }
        if (payload.size != expectedSize) {
            return Decoded.Refused(listOf("permalink payload size mismatch"))
        }
        if (crc32(payload).toULong() != expectedCrc.toULong()) {
            return Decoded.Refused(listOf("permalink CRC mismatch"))
        }
        if (payload.isEmpty() || (payload[0].toInt() and 0xFF) != FORMAT_VERSION) {
            return Decoded.Refused(listOf("unsupported permalink payload version"))
        }
        var pos = 1
        if (payload.size < pos + 11) return Decoded.Refused(listOf("permalink payload truncated"))
        val missionId = readBE(payload, pos, 4); pos += 4
        val seedOverride = readBE(payload, pos, 4); pos += 4
        val nameLen = readBE(payload, pos, 2); pos += 2
        if (nameLen < 1 || nameLen > MAX_NAME_BYTES || payload.size < pos + nameLen) {
            return Decoded.Refused(listOf("permalink name length refused"))
        }
        val firmwareName = bytesOf(payload, pos, nameLen).decodeToString()
        pos += nameLen
        val firmwareSource = bytesOf(payload, pos, payload.size - pos).decodeToString()
        val problems = ArrayList<String>()
        if (missionId !in knownMissionIds) problems.add("permalink mission id $missionId is not authored")
        if (seedOverride != AUTHORED_SEED && seedOverride <= 0) problems.add("permalink seed override refused")
        problems.addAll(WebInputLimits.sourceProblems(firmwareSource))
        return if (problems.isEmpty()) {
            Decoded.Ok(missionId, seedOverride, firmwareName, firmwareSource)
        } else {
            Decoded.Refused(problems)
        }
    }

    // ------------------------------------------------- gzip/DEFLATE (stored only)

    /** One stored DEFLATE block carries at most this many payload bytes. */
    private const val STORED_BLOCK_MAX = 512

    /** RFC 1952 2.4: DEFLATE made only of stored blocks plus the empty final block. */
    private fun deflateStored(payload: List<Byte>): List<Byte> {
        val out = ArrayList<Byte>(payload.size + payload.size / STORED_BLOCK_MAX * 5 + 3)
        var pos = 0
        while (pos < payload.size) {
            val len = minOf(STORED_BLOCK_MAX, payload.size - pos)
            out.add(0.toByte()) // BFINAL 0, BTYPE 00 (stored)
            putRawLE(out, len)
            putRawLE(out, len.inv() and 0xFFFF) // NLEN: one's complement of LEN
            out.addAll(payload.subList(pos, pos + len))
            pos += len
        }
        out.add(1.toByte()) // BFINAL 1
        putRawLE(out, 0)
        putRawLE(out, 0xFFFF)
        return out
    }

    /** Decompress a DEFLATE stream that uses stored blocks only (our own format;
     *  compressed blocks are refused, which keeps the decoder 60 lines instead
     *  of a full Huffman machine). */
    private fun inflateStored(deflated: ByteArray): List<Byte> {
        val out = ArrayList<Byte>()
        var pos = 0
        var finalBlock = false
        while (!finalBlock) {
            if (pos + 5 > deflated.size) throw IllegalStateException("permalink deflate stream truncated")
            val header = deflated[pos].toInt()
            finalBlock = header and 1 == 1
            val btype = (header ushr 1) and 3
            if (btype != 0) throw IllegalStateException("permalink uses compressed deflate blocks, not the FLW1 stored form")
            pos += 1
            val len = deflated[pos].toInt() and 0xFF or ((deflated[pos + 1].toInt() and 0xFF) shl 8)
            val nlen = deflated[pos + 2].toInt() and 0xFF or ((deflated[pos + 3].toInt() and 0xFF) shl 8)
            pos += 4
            if ((len.inv() and 0xFFFF) != nlen) throw IllegalStateException("permalink LEN/NLEN mismatch")
            if (len == 0) continue
            if (pos + len > deflated.size) throw IllegalStateException("permalink deflate stream truncated")
            out.addAll(deflated.copyOfRange(pos, pos + len).toList())
            pos += len
            if (out.size > WebInputLimits.MAX_SOURCE_BYTES + 128) {
                throw IllegalStateException("permalink payload exceeds the source ceiling")
            }
        }
        return out
    }

    // ----------------------------------------------------------- CRC-32 + bytes

    /** Standard CRC-32 (the value gzip stores), table built once at first use. */
    private fun crc32(bytes: List<Byte>): Int {
        val table = crcTable()
        var crc = -1 // 0xFFFFFFFF
        for (b in bytes) {
            crc = (crc ushr 8) xor table[(crc xor b.toInt()) and 0xFF]
        }
        return crc.inv()
    }

    private var crcTableCache: IntArray? = null

    private fun crcTable(): IntArray {
        crcTableCache?.let { return it }
        val table = IntArray(256)
        for (i in 0 until 256) {
            var c = i
            repeat(8) { c = if (c and 1 != 0) (c ushr 1) xor 0xEDB88320.toInt() else c ushr 1 }
            table[i] = c
        }
        crcTableCache = table
        return table
    }

    /** Big-endian unsigned integer of [width] bytes (network byte order, like the gzip header). */
    private fun putBE(out: MutableList<Byte>, value: Int, width: Int) {
        for (shift in (width - 1) downTo 0) {
            out.add(((value ushr (shift * 8)) and 0xFF).toByte())
        }
    }

    /** Little-endian 16-bit length pair (DEFLATE stores LEN/NLEN LE, unlike gzip's BE header fields). */
    private fun putRawLE(out: MutableList<Byte>, value: Int) {
        out.add((value and 0xFF).toByte())
        out.add(((value ushr 8) and 0xFF).toByte())
    }

    /** Little-endian 32-bit field (RFC 1952: CRC32 and ISIZE are LE, MTIME is BE). */
    private fun putRawLE32(out: MutableList<Byte>, value: Int) {
        out.add((value and 0xFF).toByte())
        out.add(((value ushr 8) and 0xFF).toByte())
        out.add(((value ushr 16) and 0xFF).toByte())
        out.add(((value ushr 24) and 0xFF).toByte())
    }

    private fun readLE(bytes: ByteArray, offset: Int, width: Int): Int {
        var v = 0
        for (i in 0 until width) {
            v = v or ((bytes[offset + i].toInt() and 0xFF) shl (i * 8))
        }
        return v
    }

    private fun readBE(bytes: List<Byte>, offset: Int, width: Int): Int {
        var v = 0
        for (i in 0 until width) {
            v = (v shl 8) or (bytes[offset + i].toInt() and 0xFF)
        }
        return v
    }

    /** Copy `width` bytes out of a `List<Byte>` payload window. */
    private fun bytesOf(bytes: List<Byte>, offset: Int, width: Int): ByteArray {
        val out = ByteArray(width)
        for (i in 0 until width) out[i] = bytes[offset + i]
        return out
    }

    // ---------------------------------------------------------------- base64url

    /** RFC 4648 base64url alphabet, no padding (`A-Za-z0-9-_`). Written here
     *  rather than via `kotlin.io.encoding` so encode and decode are pinned to
     *  one implementation on every Kotlin/Wasm release. */
    private const val BASE64URL_ALPHABET = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-_"

    private fun base64UrlNoPadding(bytes: ByteArray): String {
        val sb = StringBuilder((bytes.size + 2) / 3 * 4)
        var i = 0
        while (i + 2 < bytes.size) {
            val n = ((bytes[i].toInt() and 0xFF) shl 16) or
                ((bytes[i + 1].toInt() and 0xFF) shl 8) or
                (bytes[i + 2].toInt() and 0xFF)
            sb.append(BASE64URL_ALPHABET[(n ushr 18) and 63])
            sb.append(BASE64URL_ALPHABET[(n ushr 12) and 63])
            sb.append(BASE64URL_ALPHABET[(n ushr 6) and 63])
            sb.append(BASE64URL_ALPHABET[n and 63])
            i += 3
        }
        val rest = bytes.size - i
        if (rest == 1) {
            val n = bytes[i].toInt() and 0xFF
            sb.append(BASE64URL_ALPHABET[(n ushr 2) and 63])
            sb.append(BASE64URL_ALPHABET[(n shl 4) and 63])
        } else if (rest == 2) {
            val n = ((bytes[i].toInt() and 0xFF) shl 8) or (bytes[i + 1].toInt() and 0xFF)
            sb.append(BASE64URL_ALPHABET[(n ushr 10) and 63])
            sb.append(BASE64URL_ALPHABET[(n ushr 4) and 63])
            sb.append(BASE64URL_ALPHABET[(n shl 2) and 63])
        }
        return sb.toString()
    }

    /** Strict decode: exactly the unpadded base64url forms (length % 4 of 0, 2
     *  or 3; a remainder of 1 is not a base64 length). Any character outside the
     *  alphabet throws. */
    private fun base64UrlDecode(text: String): ByteArray {
        if (text.isEmpty()) return ByteArray(0)
        val rem = text.length % 4
        if (rem == 1) throw IllegalArgumentException("invalid base64url length")
        val groups = text.length / 4
        val out = ByteArray(groups * 3 + if (rem == 2) 1 else if (rem == 3) 2 else 0)
        var o = 0
        for (g in 0 until groups) {
            val a = charValue(text, g * 4)
            val b = charValue(text, g * 4 + 1)
            val c = charValue(text, g * 4 + 2)
            val d = charValue(text, g * 4 + 3)
            val n = (a shl 18) or (b shl 12) or (c shl 6) or d
            out[o++] = ((n ushr 16) and 0xFF).toByte()
            out[o++] = ((n ushr 8) and 0xFF).toByte()
            out[o] = (n and 0xFF).toByte()
            o++
        }
        if (rem >= 2) {
            val a = charValue(text, groups * 4)
            val b = charValue(text, groups * 4 + 1)
            out[o++] = (((a shl 2) or (b shr 4)) and 0xFF).toByte()
            if (rem == 3) {
                val c = charValue(text, groups * 4 + 2)
                out[o] = (((b shl 4) or (c shr 2)) and 0xFF).toByte()
            }
        }
        return out
    }

    private fun charValue(text: String, index: Int): Int {
        val v = BASE64URL_ALPHABET.indexOf(text[index].toString())
        if (v < 0) throw IllegalArgumentException("invalid base64url character '${text[index]}'")
        return v
    }
}
