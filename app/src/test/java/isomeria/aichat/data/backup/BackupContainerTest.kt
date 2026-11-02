package isomeria.aichat.data.backup

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import org.junit.Assert.*
import org.junit.Test

class BackupContainerTest {
    private val password = "fixture-password".toCharArray()
    private fun encrypt(data: ByteArray): ByteArray = ByteArrayOutputStream().also { output ->
        BackupContainer.encrypting(output, password).use { it.write(data) }
    }.toByteArray()
    private fun decrypt(data: ByteArray, key: CharArray = password): ByteArray = ByteArrayOutputStream().also {
        BackupContainer.decrypt(data.inputStream(), it, key)
    }.toByteArray()
    private fun refused(data: ByteArray) {
        var rejected = false
        try { decrypt(data) }
        catch (_: InvalidBackupException) { rejected = true }
        catch (_: WrongPasswordException) { rejected = true }
        assertTrue("Corrupted container accepted", rejected)
    }
    @Test fun roundTripsEmptyPartialExactAndMultipleChunks() {
        for (length in listOf(0, 1, 65_535, 65_536, 65_537, 196_615)) {
            val plain = ByteArray(length) { (it * 31).toByte() }
            assertArrayEquals(plain, decrypt(encrypt(plain)))
        }
    }
    @Test fun headerRecordsFixedVersionKdfAndChunkParametersWithRandomSaltAndNonce() {
        val first = encrypt(byteArrayOf(1, 2, 3))
        val second = encrypt(byteArrayOf(1, 2, 3))
        val header = ByteBuffer.wrap(first)
        assertArrayEquals(BackupContainer.MAGIC, ByteArray(8).also { header.get(it) })
        assertEquals(1, header.int)
        assertEquals(310_000, header.int)
        assertEquals(65_536, header.int)
        assertFalse(first.contentEquals(second))
        assertFalse(first.copyOfRange(20, 36).contentEquals(second.copyOfRange(20, 36)))
        assertFalse(first.copyOfRange(36, 44).contentEquals(second.copyOfRange(36, 44)))
    }
    @Test fun wrongPasswordAndModifiedHeaderAreRejected() {
        val sealed = encrypt(ByteArray(100) { it.toByte() })
        assertThrows(WrongPasswordException::class.java) { decrypt(sealed, "wrong-password".toCharArray()) }
        for (position in listOf(8, 12, 16, 20, 36)) refused(sealed.copyOf().also { it[position] = (it[position].toInt() xor 1).toByte() })
    }
    @Test fun everyTruncationBoundaryIncludingCompleteNonFinalChunksIsRejected() {
        val sealed = encrypt(ByteArray(BackupContainer.CHUNK_BYTES * 2 + 7))
        val record = 9 + BackupContainer.CHUNK_BYTES + 16
        for (length in listOf(0, 8, 43, 44, 52, 44 + record, 44 + 2 * record, sealed.size - 16, sealed.size - 1)) {
            refused(sealed.copyOf(length))
        }
    }
    @Test fun swappedOmittedDuplicatedAndSplicedChunksOrTrailingDataAreRejected() {
        val data = ByteArray(BackupContainer.CHUNK_BYTES * 2 + 7) { it.toByte() }
        val sealed = encrypt(data)
        val other = encrypt(data)
        val start = BackupContainer.HEADER_BYTES
        val record = 9 + BackupContainer.CHUNK_BYTES + 16
        val header = sealed.copyOfRange(0, start)
        val first = sealed.copyOfRange(start, start + record)
        val second = sealed.copyOfRange(start + record, start + 2 * record)
        val final = sealed.copyOfRange(start + 2 * record, sealed.size)
        refused(header + second + first + final)
        refused(header + first + final)
        refused(header + first + first + second + final)
        refused(header + other.copyOfRange(start, start + record) + second + final)
        refused(sealed + byteArrayOf(0))
        refused(sealed.copyOf().also { it[start + 4] = 1 })
        refused(sealed.copyOf().also { it[start + 8] = 1 })
        refused(sealed.copyOf().also { it[start + 9] = (it[start + 9].toInt() xor 1).toByte() })
    }
    @Test fun encryptionAndDecryptionUseBoundedChunkWritesAndReads() {
        val plain = ByteArray(BackupContainer.CHUNK_BYTES * 4 + 13) { (it * 17).toByte() }
        val encoded = ByteArrayOutputStream()
        val boundedOutput = object : java.io.FilterOutputStream(encoded) {
            override fun write(bytes: ByteArray, offset: Int, length: Int) {
                assertTrue(length <= BackupContainer.CHUNK_BYTES + 16)
                out.write(bytes, offset, length)
            }
        }
        BackupContainer.encrypting(boundedOutput, password).use { stream ->
            plain.asList().chunked(177).forEach { chunk -> stream.write(chunk.toByteArray()) }
        }
        val boundedInput = object : ByteArrayInputStream(encoded.toByteArray()) {
            override fun read(bytes: ByteArray, offset: Int, length: Int): Int {
                assertTrue(length <= BackupContainer.CHUNK_BYTES + 16)
                return super.read(bytes, offset, minOf(length, 101))
            }
        }
        val output = ByteArrayOutputStream()
        BackupContainer.decrypt(boundedInput, output, password)
        assertArrayEquals(plain, output.toByteArray())
    }
    @Test fun interruptedDecryptionPropagatesCancellation() {
        val sealed = encrypt(ByteArray(BackupContainer.CHUNK_BYTES * 2 + 7))
        var chunks = 0
        assertThrows(kotlinx.coroutines.CancellationException::class.java) {
            BackupContainer.decrypt(sealed.inputStream(), ByteArrayOutputStream(), password) {
                if (++chunks > 1) throw kotlinx.coroutines.CancellationException()
            }
        }
    }
}
