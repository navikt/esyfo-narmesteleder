val valkeyVersion = "5.5.0"

plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.ktor)
    alias(libs.plugins.ktlint)
    alias(libs.plugins.flyway)
}

group = "no.nav.syfo"
version = "0.0.1"

application {
    mainClass = "io.ktor.server.netty.EngineMain"
}

repositories {
    mavenCentral()
    maven(url = "https://packages.confluent.io/maven/")
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
    implementation(libs.confluent.kafka.avro.serializer) {
        exclude(group = "org.apache.kafka", module = "kafka-clients")
    }
    implementation(libs.datafaker)
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
}
application {
    mainClass.set("no.nav.syfo.ApplicationKt")
}

kotlin {
    jvmToolchain(21)
}

val avroSchemasDir = "src/main/avro"
val personhendelseSchema = "$avroSchemasDir/no/nav/person/pdl/leesah/Personhendelse.avsc"
val avroCodeGenerationDir = "build/generated-main-avro-custom-java"
val avroTools by configurations.creating

dependencies {
    avroTools(libs.apache.avro.tools)
}

sourceSets {
    main {
        java {
            srcDir(file(avroCodeGenerationDir))
        }
    }
}

tasks {
    register<JavaExec>("customAvroCodeGeneration") {
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

    jar {
        manifest.attributes["Main-Class"] = "no.nav.syfo.ApplicationKt"
    }

    register("printVersion") {
        doLast {
            println(project.version)
        }
    }

    shadowJar {
        dependsOn("customAvroCodeGeneration")
        filesMatching("META-INF/services/**") {
            duplicatesStrategy = DuplicatesStrategy.INCLUDE
        }
        mergeServiceFiles()
        archiveFileName.set("app.jar")
        archiveClassifier.set("")
        archiveVersion.set("")
    }

    compileKotlin {
        dependsOn("customAvroCodeGeneration")
    }

    compileTestKotlin {
        dependsOn("customAvroCodeGeneration")
    }

    named("runKtlintCheckOverMainSourceSet") {
        dependsOn("customAvroCodeGeneration")
    }

    named("runKtlintCheckOverTestSourceSet") {
        dependsOn("customAvroCodeGeneration")
    }

    test {
        dependsOn("customAvroCodeGeneration")
        useJUnitPlatform()
    }

    named("check") {
        dependsOn("ktlintCheck")
    }
}
