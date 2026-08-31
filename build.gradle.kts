plugins {
    id("java-library")
    id("net.neoforged.moddev") version "2.0.143"
}

version = property("mod_version") as String
group = "com.apokalypse"
base { archivesName = "structuralintegrity" }

java {
    sourceCompatibility = JavaVersion.VERSION_21
    targetCompatibility = JavaVersion.VERSION_21
}

tasks.withType<JavaCompile> { options.encoding = "UTF-8" }

// sable is a hard dependency and ships the companion library jar-in-jar, so both
// are taken straight from the pack rather than a maven repo: nothing is downloaded,
// and the classes compiled against are byte-for-byte the ones that will be loaded.
dependencies {
    compileOnly(files("libs/sable-neoforge-1.21.1-2.0.5.jar"))
    compileOnly(files("libs/sable-companion-common-1.21.1-1.6.0.jar"))
}

neoForge {
    version = property("neoforge_version") as String
    runs {
        create("client") { client() }
        create("server") { server() }
    }
    mods {
        create("structuralintegrity") { sourceSet(sourceSets["main"]) }
    }
}

// neoforge.mods.toml carried a hardcoded version, so the in-game mod list reported
// 0.1.0 forever while the jar filename tracked mod_version. One source of truth.
tasks.named<ProcessResources>("processResources") {
    val props = mapOf("mod_version" to version)
    inputs.properties(props)
    filesMatching("META-INF/neoforge.mods.toml") { expand(props) }
}
