import com.jfrog.bintray.gradle.BintrayExtension.PackageConfig
import com.palantir.gradle.gitversion.VersionDetails
import groovy.util.Node
import groovy.util.NodeList
import org.jetbrains.kotlin.gradle.tasks.KotlinCompile

val springBootVersion = "2.1.9.RELEASE"


plugins {
    kotlin("jvm") version "1.3.50"
    id("idea")
//    id("org.springframework.boot") version "2.1.4.RELEASE"
    id("com.palantir.git-version") version "0.11.0" apply true
    id("maven-publish") apply true
    id("com.jfrog.bintray") version "1.8.4"
    id("org.jetbrains.kotlin.plugin.spring") version "1.3.50"
    id("org.jetbrains.kotlin.plugin.allopen") version "1.3.50"

}

dependencies {
    implementation("org.jetbrains.kotlin:kotlin-stdlib:1.3.50")
    implementation("org.jetbrains.kotlin:kotlin-reflect:1.3.50")
}

allprojects {
    apply(plugin = "com.palantir.git-version")
    group = "sh.nunc"
    val gitVersion: groovy.lang.Closure<*> by rootProject.extra
    val versionDetails: groovy.lang.Closure<VersionDetails> by rootProject.extra
    val versionDetailsObj: VersionDetails by rootProject.extra { versionDetails.call() }
    val distTag = if (versionDetailsObj.commitDistance > 0) "-d" + versionDetailsObj.commitDistance + "-" + versionDetailsObj.gitHash.substring(0, 4) else ""
    val branchTag = if (versionDetailsObj.branchName ?: "master" == "master") "" else "-" + versionDetailsObj.branchName!!.substring(0, 3)
    version = (versionDetailsObj.lastTag ?: "0.0.1") + distTag + branchTag
    apply(plugin = "maven-publish")
    apply(plugin= "com.jfrog.bintray")

    fun findProperty(s: String) = project.findProperty(s) as String?

    bintray {
        user = findProperty("bintrayUser")
        key = findProperty("bintrayApiKey")
        publish = true
        setPublications("nylon")
        pkg(delegateClosureOf<PackageConfig> {
            repo = "sh.nunc"
            name = "nylon"
            websiteUrl = "https://github.com/rjtg/nylon"
            githubRepo = "rjtg/nylon"
            vcsUrl = "https://github.com/rjtg/nylon"
            setLabels("kotlin", "Spring Boot", "Resilience", "Caching")
            setLicenses("MIT")
        })

    }


    publishing {
        publications {
            create<MavenPublication>("nylon") {
                groupId = project.group as String
                artifactId = project.name
                version = project.version as String
                from(components["java"])
                pom {
                    name.set("Nylon")
                    description.set("Resilience Annotations for Spring Boot")
                    url.set("https://github.com/rjtg/nylon")
                    licenses {
                        license {
                            name.set("MIT License")
                            url.set("https://raw.githubusercontent.com/rjtg/nylon/master/LICENSE")
                        }
                    }
                    developers {
                        developer {
                            id.set("rjtg")
                            name.set("Roland Gude")
                            email.set("rg@nunc.sh")
                        }
                    }
                    scm {
                        connection.set("scm:git:git://github.com/rjtg/nylon.git")
                        developerConnection.set("scm:git:ssh://github.com/rjtg/nylon.git")
                        url.set("https://github.com/rjtg/nylon")
                    }

                    withXml {
                        val deps = asNode()["dependencies"] as NodeList
                        deps.forEach {asNode().remove(it as Node) }
                        asNode().appendNode("dependencies").let { depNode ->
                            configurations.implementation.get().allDependencies.forEach {
                                depNode.appendNode("dependency").apply {
                                    appendNode("groupId", it.group)
                                    appendNode("artifactId", it.name)
                                    appendNode("version", it.version)
                                }
                            }
                        }
                    }

                }
            }
        }
    }




}


tasks.withType<KotlinCompile> {
    kotlinOptions.jvmTarget = "1.8"
}

repositories {
    mavenCentral()
}

dependencies {
    implementation(kotlin("stdlib-jdk8"))

    implementation("org.springframework.boot:spring-boot-starter-aop:$springBootVersion")
    implementation("org.springframework.boot:spring-boot-starter-cache:$springBootVersion")
    //implementation("org.springframework.data:spring-data-redis:$springBootVersion")
    implementation("io.github.microutils:kotlin-logging:1.6.22")
    implementation("org.aspectj:aspectjweaver:1.9.4")
    implementation("net.jodah:failsafe:2.3.1")
    testImplementation("io.mockk:mockk:1.10.0")
    testImplementation("com.ninja-squad:springmockk:2.0.3")
    testImplementation("org.springframework.boot:spring-boot-starter-test:$springBootVersion") {
        exclude(module = "mockito-core")
    }
    testImplementation("org.assertj:assertj-core:3.11.1")
    testImplementation("org.junit.jupiter:junit-jupiter-api:5.2.0")
    testImplementation("org.junit.jupiter:junit-jupiter-params:5.2.0")
    testRuntime("org.junit.jupiter:junit-jupiter-engine:5.2.0")
}

tasks.withType<KotlinCompile> {
    kotlinOptions.jvmTarget = "1.8"
}

