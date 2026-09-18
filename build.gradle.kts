import com.adarshr.gradle.testlogger.theme.ThemeType

plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.ktor)
    alias(libs.plugins.ktlint)
    alias(libs.plugins.flyway)
    alias(libs.plugins.test.logger)
}

group = "no.nav.syfo"
version = "0.0.1"

application {
    mainClass.set("no.nav.syfo.ApplicationKt")
}

repositories {
    mavenCentral()
    maven(url = "https://packages.confluent.io/maven/")
    maven(url = "https://github-package-registry-mirror.gc.nav.no/cached/maven-release")
}

buildscript {
    repositories {
        mavenCentral()
    }
    dependencies {
        classpath(libs.database.flyway.core)
    }
}

dependencies {
    implementation(platform(libs.netty))
    implementation(libs.apache.avro)
    implementation(libs.commons.text)
    implementation(libs.confluent.kafka.avro.serializer) {
        exclude(group = "org.apache.kafka", module = "kafka-clients")
    }
    implementation(libs.datafaker)
    implementation(libs.esyfo.logger)
    implementation(libs.logback.classic)
    implementation(libs.bundles.ktor.client)
    implementation(libs.bundles.ktor.server)
    implementation(libs.koin.ktor)
    implementation(libs.koin.logger)
    implementation(libs.logstash)
    implementation(libs.jackson.datatype.jsr310)
    implementation(libs.bundles.database)
    implementation(libs.ktor.server.micrometer)
    implementation(libs.micrometer.prometheus)
    implementation(libs.kafka.clients)
    implementation(libs.valkey.java)

    testImplementation(libs.ktor.server.test.host)
    testImplementation(libs.bundles.kotest)
    testImplementation(libs.mockk)
    testImplementation(libs.testcontainers)
    testImplementation(libs.ktor.client.mock)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.bundles.testcontainers)
    testImplementation(libs.esyfo.logger.testkit)
}
kotlin {
    jvmToolchain(25)
}

val avroSchemasDir = "src/main/avro"
val personhendelseSchema = "$avroSchemasDir/no/nav/person/pdl/leesah/Personhendelse.avsc"
val avroCodeGenerationDir = "build/generated-main-avro-custom-java"
val avroTools = configurations.create("avroTools")

dependencies {
    avroTools(libs.apache.avro.tools) {
        exclude(group = "org.apache.avro", module = "trevni-avro")
        exclude(group = "org.apache.avro", module = "trevni-core")
    }
}

val customAvroCodeGeneration = tasks.register<JavaExec>("customAvroCodeGeneration") {
    inputs.file(personhendelseSchema)
    outputs.dir(avroCodeGenerationDir)
    classpath = avroTools
    mainClass.set("org.apache.avro.tool.Main")
    args(
        "compile",
        "schema",
        "-encoding",
        "UTF-8",
        "-string",
        "-fieldVisibility",
        "private",
        "-noSetters",
        "$projectDir/$personhendelseSchema",
        "$projectDir/$avroCodeGenerationDir",
    )
}

sourceSets {
    main {
        java {
            srcDir(customAvroCodeGeneration)
        }
    }
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
        testlogger {
            theme = ThemeType.MOCHA_PARALLEL
            showFullStackTraces = true
            showSimpleNames = true
        }
    }

    named("check") {
        dependsOn("ktlintCheck")
    }
}
