import java.io.File as JFile
import java.util.zip.ZipFile

plugins {
    kotlin("multiplatform")
    kotlin("plugin.serialization")
    id("app.cash.sqldelight")
}

// Shared source lives in nyora-mac; this module compiles the JVM target only.
val macSharedSrc: String = "${rootProject.projectDir}/nyora-shared/src"

sqldelight {
    databases {
        create("NyoraDatabase") {
            packageName.set("com.nyora.hasan72341.shared.db")
            srcDirs.from(file("$macSharedSrc/commonMain/sqldelight"))
        }
    }
}

kotlin {
    jvmToolchain(17)
    jvm()

    sourceSets {
        val commonMain by getting {
            kotlin.srcDirs("$macSharedSrc/commonMain/kotlin")
            resources.srcDirs("$macSharedSrc/commonMain/resources")
            dependencies {
                implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.10.2")
                implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.9.0")
                // Mihon-compatible .tachibk backups are protobuf-encoded.
                implementation("org.jetbrains.kotlinx:kotlinx-serialization-protobuf:1.9.0")
                implementation("org.jetbrains.kotlinx:kotlinx-datetime:0.6.1")
                implementation("app.cash.sqldelight:runtime:2.1.0")
                implementation("app.cash.sqldelight:coroutines-extensions:2.1.0")
            }
        }
        val jvmMain by getting {
            kotlin.srcDirs("$macSharedSrc/jvmMain/kotlin")
            resources.srcDirs("$macSharedSrc/jvmMain/resources")
            dependencies {
                implementation("org.jetbrains.kotlinx:kotlinx-coroutines-swing:1.10.2")
                // GraalVM dropped — JS evaluation stubbed in KotatsuLoaderContext to keep
                // the hosted helper small (<500 MB) and fast to start.
                implementation("app.cash.sqldelight:sqlite-driver:2.1.0")
                // OkHttp 5 — aligned with kotatsu-parsers-redo (which targets OkHttp 5).
                implementation("com.squareup.okhttp3:okhttp:5.1.0")
                implementation("com.squareup.okhttp3:okhttp-dnsoverhttps:5.1.0")
                // Native Kotatsu parsers (kotatsu-parsers-redo) — the in-process parser engine.
                // org.json is provided explicitly (the desktop JVM, unlike Android, has no built-in org.json).
                implementation("com.github.clquwu:kotatsu-parsers-redo:59c033ecfd")
                implementation("org.json:json:20240303")
            }
        }
    }
}

// --- Fat JAR tasks (mirror nyora-mac/shared for standalone CLI / headless server use) ---

val mergedServicesDir = layout.buildDirectory.dir("merged-services").map { it.asFile }

tasks.register("mergeServiceFiles") {
    group = "build"
    val jvmMain = kotlin.targets.getByName("jvm").compilations.getByName("main")
    inputs.files(jvmMain.runtimeDependencyFiles)
    outputs.dir(mergedServicesDir)
    doLast {
        val outDir = mergedServicesDir.get()
        outDir.deleteRecursively()
        val servicesOut = JFile(outDir, "META-INF/services").apply { mkdirs() }
        val accum = mutableMapOf<String, StringBuilder>()
        jvmMain.runtimeDependencyFiles!!
            .filter { it.isFile && it.name.endsWith(".jar") }
            .forEach { jar ->
                ZipFile(jar).use { zf ->
                    val entries = zf.entries()
                    while (entries.hasMoreElements()) {
                        val entry = entries.nextElement()
                        if (!entry.isDirectory &&
                            entry.name.startsWith("META-INF/services/") &&
                            entry.name.length > "META-INF/services/".length
                        ) {
                            val key = entry.name.removePrefix("META-INF/services/")
                            val text = zf.getInputStream(entry).bufferedReader().readText()
                            accum.getOrPut(key) { StringBuilder() }.append(text).append('\n')
                        }
                    }
                }
            }
        accum.forEach { (name, content) ->
            JFile(servicesOut, name).writeText(content.toString())
        }
    }
}

tasks.register<Jar>("helperJar") {
    group = "build"
    description = "Fat JAR for headless server / CLI use."
    archiveBaseName.set("nyora-helper")
    archiveVersion.set("")
    archiveClassifier.set("")
    duplicatesStrategy = DuplicatesStrategy.EXCLUDE
    manifest {
        attributes(
            "Main-Class" to "com.nyora.hasan72341.shared.HelperMain",
            "Multi-Release" to "true",
        )
    }
    val jvmMain = kotlin.targets.getByName("jvm").compilations.getByName("main")
    dependsOn(jvmMain.compileTaskProvider, "jvmProcessResources", "mergeServiceFiles")
    from(jvmMain.output.allOutputs, mergedServicesDir)
    from(provider {
        jvmMain.runtimeDependencyFiles!!
            .filter { it.isDirectory || it.name.endsWith(".jar") }
            .map { if (it.isDirectory) it else zipTree(it) }
    }) {
        exclude(
            "META-INF/*.SF", "META-INF/*.DSA", "META-INF/*.RSA", "META-INF/*.EC",
            "META-INF/MANIFEST.MF", "META-INF/LICENSE", "META-INF/LICENSE.txt",
            "META-INF/NOTICE", "META-INF/NOTICE.txt", "META-INF/services/**",
            "module-info.class",
        )
    }
}
