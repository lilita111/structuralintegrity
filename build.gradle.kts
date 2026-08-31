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

// sable and sablecollisiondamage are hard dependencies in neoforge.mods.toml, so the
// dev runs (including gameTestServer) need them on the runtime classpath as loadable
// mods, not just as compile-time API jars - without runtimeOnly here, ModDevGradle's
// run tasks never see them and NeoForge refuses to start structuralintegrity at all.
// sable-companion-common is jar-in-jar inside sable-neoforge itself (extracted here
// only so the API compiles against it) so it needs no separate runtimeOnly entry.
// All three are taken straight from the pack rather than a maven repo: nothing is
// downloaded, and the classes compiled/run against are byte-for-byte the ones that
// will be loaded in game.
dependencies {
    compileOnly(files("libs/sable-neoforge-1.21.1-2.0.5.jar"))
    compileOnly(files("libs/sable-companion-common-1.21.1-1.6.0.jar"))
    runtimeOnly(files("libs/sable-neoforge-1.21.1-2.0.5.jar"))
    runtimeOnly(files("libs/sablecollisiondamage-1.0.8.jar"))
    runtimeOnly(files("libs/sableexplosionfix-1.0.0.jar"))
}

neoForge {
    version = property("neoforge_version") as String
    runs {
        create("client") { client() }
        create("server") { server() }
        // No convenience method on RunModel for this one (javap on RunModel confirmed
        // only client/clientData/data/server/serverData exist) - moddev-config.json for
        // 21.1.228 confirms "gameTestServer" is a real run type (server=true, gameTest=true,
        // neoforge.enableGameTest/neoforge.gameTestServer=true), just set via raw type.
        create("gameTestServer") {
            type = "gameTestServer"
        }
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
