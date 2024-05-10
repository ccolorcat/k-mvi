import org.gradle.internal.component.external.model.DefaultModuleComponentSelector

// Top-level build file where you can add configuration options common to all sub-projects/modules.
plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.jetbrains.kotlin.android) apply false
    alias(libs.plugins.android.library) apply false
}

redirectToLocal()

fun redirectToLocal() {
    for (child in subprojects) {
        child.configurations.all {
            resolutionStrategy.dependencySubstitution {
                all {
                    val selector = this.requested
                    if (selector is DefaultModuleComponentSelector && selector.group == libs.versions.groupId.get()) {
                        val project = project(":${selector.module}")
                        useTarget(project)
                        println("replace $selector with $project")
                    }
                }
            }
        }
    }
}
