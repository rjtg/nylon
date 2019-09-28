import org.gradle.tooling.internal.consumer.versioning.VersionDetails
import org.jetbrains.kotlin.gradle.tasks.KotlinCompile

plugins {
    kotlin("jvm") version "1.3.50"
    id("idea")
    id("java")
    id("org.springframework.boot") version "2.1.4.RELEASE"
    id("com.palantir.git-version") version "0.11.0"
//    id("maven-publish") apply true

}
group = "de.tamedbeast"
val gitVersion: groovy.lang.Closure<*> by rootProject.extra
val versionDetails: groovy.lang.Closure<VersionDetails> by rootProject.extra
val versionDetailsObj: VersionDetails by rootProject.extra { versionDetails.call() }
version = (versionDetailsObj.lastTag ?: "0.0.1")  + "-d" + versionDetailsObj.commitDistance + (if (versionDetailsObj.branchName ?: "master" == "master") "" else "-" +versionDetailsObj.branchName!!.substring(0,3)) + "-" +versionDetailsObj.gitHash.substring(0,4)

repositories {
    mavenCentral()
}

dependencies {
    implementation(kotlin("stdlib-jdk8"))
}

tasks.withType<KotlinCompile> {
    kotlinOptions.jvmTarget = "1.8"
}