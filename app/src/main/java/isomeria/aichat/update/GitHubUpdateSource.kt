package isomeria.aichat.update

import isomeria.aichat.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException
import java.util.concurrent.TimeUnit
import javax.inject.Inject

/**
 * 从 GitHub 的「最新 Release」接口取版本信息。仓库公开，不需要 token，请求里也不带任何用户数据。
 *
 * 接口：GET /repos/{owner}/{repo}/releases/latest，返回最新的正式版（不含 Pre-release 和草稿）。
 * 字段见 docs.github.com/rest/releases/releases#get-the-latest-release
 */
class GitHubUpdateSource(
    client: OkHttpClient,
    private val json: Json,
    private val repo: String,
    private val apiBase: HttpUrl,
) : UpdateSource {
    @Inject constructor(client: OkHttpClient, json: Json) :
        this(client, json, BuildConfig.GITHUB_REPO, "https://api.github.com/".toHttpUrl())

    /** 模型请求的客户端不跟随重定向（保护 API Key）；这里没有凭据，允许重定向，超时也短一些 */
    private val http = client.newBuilder()
        .followRedirects(true)
        .followSslRedirects(true)
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .callTimeout(30, TimeUnit.SECONDS)
        .build()

    override suspend fun latest(): ReleaseInfo = withContext(Dispatchers.IO) {
        val url = apiBase.newBuilder()
            .addPathSegments("repos/$repo/releases/latest")
            .build()
        val request = Request.Builder()
            .url(url)
            .header("Accept", "application/vnd.github+json")
            .header("X-GitHub-Api-Version", "2022-11-28")
            .header("User-Agent", "AIChat/${BuildConfig.VERSION_NAME}")
            .build()
        http.newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw IOException("GitHub 返回 ${response.code}")
            val release = json.decodeFromString<GitHubRelease>(response.body.string())
            if (release.draft || release.prerelease) throw IOException("最新 Release 不是正式版")
            toInfo(release)
        }
    }

    private fun toInfo(release: GitHubRelease): ReleaseInfo {
        val tag = release.tagName.trim()
        // 只打开 GitHub 自己的页面
        val page = release.htmlUrl?.takeIf { it.startsWith("https://github.com/") }
            ?: "https://github.com/$repo/releases/tag/$tag"
        return ReleaseInfo(
            version = tag.removePrefix("v").removePrefix("V"),
            tag = tag,
            notes = ReleaseNotes.toPlainText(release.body.orEmpty()),
            pageUrl = page,
            apkUrl = release.assets.firstOrNull { it.name.endsWith(".apk") }?.downloadUrl,
        )
    }

    @Serializable
    private data class GitHubRelease(
        @SerialName("tag_name") val tagName: String,
        val name: String? = null,
        val body: String? = null,
        @SerialName("html_url") val htmlUrl: String? = null,
        val draft: Boolean = false,
        val prerelease: Boolean = false,
        val assets: List<GitHubAsset> = emptyList(),
    )

    @Serializable
    private data class GitHubAsset(
        val name: String,
        @SerialName("browser_download_url") val downloadUrl: String? = null,
    )
}
