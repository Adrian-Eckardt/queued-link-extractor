plugins {
    application
    jacoco
}

group = "dev.linkextractor"
version = "0.1.0-SNAPSHOT"

repositories {
    mavenCentral()
}

java {
    toolchain.languageVersion = JavaLanguageVersion.of(25)
}

application {
    mainClass = "dev.linkextractor.LinkExtractor"
}

dependencies {
    implementation("com.google.code.gson:gson:2.15.0")
    implementation("org.jsoup:jsoup:1.24.1")
}

testing.suites {
    withType<JvmTestSuite>().configureEach {
        useJUnitJupiter("6.1.3")
    }
    register<JvmTestSuite>("integrationTest") {
        dependencies {
            implementation(project())
        }
        targets.configureEach {
            testTask.configure { shouldRunAfter(tasks.test) }
        }
    }
}

jacoco {
    toolVersion = "0.8.16"
}

val integrationTest = tasks.named<Test>("integrationTest")
tasks.jacocoTestReport {
    dependsOn(tasks.test, integrationTest)
    executionData(integrationTest.get())
    reports {
        html.required = true
        xml.required = true
    }
}

tasks.jacocoTestCoverageVerification {
    dependsOn(tasks.jacocoTestReport)
    executionData(integrationTest.get())
    violationRules {
        rule {
            for (metric in listOf("LINE", "BRANCH", "INSTRUCTION")) {
                limit {
                    counter = metric
                    value = "MISSEDCOUNT"
                    maximum = BigDecimal.ZERO
                }
            }
        }
    }
}

// Gradle skips test and coverage tasks when a suite has no sources.
val productionSources = sourceSets.main.get().allJava
val unitTestResults = tasks.test.flatMap { it.reports.junitXml.outputLocation }
tasks.check {
    dependsOn(tasks.jacocoTestCoverageVerification)
    doLast {
        if (!productionSources.isEmpty) {
            check(unitTestResults.get().asFileTree.matching { include("TEST-*.xml") }.files.isNotEmpty()) {
                "Production sources require unit tests in src/test/java."
            }
        }
    }
}
