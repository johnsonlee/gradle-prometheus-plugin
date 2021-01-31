package io.johnsonlee.gradle.metrics

import io.prometheus.client.CollectorRegistry
import io.prometheus.client.Gauge
import io.prometheus.client.exporter.common.TextFormat
import org.codehaus.groovy.runtime.ProcessGroovyMethods.execute
import org.codehaus.groovy.runtime.ProcessGroovyMethods.getText
import org.gradle.BuildAdapter
import org.gradle.BuildResult
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.ProjectEvaluationListener
import org.gradle.api.ProjectState
import org.gradle.api.Task
import org.gradle.api.execution.TaskExecutionListener
import org.gradle.api.initialization.Settings
import org.gradle.api.invocation.Gradle
import org.gradle.api.tasks.TaskState
import redis.clients.jedis.Jedis
import java.io.File
import java.io.StringWriter
import java.net.URI
import java.util.concurrent.ConcurrentHashMap

class GradlePrometheusPlugin : Plugin<Gradle> {

    override fun apply(gradle: Gradle) {
        val projectName = repositoryPath
        val registry = CollectorRegistry(true)
        val settingsTime = ConcurrentHashMap<String, Long>()
        val settingsDuration = Gauge.build()
                .name("gradle_settings_duration_ms")
                .help("Gradle settings duration in millis")
                .labelNames("project")
                .register(registry)
        val taskTime = ConcurrentHashMap<String, Long>()
        val taskDuration = Gauge.build()
                .name("gradle_task_execution_duration_ms")
                .help("Task execution duration in millis")
                .labelNames("project", "path", "status", "didWork", "executed", "noSource", "skipped", "skipMessage", "upToDate")
                .register(registry)
        val evaluationTime = ConcurrentHashMap<String, Long>()
        val evaluationDuration = Gauge.build()
                .name("gradle_project_evaluation_duration_ms")
                .help("Gradle project evaluation duration in millis")
                .labelNames("project", "path", "executed", "status")
                .register(registry)

        gradle.addListener(object : TaskExecutionListener {
            override fun beforeExecute(task: Task) {
                taskTime[task.path] = System.currentTimeMillis()
            }

            override fun afterExecute(task: Task, state: TaskState) {
                taskDuration.labels(
                        projectName,
                        task.path,
                        "${state.failure == null}",
                        "${state.didWork}",
                        "${state.executed}",
                        "${state.noSource}",
                        "${state.skipped}",
                        (state.skipMessage ?: ""),
                        "${state.upToDate}"
                ).set((System.currentTimeMillis() - taskTime[task.path]!!).toDouble())
            }
        })
        gradle.addBuildListener(object : BuildAdapter() {
            // fix compatibility issue of Gradle
            init {
                settingsTime[projectName] = System.currentTimeMillis()
            }

            override fun beforeSettings(settings: Settings) {
                settingsTime[projectName] = System.currentTimeMillis()
            }

            override fun settingsEvaluated(settings: Settings) {
                settingsDuration
                        .labels(projectName)
                        .set((System.currentTimeMillis() - settingsTime[projectName]!!).toDouble())
            }

            override fun buildFinished(result: BuildResult) {
                try {
                    val host = gradle.rootProject.findProperty("redis.host")?.toString() ?: "127.0.0.1"
                    val port = gradle.rootProject.findProperty("redis.port")?.toString()?.toInt() ?: 6379

                    Jedis(host, port).use {
                        it.set(projectName, registry.toPlainText())
                    }
                } catch (e: Throwable) {
                    System.err.println(e.message)
                }
            }

        })
        gradle.addProjectEvaluationListener(object : ProjectEvaluationListener {
            override fun beforeEvaluate(project: Project) {
                evaluationTime[project.path] = System.currentTimeMillis()
            }

            override fun afterEvaluate(project: Project, state: ProjectState) {
                evaluationDuration
                        .labels(projectName, project.path, "${state.executed}", "${state.failure == null}")
                        .set((System.currentTimeMillis() - evaluationTime[project.path]!!).toDouble())
            }
        })
    }

}

internal val repositoryPath: String by lazy {
    val repo: String.() -> String = {
        substringBefore(".git").split('/', ':').filter(String::isNotEmpty).takeLast(2).joinToString(":")
    }
    getText(execute("git config --get remote.origin.url"))?.trim()?.takeIf(String::isNotEmpty)?.let {
        try {
            URI(it).path.repo()
        } catch (e: Exception) {
            it.repo()
        }
    } ?: File(System.getProperty("user.dir")).name
}

private fun CollectorRegistry.toPlainText(): String = StringWriter().apply {
    TextFormat.write004(this, metricFamilySamples())
}.toString()
