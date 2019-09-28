import com.palantir.gradle.gitversion.VersionDetails
import org.jetbrains.kotlin.gradle.tasks.KotlinCompile

plugins {
    kotlin("jvm") version "1.3.50"
    id("idea")
//    id("org.springframework.boot") version "2.1.4.RELEASE"
    id("com.palantir.git-version") version "0.11.0" apply true
//    id("maven-publish") apply true

}
allprojects {
    apply(plugin = "com.palantir.git-version")
    group = "de.tamedbeast"
    val gitVersion: groovy.lang.Closure<*> by rootProject.extra
    val versionDetails: groovy.lang.Closure<VersionDetails> by rootProject.extra
    val versionDetailsObj: VersionDetails by rootProject.extra { versionDetails.call() }
    version = (versionDetailsObj.lastTag
        ?: "0.0.1") + "-d" + versionDetailsObj.commitDistance + (if (versionDetailsObj.branchName ?: "master" == "master") "" else "-" + versionDetailsObj.branchName!!.substring(
        0,
        3
    )) + "-" + versionDetailsObj.gitHash.substring(0, 4)

}
tasks.withType<KotlinCompile> {
    kotlinOptions.jvmTarget = "1.8"
}

repositories {
    mavenCentral()
}

dependencies {
    implementation(kotlin("stdlib-jdk8"))

    implementation("org.springframework.boot:spring-boot-starter-aop:2.1.10.RELEASE")
    implementation("org.springframework.boot:spring-boot-starter-cache:2.1.10.RELEASE")
    implementation("org.springframework.data:spring-data-redis:2.1.10.RELEASE")
    implementation("io.github.microutils:kotlin-logging:1.6.22")
    implementation("org.aspectj:aspectjweaver:1.9.4")
    implementation("net.jodah:failsafe:2.3.1")
}

tasks.withType<KotlinCompile> {
    kotlinOptions.jvmTarget = "1.8"
}