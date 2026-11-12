pluginManagement {
    repositories {
        google {
            content {
                includeGroupByRegex("com\\.android.*")
                includeGroupByRegex("com\\.google.*")
                includeGroupByRegex("androidx.*")
            }
        }
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
        // 0.7.1 离线识别：sherpa-onnx 官方 GitHub Release 里的 AAR（Maven 上没有官方发布）。
        // 只用于这一个模块；下载后由 app 的 verifySherpaOnnx 按写死的 SHA-256 核对
        exclusiveContent {
            forRepository {
                ivy {
                    name = "sherpa-onnx-releases"
                    url = uri("https://github.com/k2-fsa/sherpa-onnx/releases/download")
                    patternLayout { artifact("v[revision]/[artifact]-[revision].[ext]") }
                    metadataSources { artifact() }
                }
            }
            filter { includeModule("com.k2fsa.sherpa.onnx", "sherpa-onnx-static-link-onnxruntime") }
        }
        // 0.8.0 语音通话：Silero VAD 模型（k2-fsa 转换的 silero_vad.onnx），在 sherpa-onnx 官方 GitHub Release 的 asr-models 里。
        // 只用于这一个文件；下载后由 app 的 prepareVadAsset 按写死的 SHA-256 核对，再放进安装包的 assets
        exclusiveContent {
            forRepository {
                ivy {
                    name = "sherpa-onnx-models"
                    url = uri("https://github.com/k2-fsa/sherpa-onnx/releases/download")
                    patternLayout { artifact("[revision]/[artifact].[ext]") }
                    metadataSources { artifact() }
                }
            }
            filter { includeModule("com.k2fsa.sherpa.onnx.models", "silero_vad") }
        }
    }
}

rootProject.name = "AIChat"
include(":app")
