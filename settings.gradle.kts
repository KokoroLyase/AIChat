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
    }
}

rootProject.name = "AIChat"
include(":app")
