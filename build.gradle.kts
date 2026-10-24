plugins {
    alias(libs.plugins.fabric.loom)
    alias(libs.plugins.kotlin.jvm)
}

base {
    archivesName = project.property("archives_base_name") as String
    // The Minecraft version the add-on is built for goes into its version, e.g. 1.0.0+26.3
    version = "${project.property("mod_version")}+${libs.versions.minecraft.get()}"
    group = project.property("maven_group") as String
}

repositories {
    mavenCentral()
    // Lets you test against a locally built client (`./gradlew publishToMavenLocal` in LiquidBounce).
    mavenLocal()
    maven {
        name = "CCBlueX Releases"
        url = uri("https://maven.ccbluex.net/releases")
    }
    maven {
        name = "CCBlueX Snapshots"
        url = uri("https://maven.ccbluex.net/snapshots")
    }
    maven {
        name = "Fabric"
        url = uri("https://maven.fabricmc.net/")
    }
}

// The pages are drawn by the web view of Android, so the access widener only covers the client's integration API.
loom {
    accessWidenerPath = file("src/main/resources/liquidbounce-droidbridge.accesswidener")
}

// Two things to leave alone here:
//
// 1. There is no `mappings(...)` line. LiquidBounce declares none either, and Loom defaults to
//    Mojang official mappings for this Minecraft version. A different mapping set produces an
//    add-on that compiles and then fails on every Minecraft call.
// 2. Dependencies use plain `implementation`, not `modImplementation`. This Loom version has no
//    remapping step - the development and production namespaces are both Mojang official - so the
//    `mod*` configurations do not exist. LiquidBounce's own build does the same.
dependencies {
    minecraft(libs.minecraft)

    implementation(libs.fabric.loader)
    implementation(libs.fabric.api)
    implementation(libs.fabric.kotlin)

    // The client itself; there is no separate API artifact.
    implementation(libs.liquidbounce)
}

// Gradle keeps a resolved snapshot for a day; the client publishes one on every push to nextgen.
configurations.all {
    resolutionStrategy.cacheChangingModulesFor(0, "seconds")
}

tasks.processResources {
    val modVersion = providers.gradleProperty("mod_version").zip(libs.versions.minecraft) { version, minecraft ->
        "$version+$minecraft"
    }
    val minecraftVersion = libs.versions.minecraft
    val loaderVersion = libs.versions.fabric.loader
    val fabricKotlinVersion = libs.versions.fabric.kotlin

    inputs.property("version", modVersion)
    inputs.property("minecraft_version", minecraftVersion)
    inputs.property("loader_version", loaderVersion)
    inputs.property("fabric_kotlin_version", fabricKotlinVersion)

    filesMatching("fabric.mod.json") {
        expand(
            mapOf(
                "version" to modVersion.get(),
                "minecraft_version" to minecraftVersion.get(),
                "loader_version" to loaderVersion.get(),
                "fabric_kotlin_version" to fabricKotlinVersion.get(),
            )
        )
    }
}

// The native library goes into the jar under natives/<os>-<arch>/. CI builds it and passes the directory with
// -Pnatives=<dir>; without it, the library is built with cargo for this machine only.
val nativesDir = providers.gradleProperty("natives").map { file(it) }
val hostPlatform = "android-" + when (System.getProperty("os.arch")) {
    "amd64", "x86_64" -> "x64"
    "aarch64", "arm64" -> "arm64"
    else -> System.getProperty("os.arch")
}
val nativeLibrary = "libliquidbounce_droidbridge.so"

val cargoBuild = tasks.register<Exec>("cargoBuild") {
    description = "Builds the native library for this machine."
    onlyIf { !nativesDir.isPresent }
    workingDir = file("native")
    commandLine("cargo", "build", "--release")
    inputs.dir("native/src")
    inputs.files("native/Cargo.toml", "native/Cargo.lock")
    outputs.file("native/target/release/$nativeLibrary")
}

val collectNatives = tasks.register<Sync>("collectNatives") {
    dependsOn(cargoBuild)
    into(layout.buildDirectory.dir("natives"))
    if (nativesDir.isPresent) {
        from(nativesDir)
    } else {
        from("native/target/release/$nativeLibrary") { into(hostPlatform) }
    }
}

tasks.processResources {
    from(collectNatives) { into("natives") }
}

tasks.withType<JavaCompile>().configureEach {
    options.encoding = "UTF-8"
    options.release = libs.versions.jdk.get().toInt()
}

java {
    withSourcesJar()

    toolchain {
        languageVersion = JavaLanguageVersion.of(libs.versions.jdk.get().toInt())
    }
}

kotlin {
    compilerOptions {
        jvmToolchain(libs.versions.jdk.get().toInt())
        // LiquidBounce is compiled with preview features, which marks its classes as pre-release
        freeCompilerArgs.add("-Xskip-prerelease-check")
        // As in LiquidBounce, whose API uses them
        freeCompilerArgs.add("-Xcompanion-blocks-and-extensions")
    }
}

tasks.jar {
    from("LICENSE") {
        rename { "${it}_${project.base.archivesName.get()}" }
    }
}
