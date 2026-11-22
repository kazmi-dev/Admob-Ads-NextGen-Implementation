import org.gradle.api.publish.PublishingExtension
import org.gradle.api.publish.maven.MavenPublication

apply(plugin = "maven-publish")

val publishArtifactId = extra["publishArtifactId"] as String

afterEvaluate {
    configure<PublishingExtension> {
        publications {
            register<MavenPublication>("release") {
                from(components["release"])
                groupId = "com.github.kazmi-dev"
                artifactId = publishArtifactId
                version = rootProject.property("libraryVersion") as String
            }
        }
    }
}
