// Path: android/app/src/main/java/app/stash/android/Crypt15.kt
package app.stash.android

import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.io.PushbackInputStream
import java.util.zip.InflaterOutputStream
import java.util.zip.ZipException
import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/** The key does not open this backup (or the file is not a crypt15 backup / is damaged). */
class WrongKeyException(message: String) : Exception(message)

/**
 * Decrypts a WhatsApp "end-to-end encrypted backup" file (msgstore.db.crypt15) using the 64-digit key.
 * No Android classes here on purpose: it runs on a plain JVM too (tests/jvm/Crypt15Test.kt).
 *
 * File layout (verified against wa-crypt-tools):
 *   [1 byte: header size N] [optional 0x01 byte] [N bytes protobuf header, holds the 16-byte IV]
 *   [ciphertext = AES-256-GCM of a zlib stream] [16 bytes GCM tag] [16 bytes MD5]
 * Key: HMAC-SHA256(HMAC-SHA256(32 zero bytes, rootKey), "backup encryption" + 0x01).
 *
 * Memory: streams in 64 KB pieces. Java's GCM cipher would hold the whole decrypted file in RAM, so the keystream is
 * made with AES in counter mode instead (that is all GCM does to the data). The GCM tag is therefore not checked;
 * a wrong key or a damaged file is caught by the zlib stream (its header and Adler-32 checksum) and by the caller's
 * "SQLite format 3" check.
 */
object Crypt15 {
    private const val TAIL = 32 // GCM tag + MD5

    /** "ab12 cd34 ..." or "AB12CD34..." (64 hex digits, spaces/newlines allowed) -> 32 bytes, or null if it isn't one. */
    fun parseKey(text: String): ByteArray? {
        val hex = text.filter { !it.isWhitespace() && it != '-' }
        if (hex.length != 64) return null
        val out = ByteArray(32)
        for (i in 0 until 32) {
            val hi = Character.digit(hex[i * 2], 16)
            val lo = Character.digit(hex[i * 2 + 1], 16)
            if (hi < 0 || lo < 0) return null
            out[i] = ((hi shl 4) or lo).toByte()
        }
        return out
    }

    private fun hmac(key: ByteArray, vararg parts: ByteArray): ByteArray {
        val m = Mac.getInstance("HmacSHA256")
        m.init(SecretKeySpec(key, "HmacSHA256"))
        for (p in parts) m.update(p)
        return m.doFinal()
    }

    fun deriveKey(root: ByteArray): ByteArray {
        val privateKey = hmac(ByteArray(32), root)
        return hmac(privateKey, "backup encryption".toByteArray(Charsets.US_ASCII), byteArrayOf(1))
    }

    /** The IV is protobuf field 3 (c15_iv) -> its field 1 (bytes). Minimal reader: only what is needed to find it. */
    private fun findIv(pb: ByteArray): ByteArray? {
        fun varint(b: ByteArray, pos: IntArray): Long {
            var r = 0L
            var shift = 0
            while (pos[0] < b.size) {
                val v = b[pos[0]++].toInt() and 0xFF
                r = r or ((v and 0x7F).toLong() shl shift)
                if (v and 0x80 == 0) return r
                shift += 7
                if (shift > 63) break
            }
            throw WrongKeyException("not a crypt15 backup (bad header)")
        }
        val pos = intArrayOf(0)
        while (pos[0] < pb.size) {
            val tag = varint(pb, pos)
            val field = (tag shr 3).toInt()
            when ((tag and 7).toInt()) {
                0 -> varint(pb, pos)
                1 -> pos[0] += 8
                5 -> pos[0] += 4
                2 -> {
                    val len = varint(pb, pos).toInt()
                    if (len < 0 || pos[0] + len > pb.size) throw WrongKeyException("not a crypt15 backup (bad header)")
                    if (field == 3) {
                        val inner = pb.copyOfRange(pos[0], pos[0] + len)
                        val p2 = intArrayOf(0)
                        while (p2[0] < inner.size) {
                            val t2 = varint(inner, p2)
                            if ((t2 and 7).toInt() != 2) return null
                            val l2 = varint(inner, p2).toInt()
                            if (l2 < 0 || p2[0] + l2 > inner.size) return null
                            if ((t2 shr 3).toInt() == 1) return inner.copyOfRange(p2[0], p2[0] + l2)
                            p2[0] += l2
                        }
                        return null
                    }
                    pos[0] += len
                }
                else -> throw WrongKeyException("not a crypt15 backup (bad header)")
            }
        }
        return null
    }

    // ---- GCM counter start for a 16-byte IV: J0 = GHASH_H(IV || 0^64 || len(IV) in bits as 64-bit) ------------

    private fun be64(b: ByteArray, off: Int): Long {
        var r = 0L
        for (i in 0 until 8) r = (r shl 8) or (b[off + i].toLong() and 0xFF)
        return r
    }

