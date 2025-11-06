plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.ktor)
}

group = "no.nav.syfo"
version = "0.0.1"

application {
    mainClass = "io.ktor.server.netty.EngineMain"
}

repositories {
    mavenCentral()
}

dependencies {
    implementation(libs.datafaker)
    implementation(libs.ktor.server.core)
    implementation(libs.ktor.server.netty)
    implementation(libs.logback.classic)
    implementation(libs.ktor.server.core)
    implementation(libs.ktor.server.config.yaml)
    implementation("io.ktor:ktor-client-core")
    implementation("io.ktor:ktor-client-content-negotiation")
    implementation("io.ktor:ktor-client-apache-jvm")
    implementation("io.ktor:ktor-serialization-jackson")
    implementation("io.ktor:ktor-server-auth")
    implementation("io.ktor:ktor-server-auth-jwt")
    implementation("io.ktor:ktor-server-call-id")
    implementation("io.ktor:ktor-server-content-negotiation")
    implementation("io.ktor:ktor-server-status-pages")
    implementation(libs.koin.ktor)
    implementation(libs.koin.logger)
    implementation(libs.logstash)
    implementation(libs.jackson.datatype.jsr310)
    // Database
    implementation(libs.bundles.database)
    // Metrics and Prometheus
    implementation(libs.ktor.server.micrometer)
    implementation(libs.micrometer.prometheus)
    implementation(libs.kafka.clients)
    implementation(libs.kafka.twothirteen) { exclude(group = "log4j") }
    implementation(libs.logging.janino)
    testImplementation(libs.ktor.server.test.host)
    testImplementation(libs.bundles.kotest)
    testImplementation(libs.mockk)
    testImplementation(libs.testcontainers)
    testImplementation(libs.ktor.client.mock)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.bundles.testcontainers) // Will want this eventually
}
application {
    mainClass.set("no.nav.syfo.ApplicationKt")
}

kotlin {
    jvmToolchain(21)
}

tasks {
    jar {
        manifest.attributes["Main-Class"] = "no.nav.syfo.ApplicationKt"
    }

    register("printVersion") {
        doLast {
            println(project.version)
        }
    }

    shadowJar {
        filesMatching("META-INF/services/**") {
            duplicatesStrategy = DuplicatesStrategy.INCLUDE
        }
        mergeServiceFiles()
        archiveFileName.set("app.jar")
        archiveClassifier.set("")
        archiveVersion.set("")
    }

    test {
        useJUnitPlatform()
    }
}
