pluginManagement {
    repositories {
        // 优先使用官方插件门户和 Maven 中央仓库
        gradlePluginPortal()
        mavenCentral()

        // 阿里云镜像加速
        maven { url = uri("https://maven.aliyun.com/repository/google") }
        maven { url = uri("https://maven.aliyun.com/repository/public") }

        // Shizuku 官方仓库
        maven { url = uri("https://maven.rikka.app/") }

        google()
    }
}

// ★ 已删除导致报错的 foojay-resolver-convention 插件

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        // 阿里云镜像加速
        maven { url = uri("https://maven.aliyun.com/repository/google") }
        maven { url = uri("https://maven.aliyun.com/repository/public") }

        // Shizuku 官方仓库
        maven { url = uri("https://maven.rikka.app/") }

        google()
        mavenCentral()
    }
}

rootProject.name = "TaskManager"
include(":app")