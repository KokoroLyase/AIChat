package isomeria.aichat.update

/**
 * 语义化版本。检查更新时比较 GitHub Release 的 tag 和当前版本：
 * 去掉 v 前缀后按数字逐段比较，不用字符串比较（否则 0.10.0 会小于 0.9.0）。
 */
data class SemVer(
    val major: Int,
    val minor: Int,
    val patch: Int,
    /** 预发布标识，如 beta.1；正式版为 null */
    val preRelease: String? = null,
) : Comparable<SemVer> {

    override fun compareTo(other: SemVer): Int {
        compareValues(major, other.major).takeIf { it != 0 }?.let { return it }
        compareValues(minor, other.minor).takeIf { it != 0 }?.let { return it }
        compareValues(patch, other.patch).takeIf { it != 0 }?.let { return it }
        return comparePreRelease(preRelease, other.preRelease)
    }

    override fun toString(): String = "$major.$minor.$patch" + (preRelease?.let { "-$it" } ?: "")

    companion object {
        private val pattern = Regex("""^[vV]?(\d+)\.(\d+)\.(\d+)(?:-([0-9A-Za-z.-]+))?(?:\+[0-9A-Za-z.-]+)?$""")

        fun parse(text: String): SemVer? {
            val match = pattern.matchEntire(text.trim()) ?: return null
            val (major, minor, patch, pre) = match.destructured
            return SemVer(
                major.toIntOrNull() ?: return null,
                minor.toIntOrNull() ?: return null,
                patch.toIntOrNull() ?: return null,
                pre.ifEmpty { null },
            )
        }

        /** 正式版大于同号的预发布版；预发布标识逐段比较，数字段按数值比较 */
        private fun comparePreRelease(a: String?, b: String?): Int {
            if (a == b) return 0
            if (a == null) return 1
            if (b == null) return -1
            val partsA = a.split('.')
            val partsB = b.split('.')
            for (i in 0 until minOf(partsA.size, partsB.size)) {
                val x = partsA[i]
                val y = partsB[i]
                val nx = x.toIntOrNull()
                val ny = y.toIntOrNull()
                val result = when {
                    nx != null && ny != null -> compareValues(nx, ny)
                    nx != null -> -1
                    ny != null -> 1
                    else -> x.compareTo(y)
                }
                if (result != 0) return result
            }
            return compareValues(partsA.size, partsB.size)
        }
    }
}

/** 是否有新版本：远端 tag 能解析且大于当前版本。 */
fun isNewerVersion(remoteTag: String, currentVersion: String): Boolean {
    val remote = SemVer.parse(remoteTag) ?: return false
    val current = SemVer.parse(currentVersion.substringBefore("-debug")) ?: return false
    return remote > current
}
