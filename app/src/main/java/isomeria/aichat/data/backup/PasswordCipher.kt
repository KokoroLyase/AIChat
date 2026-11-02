package isomeria.aichat.data.backup

import java.security.GeneralSecurityException
import java.security.SecureRandom
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

class WrongPasswordException : Exception("密码错误")

/**
 * 用密码加密导出文件里的 API Key：PBKDF2-HMAC-SHA256 派生 256 位密钥，AES-GCM 加密。
 * 每次加密都用新的随机盐和 IV；GCM 的认证标签保证密码错误或内容被改动时解密失败，而不是得到乱码。
 */
object PasswordCipher {
    /** PBKDF2 迭代次数。写进文件里，以后调整不影响旧文件 */
    const val ITERATIONS = 310_000
    const val MIN_PASSWORD_LENGTH = 6

    private const val KDF = "PBKDF2WithHmacSHA256"
    private const val TRANSFORMATION = "AES/GCM/NoPadding"
    private const val KEY_BITS = 256
    private const val SALT_BYTES = 16
    private const val IV_BYTES = 12
    private const val TAG_BITS = 128

    /** 附加认证数据：把密文和用途绑定，防止被挪作他用 */
    private val aad = "AIChat backup api key v1".toByteArray(Charsets.UTF_8)

    private val random = SecureRandom()
    private val base64Encoder = Base64.getEncoder()
    private val base64Decoder = Base64.getDecoder()

    fun encrypt(plain: String, password: CharArray, iterations: Int = ITERATIONS): SecretDto {
        val salt = ByteArray(SALT_BYTES).also(random::nextBytes)
        val iv = ByteArray(IV_BYTES).also(random::nextBytes)
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, deriveKey(password, salt, iterations), GCMParameterSpec(TAG_BITS, iv))
        cipher.updateAAD(aad)
        val sealed = cipher.doFinal(plain.toByteArray(Charsets.UTF_8))
        return SecretDto(
            scheme = SecretDto.SCHEME_PASSWORD,
            iterations = iterations,
            salt = base64Encoder.encodeToString(salt),
            iv = base64Encoder.encodeToString(iv),
            data = base64Encoder.encodeToString(sealed),
        )
    }

    /** 密码错误时抛 [WrongPasswordException]；文件内容不完整时抛 [IllegalArgumentException] */
    fun decrypt(secret: SecretDto, password: CharArray): String {
        require(secret.scheme == SecretDto.SCHEME_PASSWORD) { "不支持的加密方式：${secret.scheme}" }
        require(secret.iterations in 1..10_000_000) { "迭代次数无效" }
        val salt = base64Decoder.decode(requireNotNull(secret.salt) { "缺少 salt" })
        val iv = base64Decoder.decode(requireNotNull(secret.iv) { "缺少 iv" })
        val sealed = base64Decoder.decode(secret.data)
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.DECRYPT_MODE, deriveKey(password, salt, secret.iterations), GCMParameterSpec(TAG_BITS, iv))
        cipher.updateAAD(aad)
        val plain = try {
            cipher.doFinal(sealed)
        } catch (_: GeneralSecurityException) {
            throw WrongPasswordException()
        }
        return String(plain, Charsets.UTF_8)
    }

    private fun deriveKey(password: CharArray, salt: ByteArray, iterations: Int): SecretKeySpec {
        val spec = PBEKeySpec(password, salt, iterations, KEY_BITS)
        try {
            val bytes = SecretKeyFactory.getInstance(KDF).generateSecret(spec).encoded
            return SecretKeySpec(bytes, "AES")
        } finally {
            spec.clearPassword()
        }
    }
}
