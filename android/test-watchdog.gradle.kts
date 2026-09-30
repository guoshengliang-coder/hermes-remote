import org.gradle.api.tasks.compile.JavaCompile
import org.gradle.api.tasks.bundling.Jar
import org.gradle.api.tasks.testing.Test

// Build-only, dependency-free watchdog. It is never on the app's runtime classpath.
val compileTestWatchdog = tasks.register<JavaCompile>("compileTestWatchdog") {
    source(fileTree("test-watchdog/src") { include("**/*.java") })
    classpath = files()
    destinationDirectory.set(layout.buildDirectory.dir("test-watchdog/classes"))
    sourceCompatibility = "17"
    targetCompatibility = "17"
    options.release.set(17)
}
val testWatchdogJar = tasks.register<Jar>("testWatchdogJar") {
    dependsOn(compileTestWatchdog)
    from(compileTestWatchdog.flatMap { it.destinationDirectory })
    archiveFileName.set("test-watchdog.jar")
    destinationDirectory.set(layout.buildDirectory.dir("test-watchdog"))
    manifest.attributes["Premain-Class"] = "com.hermes.testing.TestWorkerWatchdog"
}
val verifyTestWatchdog = tasks.register<Exec>("verifyTestWatchdog") {
    group = "verification"
    description = "Proves hard timeout, normal exit and isolation using real JVM processes."
    commandLine("python3", file("test-watchdog/test_watchdog.py").absolutePath, "-v")
}
val workerTimeout = providers.gradleProperty("hermesTestWorkerTimeoutSeconds").orElse("600").get()
require(workerTimeout.toLong() > 0) { "hermesTestWorkerTimeoutSeconds must be positive" }
val agentFile = testWatchdogJar.flatMap { it.archiveFile }
subprojects {
    tasks.withType<Test>().configureEach {
        dependsOn(testWatchdogJar, verifyTestWatchdog)
        inputs.file(agentFile)
        jvmArgs("-javaagent:${agentFile.get().asFile.absolutePath}=$workerTimeout")
    }
}
