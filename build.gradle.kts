plugins {
    id("java")
}

group = "top.lsfstudio"

// 版本号唯一来源：仓库根目录的 VERSION 文件（格式 v主.次.修订-构建号）
val versionFile = layout.projectDirectory.file("VERSION").asFile
val updaterVersion = versionFile.readText().trim()
val appVersion = updaterVersion.removePrefix("v").substringBefore("-")

version = updaterVersion

tasks.withType<JavaCompile>().configureEach {
    options.release = 21
    options.encoding = "UTF-8"
}

/** 把 VERSION 写进 jar 资源，Java 运行时读取，避免和构建脚本里的版本号不同步。 */
val generateVersion = tasks.register("generateVersion") {
    val out = layout.buildDirectory.file("generated/resources/version.txt")
    inputs.property("version", updaterVersion)
    outputs.file(out)
    doLast {
        val file = out.get().asFile
        file.parentFile.mkdirs()
        file.writeText(updaterVersion)
    }
}

tasks.processResources {
    dependsOn(generateVersion)
    from(layout.buildDirectory.dir("generated/resources"))
}

tasks.jar {
    manifest {
        attributes(
            "Main-Class" to "top.lsfstudio.spcupdater.Main",
            "Implementation-Title" to "SweetPotatoClient-Updater",
            "Implementation-Version" to project.version.toString()
        )
    }
}

/** 构建号 +1：vX.Y.Z-NNNNN -> vX.Y.Z-(N+1)。 */
tasks.register("bumpVersion") {
    group = "versioning"
    description = "把 VERSION 里的构建号 +1"
    doLast {
        val current = versionFile.readText().trim()
        val match = Regex("^v(\\d+)\\.(\\d+)\\.(\\d+)-(\\d+)$").find(current)
            ?: throw GradleException("VERSION 格式不合法：$current（应为 v主.次.修订-构建号）")
        val (major, minor, patch, build) = match.destructured
        val next = "v$major.$minor.$patch-" + (build.toInt() + 1).toString().padStart(5, '0')
        versionFile.writeText(next + "\n")
        logger.lifecycle("版本号：$current -> $next")
    }
}

/** 客户端根目录（本仓库的上一级）。 */
val clientRoot = layout.projectDirectory.dir("..")

/** .NET Framework 自带的 csc.exe，用来生成单文件 update.exe。 */
val cscExecutable: String = run {
    val windir = System.getenv("WINDIR") ?: "C:\\Windows"
    val candidates = listOf("Framework64", "Framework").map {
        File("$windir\\Microsoft.NET\\$it\\v4.0.30319\\csc.exe")
    }
    candidates.firstOrNull { it.isFile }?.absolutePath ?: "csc"
}

/**
 * 构建单文件 update.exe，直接输出到客户端根目录；
 * 同时复制一份带版本号的副本到 dist/，方便作为 Release 附件上传。
 */
tasks.register<Exec>("buildExe") {
    group = "distribution"
    description = "生成单文件 update.exe 到 SweetPotatoClient 根目录"
    dependsOn(tasks.jar)

    val jarFile = layout.buildDirectory.file("libs/SweetPotatoClient-Updater-${project.version}.jar").get().asFile
    val launcherSource = layout.projectDirectory.file("launcher/UpdateLauncher.cs").asFile
    val outExe = clientRoot.file("update.exe").asFile
    val releaseCopy = layout.projectDirectory.dir("dist").file("update-${updaterVersion}.exe").asFile

    inputs.file(jarFile)
    inputs.file(launcherSource)
    outputs.file(outExe)
    outputs.file(releaseCopy)

    doFirst {
        outExe.parentFile.mkdirs()
        var waited = 0
        while (outExe.exists() && waited < 5000) {
            if (outExe.delete()) {
                break
            }
            Thread.sleep(200)
            waited += 200
        }
        if (outExe.exists()) {
            throw GradleException("无法覆盖 ${outExe.absolutePath}：请先关闭正在运行的 update.exe")
        }
        logger.lifecycle("生成单文件更新器 ${updaterVersion}：${outExe.absolutePath}")
    }
    doLast {
        releaseCopy.parentFile.mkdirs()
        outExe.copyTo(releaseCopy, overwrite = true)
        logger.lifecycle("Release 副本：${releaseCopy.absolutePath}")
    }
    commandLine(
        cscExecutable,
        "/nologo",
        "/target:exe",
        "/platform:anycpu",
        "/optimize+",
        "/out:${outExe.absolutePath}",
        "/resource:${jarFile.absolutePath},update.jar",
        launcherSource.absolutePath
    )
}

tasks.named("assemble") {
    dependsOn("buildExe")
}

/** 可选：用 jpackage 生成带运行时的 app-image（dist/update/update.exe）。 */
val jpackageExecutable: String = run {
    val fromEnv = System.getenv("JPACKAGE")
    if (!fromEnv.isNullOrBlank()) {
        fromEnv
    } else {
        val candidate = File(System.getProperty("java.home"), "bin/jpackage.exe")
        if (candidate.isFile) candidate.absolutePath else "jpackage"
    }
}

val jpackageInput = layout.buildDirectory.dir("jpackage-input")
tasks.register<Exec>("packageExe") {
    group = "distribution"
    description = "构建 dist/update/update.exe（jpackage app-image，含精简运行时）"
    dependsOn(tasks.jar)
    doFirst {
        delete(layout.projectDirectory.dir("dist/update"), jpackageInput)
        val jarFile = layout.buildDirectory.file("libs/SweetPotatoClient-Updater-${project.version}.jar").get().asFile
        val inputDir = jpackageInput.get().asFile
        inputDir.mkdirs()
        jarFile.copyTo(inputDir.resolve(jarFile.name), overwrite = true)
    }
    commandLine(
        jpackageExecutable,
        "--type", "app-image",
        "--name", "update",
        "--dest", layout.projectDirectory.dir("dist").asFile.absolutePath,
        "--input", jpackageInput.get().asFile.absolutePath,
        "--main-jar", "SweetPotatoClient-Updater-${project.version}.jar",
        "--main-class", "top.lsfstudio.spcupdater.Main",
        "--win-console",
        "--add-modules", "java.base,java.net.http,java.logging",
        "--vendor", "lsfdc",
        "--description", "SweetPotatoClient Updater",
        "--app-version", appVersion
    )
}

tasks.register("dist") {
    group = "distribution"
    description = "构建 update.exe（单文件，输出到客户端根目录 + dist 版本化副本）"
    dependsOn("buildExe")
}
