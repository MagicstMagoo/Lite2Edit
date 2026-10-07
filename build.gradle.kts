import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.tasks.KotlinCompile
import java.security.MessageDigest

plugins {
    kotlin("jvm") version "2.4.20"
    // Minecraft 26.1 is the first unobfuscated release, so Loom no longer remaps
    // Minecraft or mods: `net.fabricmc.fabric-loom` replaces `fabric-loom`.
    // See https://docs.fabricmc.net/develop/loom/#plugin-ids
    id("net.fabricmc.fabric-loom") version "1.18.2"
    id("maven-publish")
    id("com.modrinth.minotaur") version "2.+"
}

// Values from gradle.properties
val modVersion          = project.findProperty("mod_version")           ?.toString() ?: error("Missing 'mod_version' in gradle.properties")
val minecraftVersion    = project.findProperty("minecraft_version")     ?.toString() ?: error("Missing 'minecraft_version' in gradle.properties")
val archiveBaseName     = project.findProperty("archives_base_name")    ?.toString() ?: error("Missing 'archives_base_name' in gradle.properties")
val loaderVersion       = project.findProperty("loader_version")        ?.toString() ?: error("Missing 'loader_version' in gradle.properties")
val kotlinLoaderVersion = project.findProperty("kotlin_loader_version") ?.toString() ?: error("Missing 'kotlin_loader_version' in gradle.properties")
val worldEditVersion    = project.findProperty("worldedit_version")     ?.toString() ?: error("Missing 'worldedit_version' in gradle.properties")

version = modVersion
group   = "io.github.erik-donath"

base {
    archivesName.set(archiveBaseName)
}

java {
    toolchain {
        // Minecraft 26.1+ requires Java 25.
        languageVersion.set(JavaLanguageVersion.of(25))
    }
    sourceSets["main"].java {
        setSrcDirs(emptySet<String>())
    }
    withSourcesJar()
}

repositories {
    mavenCentral()
    // EngineHub's Maven is the documented source for the WorldEdit API, but its
    // 7.4.6 snapshot line is frequently unreachable, so the compile-only WorldEdit
    // artifact is fetched from Modrinth by hash instead (see `downloadWorldEdit`).
    maven("https://maven.enginehub.org/repo/")
    maven("https://maven.fabricmc.net/")
    maven("https://maven.modrinth.com")
}

// --- WorldEdit -----------------------------------------------------------------------
// WorldEdit is installed by the user, so its API is compile-only. The exact artifact that
// matches `worldedit_version` is downloaded once into the build directory and verified
// against its published SHA-512 before it is used on the compile classpath.
val worldEditJar = layout.buildDirectory.file("worldedit/worldedit-mod-${worldEditVersion}.jar")

val downloadWorldEdit by tasks.registering {
    description = "Downloads the pinned WorldEdit mod jar that Lite2Edit compiles against."
    val destination = worldEditJar
    val expectedSha512 = "f7de9f7658319f38a9d0211a2592c91d412e98599d1ff378968509ccf0aa8f1ce7ab7e669f5c866552676d2a7a23d3cfdd16b54ee1a50d22c14fce8cfa30bba7"
    outputs.file(destination)
    onlyIf { !destination.get().asFile.isFile }
    doLast {
        val target = destination.get().asFile
        target.parentFile.mkdirs()
        val url = "https://cdn.modrinth.com/data/1u6JkXh5/versions/E7sbeRFx/worldedit-mod-${worldEditVersion}.jar"
        logger.lifecycle("Downloading WorldEdit ${worldEditVersion} from $url")
        val partial = File(target.parentFile, "${target.name}.part")
        uri(url).toURL().openStream().use { input ->
            partial.outputStream().use { output -> input.copyTo(output) }
        }
        val digest = MessageDigest.getInstance("SHA-512")
            .digest(partial.readBytes())
            .joinToString("") { "%02x".format(it) }
        check(digest == expectedSha512) {
            "WorldEdit jar checksum mismatch for $url: expected $expectedSha512 but got $digest"
        }
        check(partial.renameTo(target)) { "Could not move ${partial} to $target" }
    }
}

