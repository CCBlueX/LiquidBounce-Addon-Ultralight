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

// `./gradlew runClientGameTest` starts the client with the add-on and runs src/gametest.
loom {
    accessWidenerPath = file("src/main/resources/liquidbounce-ultralight.accesswidener")
}

fabricApi {
    configureTests {
        createSourceSet = true
        modId = "liquidbounce-ultralight-gametest"
        enableGameTests = false
    }
}

loom.runs.named("clientGameTest") {
    // A loader error would otherwise wait on a dialog nobody sees; the client's own fatal errors go to
    // the log when CI is set.
    systemProperties.put("fabric.noGui", "true")
    environmentVars.put("CI", "true")
}

// The run directory is wiped before every run and the client then downloads Ultralight into it. Point it at a
// directory to keep the download in, which gets an `ultralight` folder: -Pgametest.libraries=$HOME/.cache/lb
tasks.named<JavaExec>("runClientGameTest") {
    providers.gradleProperty("gametest.libraries").orNull?.let { environment("LB_BROWSER_LIBRARIES", it) }
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

    // Ultralight itself is not bundled, the client downloads its SDK from Ultralight
    implementation(libs.ujr.core)
    implementation(libs.ujr.platform.jni)
    implementation(libs.xz)
    implementation(libs.sevenzipjbinding)
    runtimeOnly(libs.sevenzipjbinding.linux)
    runtimeOnly(libs.sevenzipjbinding.windows)
    include(libs.ujr.core)
    include(libs.ujr.platform.jni)
    include(libs.xz)
    include(libs.sevenzipjbinding)
    include(libs.sevenzipjbinding.linux)
    include(libs.sevenzipjbinding.windows)
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
