package isomeria.aichat.data.backup

import java.io.DataInputStream
import java.io.EOFException
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.nio.ByteBuffer
import java.security.GeneralSecurityException
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

class EncryptedPasswordRequiredException : Exception("EncryptedPasswordRequired")

/** Outer container v1. The authenticated plaintext is the unchanged v3 backup zip. */
object BackupContainer {
    val MAGIC: ByteArray get() = "AICHATBK".toByteArray(Charsets.US_ASCII)
    const val VERSION = 1
    const val CHUNK_BYTES = 65_536
    const val HEADER_BYTES = 44
    const val RECORD_HEADER_BYTES = 9
    private const val SALT_BYTES = 16
    private const val NONCE_PREFIX_BYTES = 8
    private const val TAG_BYTES = 16
    private const val MAX_SEQUENCE = 0xffff_ffffL
    private val random = SecureRandom()

    fun isEncrypted(file: File): Boolean = file.inputStream().use { input ->
        val prefix = ByteArray(MAGIC.size)
        var count = 0
        while (count < prefix.size) {
            val read = input.read(prefix, count, prefix.size - count)
            if (read < 0) break
            count += read
        }
        count == prefix.size && prefix.contentEquals(MAGIC)
    }

    /** Owns [output]. Full chunks are non-final; close always adds an authenticated final record. */
    fun encrypting(output: OutputStream, password: CharArray): OutputStream {
        require(password.size >= PasswordCipher.MIN_PASSWORD_LENGTH)
        val salt = ByteArray(SALT_BYTES).also(random::nextBytes)
        val noncePrefix = ByteArray(NONCE_PREFIX_BYTES).also(random::nextBytes)
        val header = ByteBuffer.allocate(HEADER_BYTES).put(MAGIC).putInt(VERSION)
            .putInt(PasswordCipher.ITERATIONS).putInt(CHUNK_BYTES).put(salt).put(noncePrefix).array()
        val key = derive(password, salt)
        try {
            output.write(header)
            return EncryptingStream(output, header, noncePrefix, key)
        } catch (error: Throwable) { key.fill(0); throw error }
    }

    /** Every chunk is authenticated before it is written. The caller must discard output on any error. */
    fun decrypt(input: InputStream, output: OutputStream, password: CharArray, checkActive: () -> Unit = {}) {
        val source = DataInputStream(input)
        var key: ByteArray? = null
        try {
            val header = ByteArray(HEADER_BYTES).also(source::readFully)
            val fields = ByteBuffer.wrap(header)
            val magic = ByteArray(MAGIC.size).also { fields.get(it) }
            if (!magic.contentEquals(MAGIC) || fields.int != VERSION || fields.int != PasswordCipher.ITERATIONS || fields.int != CHUNK_BYTES)
                throw InvalidBackupException("ContainerHeader")
            val salt = ByteArray(SALT_BYTES).also { fields.get(it) }
            val prefix = ByteArray(NONCE_PREFIX_BYTES).also { fields.get(it) }
            key = derive(password, salt)
            var expected = 0L
            while (true) {
                checkActive()
                if (expected > MAX_SEQUENCE) throw InvalidBackupException("ContainerSequenceLimit")
                val sequence = source.readInt().toLong() and MAX_SEQUENCE
                val final = source.readUnsignedByte()
                val length = source.readInt()
                if (sequence != expected || final !in 0..1 || length !in 0..CHUNK_BYTES || (final == 0 && length != CHUNK_BYTES))
                    throw InvalidBackupException("ContainerRecord")
                val sealed = ByteArray(length + TAG_BYTES).also(source::readFully)
                val cipher = cipher(Cipher.DECRYPT_MODE, key, prefix, expected, header, recordHeader(expected, final == 1, length))
                val plain = try { cipher.doFinal(sealed) } catch (_: GeneralSecurityException) { throw WrongPasswordException() }
                try { output.write(plain) } finally { plain.fill(0) }
                if (final == 1) {
                    if (source.read() != -1) throw InvalidBackupException("ContainerTrailingData")
                    output.flush()
                    return
                }
                expected++
            }
        } catch (error: EOFException) { throw InvalidBackupException("ContainerTruncated", error) }
        finally { key?.fill(0) }
    }

    private fun derive(password: CharArray, salt: ByteArray): ByteArray {
        val spec = PBEKeySpec(password, salt, PasswordCipher.ITERATIONS, 256)
        try { return SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded }
        finally { spec.clearPassword() }
    }
    private fun recordHeader(sequence: Long, final: Boolean, length: Int): ByteArray = ByteBuffer.allocate(RECORD_HEADER_BYTES)
        .putInt(sequence.toInt()).put(if (final) 1.toByte() else 0.toByte()).putInt(length).array()

    private fun cipher(mode: Int, key: ByteArray, prefix: ByteArray, sequence: Long, header: ByteArray, record: ByteArray): Cipher =
        Cipher.getInstance("AES/GCM/NoPadding").apply {
            val nonce = ByteBuffer.allocate(12).put(prefix).putInt(sequence.toInt()).array()
            init(mode, SecretKeySpec(key, "AES"), GCMParameterSpec(128, nonce))
            updateAAD(header); updateAAD(record)
        }

    private class EncryptingStream(
        private val output: OutputStream, private val header: ByteArray,
        private val prefix: ByteArray, private val key: ByteArray,
    ) : OutputStream() {
        private val buffer = ByteArray(CHUNK_BYTES)
        private var used = 0
        private var sequence = 0L
        private var closed = false
        private var failed = false
        override fun write(value: Int) {
            check(!closed && !failed)
            buffer[used++] = value.toByte()
            if (used == CHUNK_BYTES) seal(final = false)
        }
        override fun write(bytes: ByteArray, offset: Int, length: Int) {
            check(!closed && !failed)
            require(offset >= 0 && length >= 0 && offset <= bytes.size - length)
            var source = offset
            var remaining = length
            while (remaining > 0) {
                val count = minOf(remaining, CHUNK_BYTES - used)
                bytes.copyInto(buffer, used, source, source + count)
                used += count; source += count; remaining -= count
                if (used == CHUNK_BYTES) seal(final = false)
            }
        }
        private fun seal(final: Boolean) {
            try {
                check(sequence <= MAX_SEQUENCE)
                val record = recordHeader(sequence, final, used)
                val cipher = cipher(Cipher.ENCRYPT_MODE, key, prefix, sequence, header, record)
                val sealed = cipher.doFinal(buffer, 0, used)
                output.write(record); output.write(sealed)
                sequence++; used = 0
            } catch (error: Throwable) { failed = true; throw error }
        }
        override fun flush() { check(!closed); output.flush() }
        override fun close() {
            if (closed) return
            closed = true
            try { if (!failed) seal(final = true) }
            finally { key.fill(0); buffer.fill(0); output.close() }
        }
    }
}
