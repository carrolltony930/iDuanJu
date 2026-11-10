plugins { id("org.jetbrains.kotlin.jvm") }
kotlin { jvmToolchain(21) }
dependencies {
    api("com.squareup.okhttp3:okhttp:4.12.0")
    api("org.json:json:20240303")
    testImplementation("junit:junit:4.13.2")
}
tasks.test {
    // live network tests only when -Plive is passed
    systemProperty("idj.live", project.findProperty("live")?.toString() ?: "")
    systemProperty("idj.probe", project.findProperty("probe")?.toString() ?: "")
    project.findProperty("cmsApi")?.let { systemProperty("idj.cmsApi", it.toString()) }
    testLogging { showStandardStreams = true }
    project.findProperty("hgDevice")?.let { systemProperty("idj.hgDevice", it.toString()) }
    testLogging { events("passed", "failed", "skipped"); showStandardStreams = true; exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL }
}
