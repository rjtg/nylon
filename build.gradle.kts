import com.palantir.gradle.gitversion.VersionDetails
import groovy.util.Node
import groovy.util.NodeList
import org.jetbrains.kotlin.gradle.tasks.KotlinCompile

val springBootVersion: String by project
val kotlinVersion: String by System.getProperties()

plugins {
    val kotlinVersion: String by System.getProperties()
    kotlin("jvm") version kotlinVersion
    id("idea")
    id("com.palantir.git-version") version "0.12.3" apply true
    id("org.jetbrains.kotlin.plugin.spring") version kotlinVersion
    id("org.jetbrains.kotlin.plugin.allopen") version kotlinVersion
    id("maven-publish")

}

group = "sh.nunc"
val gitVersion: groovy.lang.Closure<*> by rootProject.extra
val versionDetails: groovy.lang.Closure<VersionDetails> by rootProject.extra
val versionDetailsObj: VersionDetails by rootProject.extra { versionDetails.call() }
val distTag = if (versionDetailsObj.commitDistance > 0) "-d" + versionDetailsObj.commitDistance + "-" + versionDetailsObj.gitHash.substring(0, 4) else ""
val branchTag = if (versionDetailsObj.branchName ?: "master" == "master") "" else "-" + versionDetailsObj.branchName!!.substring(0, 3)
version = (versionDetailsObj.lastTag ?: "0.0.1") + distTag + branchTag

repositories {
    mavenCentral()
}

dependencies {
    implementation(kotlin("stdlib-jdk8"))
    implementation("org.jetbrains.kotlin:kotlin-stdlib:$kotlinVersion")
    implementation("org.jetbrains.kotlin:kotlin-reflect:$kotlinVersion")
    implementation("org.springframework.boot:spring-boot-starter-aop:$springBootVersion")
    implementation("org.springframework.boot:spring-boot-starter-cache:$springBootVersion")
    implementation("io.github.microutils:kotlin-logging-jvm:2.0.10")
    implementation("org.aspectj:aspectjweaver:1.9.6")
    implementation("net.jodah:failsafe:2.4.0")
    testImplementation("io.mockk:mockk:1.11.0")
    testImplementation("com.ninja-squad:springmockk:3.0.1")
    testImplementation("org.springframework.boot:spring-boot-starter-test:$springBootVersion") {
        exclude(module = "mockito-core")
    }
    testImplementation("org.assertj:assertj-core:3.19.0")
    testImplementation("org.junit.jupiter:junit-jupiter-api")
    testImplementation("org.junit.jupiter:junit-jupiter-params")
    testRuntime("org.junit.jupiter:junit-jupiter-engine")
}

tasks {

    withType<KotlinCompile> {
        kotlinOptions.jvmTarget = "1.8"
    }
    test {
        useJUnitPlatform()
    }

}

afterEvaluate {
    tasks {
        getByName<GenerateMavenPom>("generatePomFileForMavenPublication"){
            this.destination = file("pom.xml")
        }
        getByName("jar") {
            dependsOn("generatePomFileForMavenPublication")
        }
    }
}




publishing {
    publications {
        create<MavenPublication>("maven") {
            groupId = "sh.nunc"
            artifactId = "nylon"
            version = "${project.version}"

            from(components["java"])
        }
    }
}