    private fun gmul(xh: Long, xl: Long, yh: Long, yl: Long): LongArray {
        var zh = 0L
        var zl = 0L
        var vh = yh
        var vl = yl
        for (i in 0 until 128) {
            val bit = if (i < 64) (xh ushr (63 - i)) and 1L else (xl ushr (127 - i)) and 1L
            if (bit == 1L) { zh = zh xor vh; zl = zl xor vl }
            val lsb = vl and 1L
            vl = (vl ushr 1) or (vh shl 63)
            vh = vh ushr 1
            if (lsb == 1L) vh = vh xor (0xE1L shl 56)
        }
        return longArrayOf(zh, zl)
    }

    private fun j0(aesKey: ByteArray, iv: ByteArray): ByteArray {
        val ecb = Cipher.getInstance("AES/ECB/NoPadding")
        ecb.init(Cipher.ENCRYPT_MODE, SecretKeySpec(aesKey, "AES"))
        val h = ecb.doFinal(ByteArray(16))
        val hh = be64(h, 0)
        val hl = be64(h, 8)
        var y = gmul(be64(iv, 0), be64(iv, 8), hh, hl)          // (0 xor IV) * H
        y = gmul(y[0], y[1] xor 128L, hh, hl)                    // (y xor [0]64 || [128]64) * H
        val out = ByteArray(16)
        for (i in 0 until 8) {
            out[i] = (y[0] ushr (56 - 8 * i)).toByte()
            out[8 + i] = (y[1] ushr (56 - 8 * i)).toByte()
        }
        return out
    }

    /**
     * Reads [length] bytes of crypt15 from [input], writes the decompressed SQLite database to [out].
     * [length] must be the exact size of the file. Throws [WrongKeyException] if the key/file does not fit,
     * [IOException] for read/write problems, [UploadCancelled] if [cancelled] turns true.
     */
    fun decrypt(
        input: InputStream, length: Long, rootKey: ByteArray, out: OutputStream,
        cancelled: () -> Boolean, onProgress: (Long) -> Unit = {},
    ) {
        val pb = PushbackInputStream(input, 1)
        val sizeByte = pb.read()
        if (sizeByte <= 0) throw WrongKeyException("not a crypt15 backup (empty or bad header)")
        var headerLen = 1L + sizeByte
        val next = pb.read()
        if (next == 1) headerLen++ else if (next >= 0) pb.unread(next)
        val header = ByteArray(sizeByte)
        readFully(pb, header)
        val iv = findIv(header)
        if (iv == null || iv.size != 16) throw WrongKeyException("this is not a crypt15 backup (old formats are not supported)")

        val cipherLen = length - headerLen - TAIL
        if (cipherLen <= 0) throw WrongKeyException("the backup file is too short or damaged")

        val aesKey = deriveKey(rootKey)
        val start = j0(aesKey, iv)
        var counterLow = ((start[12].toInt() and 0xFF) shl 24) or ((start[13].toInt() and 0xFF) shl 16) or
            ((start[14].toInt() and 0xFF) shl 8) or (start[15].toInt() and 0xFF)
        val ecb = Cipher.getInstance("AES/ECB/NoPadding")
        ecb.init(Cipher.ENCRYPT_MODE, SecretKeySpec(aesKey, "AES"))

        val inflate = InflaterOutputStream(out)
        val buf = ByteArray(64 * 1024)
        val ctr = ByteArray(buf.size)
        var left = cipherLen
        var done = 0L
        try {
            while (left > 0) {
                if (cancelled()) throw UploadCancelled()
                // fill the whole piece: every piece except the last must be a multiple of 16 bytes, or the AES counter drifts
                val want = minOf(buf.size.toLong(), left).toInt()
                var n = 0
                while (n < want) {
                    val r = pb.read(buf, n, want - n)
                    if (r < 0) throw WrongKeyException("the backup file ended early (still being written?)")
                    n += r
                }
                val blocks = (n + 15) / 16
                for (b in 0 until blocks) {
                    counterLow++ // GCM only counts in the last 32 bits and wraps there
                    System.arraycopy(start, 0, ctr, b * 16, 12)
                    ctr[b * 16 + 12] = (counterLow ushr 24).toByte()
                    ctr[b * 16 + 13] = (counterLow ushr 16).toByte()
                    ctr[b * 16 + 14] = (counterLow ushr 8).toByte()
                    ctr[b * 16 + 15] = counterLow.toByte()
                }
                val stream = ecb.update(ctr, 0, blocks * 16)
                for (i in 0 until n) buf[i] = (buf[i].toInt() xor stream[i].toInt()).toByte()
                try {
                    inflate.write(buf, 0, n)
                } catch (e: ZipException) {
                    throw WrongKeyException("wrong key, or the backup is damaged")
                }
                left -= n
                done += n
                onProgress(done)
            }
            try {
                inflate.finish()
            } catch (e: ZipException) {
                throw WrongKeyException("wrong key, or the backup is damaged")
            }
        } finally {
            try { inflate.flush() } catch (e: IOException) { }
        }
    }

    private fun readFully(input: InputStream, b: ByteArray) {
        var off = 0
        while (off < b.size) {
            val n = input.read(b, off, b.size - off)
            if (n < 0) throw WrongKeyException("not a crypt15 backup (header cut off)")
            off += n
        }
    }
}