plugins {
    id("java")
}

group = "top.lsfstudio"
version = "v0.0.1-00001"

tasks.withType<JavaCompile>().configureEach {
    options.release = 21
    options.encoding = "UTF-8"
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
 * 构建单文件 update.exe，直接输出到客户端根目录。
 * 内置 update.jar，运行时使用客户端自带的 JDK（jdk-<version>/bin/java.exe）。
 */
tasks.register<Exec>("buildExe") {
    group = "distribution"
    description = "生成单文件 update.exe 到 SweetPotatoClient 根目录"
    dependsOn(tasks.jar)

    val jarFile = layout.buildDirectory.file("libs/SweetPotatoClient-Updater-${project.version}.jar").get().asFile
    val launcherSource = layout.projectDirectory.file("launcher/UpdateLauncher.cs").asFile
    val outExe = clientRoot.file("update.exe").asFile

    inputs.file(jarFile)
    inputs.file(launcherSource)
    outputs.file(outExe)

    doFirst {
        outExe.parentFile.mkdirs()
        logger.lifecycle("生成单文件更新器：${outExe.absolutePath}")
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

tasks.register<Exec>("packageExe") {
    group = "distribution"
    description = "构建 dist/update/update.exe（jpackage app-image，含精简运行时）"
    dependsOn(tasks.jar)
    doFirst {
        delete(layout.projectDirectory.dir("dist"))
    }
    commandLine(
        jpackageExecutable,
        "--type", "app-image",
        "--name", "update",
        "--dest", layout.projectDirectory.dir("dist").asFile.absolutePath,
        "--input", layout.buildDirectory.dir("libs").get().asFile.absolutePath,
        "--main-jar", "SweetPotatoClient-Updater-${project.version}.jar",
        "--main-class", "top.lsfstudio.spcupdater.Main",
        "--win-console",
        "--add-modules", "java.base,java.net.http,java.logging",
        "--vendor", "lsfdc",
        "--description", "SweetPotatoClient Updater",
        "--app-version", "0.0.1"
    )
}

tasks.register("dist") {
    group = "distribution"
    description = "构建 update.exe（单文件，输出到客户端根目录）"
    dependsOn("buildExe")
}