dependencies {
    // Minecraft 26.1+ ships unobfuscated with parameter names, so no mappings()
    // dependency is declared any more.
    minecraft("com.mojang:minecraft:${minecraftVersion}")

    // Kotlin support
    implementation("net.fabricmc:fabric-loader:${loaderVersion}")
    implementation("net.fabricmc:fabric-language-kotlin:${kotlinLoaderVersion}")

    // WorldEdit core API. WorldEdit is installed by the user, so this is compile-only.
    compileOnly(files(worldEditJar) { builtBy(downloadWorldEdit) })

    // Bundled runtime dependencies: WorldEdit 7.4 no longer ships adventure-nbt,
    // but the .litematic reader/writer needs an NBT implementation of its own.
    listOf(
        "net.kyori:adventure-nbt:4.25.0",
        "net.kyori:adventure-api:4.25.0",
        "net.kyori:examination-api:1.3.0",
        "net.kyori:examination-string:1.3.0"
    ).forEach { dependency ->
        implementation(dependency)
        include(dependency)
    }
}

tasks.withType<KotlinCompile> {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_25)
        freeCompilerArgs.addAll("-Xjsr305=strict")
    }
}

tasks.withType<JavaCompile>().configureEach {
    options.release.set(25)
}

tasks.processResources {
    inputs.property("version",               project.version)
    inputs.property("loader_version",        loaderVersion)
    inputs.property("minecraft_version",     minecraftVersion)
    inputs.property("kotlin_loader_version", kotlinLoaderVersion)
    inputs.property("worldedit_version",     worldEditVersion)

    filteringCharset = "UTF-8"

    filesMatching("fabric.mod.json") {
        expand(
            "version"               to project.version,
            "loader_version"        to loaderVersion,
            "minecraft_version"     to minecraftVersion,
            "kotlin_loader_version" to kotlinLoaderVersion,
            "worldedit_version"     to worldEditVersion
        )
    }
}

modrinth {
    token.set(System.getenv("MODRINTH_TOKEN"))
    projectId.set(System.getenv("MODRINTH_ID") ?: "")

    versionNumber.set("lite2edit-fabric-${minecraftVersion}-${modVersion}")
    versionName.set("Lite2Edit Fabric $modVersion for Minecraft $minecraftVersion")
    versionType.set("release")

    // Unobfuscated Loom produces the final mod jar with the plain `jar` task.
    uploadFile.set(tasks.jar)

    gameVersions.addAll(minecraftVersion)
    loaders.add("fabric")

    dependencies {
        required.project("fabric-language-kotlin")
        required.project("worldedit")
    }
}

publishing {
    publications {
        create<MavenPublication>("maven") {
            from(components["java"])
            groupId    = "io.github.erik-donath"
            artifactId = "lite2edit-mc${minecraftVersion}"
            version    = modVersion

            pom {
                name.set("Lite2Edit")
                description.set("A Fabric mod that lets WorldEdit open Litematica schematic files (.litematic / .ltc). Converts Litematica schematics into WorldEdit clipboards for easy importing and editing.")
                url.set("https://github.com/Erik-Donath/lite2edit")
                inceptionYear.set("2025")

                licenses {
                    license {
                        name.set("The MIT License")
                        url.set("https://opensource.org/licenses/MIT")
                        distribution.set("repo")
                    }
                }

                developers {
                    developer {
                        id.set("erik-donath")
                        name.set("Erik Donath")
                        email.set("erik.donath@gmail.com")
                        url.set("https://github.com/Erik-Donath")
                        roles.set(listOf("developer", "maintainer"))
                        timezone.set("Europe/Berlin")
                    }
                }

                scm {
                    connection.set("scm:git:git://github.com/Erik-Donath/lite2edit.git")
                    developerConnection.set("scm:git:ssh://github.com/Erik-Donath/lite2edit.git")
                    url.set("https://github.com/Erik-Donath/lite2edit/tree/master")
                    tag.set("HEAD")
                }

                issueManagement {
                    system.set("GitHub Issues")
                    url.set("https://github.com/Erik-Donath/lite2edit/issues")
                }

                ciManagement {
                    system.set("GitHub Actions")
                    url.set("https://github.com/Erik-Donath/lite2edit/actions")
                }
            }
        }
    }

    repositories {
        maven {
            name = "GitHubPackages"
            url  = uri("https://maven.pkg.github.com/Erik-Donath/lite2edit")
            credentials {
                username = System.getenv("GITHUB_ACTOR") ?: project.findProperty("gpr.user") as String?
                password = System.getenv("GITHUB_TOKEN") ?: project.findProperty("gpr.token") as String?
            }
        }
    }
}
