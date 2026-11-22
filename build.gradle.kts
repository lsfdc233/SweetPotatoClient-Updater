import java.net.URI
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

/** 用 jlink 生成精简 Java 运行时，作为 runtime.zip 嵌进 update.exe。 */
val jlinkExecutable: String = run {
    val candidate = File(System.getProperty("java.home"), "bin/jlink.exe")
    if (candidate.isFile) candidate.absolutePath else "jlink"
}
val embeddedRuntimeDir = layout.buildDirectory.dir("runtime")

tasks.register<Exec>("embeddedRuntime") {
    group = "distribution"
    description = "用 jlink 生成内置 Java 运行时"
    val out = embeddedRuntimeDir.get().asFile
    outputs.dir(out)
    doFirst {
        delete(out)
        out.parentFile.mkdirs()
    }
    commandLine(
        jlinkExecutable,
        "--add-modules", "java.base,java.net.http,java.logging,jdk.crypto.ec,jdk.charsets",
        "--strip-debug", "--no-header-files", "--no-man-pages", "--compress=zip-6",
        "--output", out.absolutePath
    )
}

tasks.register<Zip>("runtimeZip") {
    group = "distribution"
    description = "把内置运行时打包成 runtime.zip"
    dependsOn("embeddedRuntime")
    from(embeddedRuntimeDir)
    archiveFileName.set("runtime.zip")
    destinationDirectory.set(layout.buildDirectory.dir("runtime-zip"))
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
val minGitZip = layout.projectDirectory.file("launcher/MinGit-64-bit.zip").asFile

tasks.register("prepareMinGit") {
    group = "distribution"
    description = "准备内置 MinGit（缺失时下载）"
    outputs.file(minGitZip)
    doLast {
        if (!minGitZip.isFile) {
            logger.lifecycle("下载 MinGit 到 ${minGitZip.absolutePath}")
            minGitZip.parentFile.mkdirs()
            val url = URI("https://github.com/git-for-windows/git/releases/download/v2.56.1.windows.1/MinGit-2.56.1-64-bit.zip").toURL()
            url.openStream().use { input -> minGitZip.outputStream().use { output -> input.copyTo(output) } }
        }
    }
}
tasks.register<Exec>("buildExe") {
    group = "distribution"
    description = "生成单文件 update.exe 到 SweetPotatoClient 根目录"
    dependsOn(tasks.jar, "runtimeZip", "prepareMinGit")
    val jarFile = layout.buildDirectory.file("libs/SweetPotatoClient-Updater-${project.version}.jar").get().asFile
    val runtimeZipFile = layout.buildDirectory.file("runtime-zip/runtime.zip").get().asFile
    val launcherSource = layout.projectDirectory.file("launcher/UpdateLauncher.cs").asFile
    // -PskipClientCopy=true：只产出 dist 里的带版本号副本，不覆盖客户端根目录的 update.exe
    val skipClientCopy = (project.findProperty("skipClientCopy") as String?)?.toBoolean() ?: false
    val outExe = if (skipClientCopy) {
        layout.buildDirectory.file("exe/update.exe").get().asFile
    } else {
        clientRoot.file("update.exe").asFile
    }
    val releaseCopy = layout.projectDirectory.dir("dist").file("update-${updaterVersion}.exe").asFile

    inputs.file(jarFile)
    inputs.file(launcherSource)
    inputs.file(runtimeZipFile)
    inputs.file(minGitZip)
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
        "/resource:${runtimeZipFile.absolutePath},runtime.zip",
        "/resource:${minGitZip.absolutePath},mingit.zip",
        "/reference:System.IO.Compression.dll",
        "/reference:System.IO.Compression.FileSystem.dll",

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
