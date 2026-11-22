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

/** 用 JDK 自带的 jpackage 生成控制台版 update.exe（app-image，含精简运行时）。 */
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
    description = "构建 dist/update/update.exe（jpackage app-image）"
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
    description = "构建 jar + update.exe"
    dependsOn("packageExe")
}
