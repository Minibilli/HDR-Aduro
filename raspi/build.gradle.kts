plugins {
    id("org.jetbrains.kotlin.jvm")
    application
}

// HDR Aduro Home Raspi: gleicher Kern wie die App (shared/), dazu Webserver für den Touchscreen
kotlin {
    jvmToolchain(17)
    sourceSets["main"].kotlin.srcDir("../shared/src/main/kotlin")
}

dependencies {
    // Unter Android ist org.json eingebaut, auf dem Raspi kommt es als Bibliothek dazu
    implementation("org.json:json:20240303")
}

application {
    mainClass.set("de.ofen.raspi.MainKt")
}

// Dieselbe Oberfläche wie in der App
tasks.processResources {
    from("../app/src/main/assets/index.html") { into("web") }
}

// Eine einzige Datei für den Raspi: java -jar hdr-aduro-home-raspi.jar
tasks.register<Jar>("fatJar") {
    archiveFileName.set("hdr-aduro-home-raspi.jar")
    manifest { attributes["Main-Class"] = "de.ofen.raspi.MainKt" }
    duplicatesStrategy = DuplicatesStrategy.EXCLUDE
    from(sourceSets.main.get().output)
    dependsOn(configurations.runtimeClasspath)
    from({ configurations.runtimeClasspath.get().filter { it.name.endsWith(".jar") }.map { zipTree(it) } })
}
