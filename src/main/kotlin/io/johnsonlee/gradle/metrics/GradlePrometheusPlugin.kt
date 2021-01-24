package io.johnsonlee.gradle.metrics

import io.prometheus.client.CollectorRegistry
import io.prometheus.client.Gauge
import io.prometheus.client.exporter.common.TextFormat
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
import java.io.StringWriter
import java.util.concurrent.ConcurrentHashMap

class GradlePrometheusPlugin : Plugin<Gradle> {

    override fun apply(gradle: Gradle) {
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
                .labelNames("project", "executed", "status")
                .register(registry)

        gradle.addListener(object : TaskExecutionListener {
            override fun beforeExecute(task: Task) {
                taskTime[task.path] = System.currentTimeMillis()
            }

            override fun afterExecute(task: Task, state: TaskState) {
                taskDuration.labels(
                        gradle.rootProject.name,
                        task.path,
                        "${state.failure == null}",
                        "${state.didWork}",
                        "${state.executed}",
                        "${state.noSource}",
                        "${state.skipped}",
                        state.skipMessage ?: "",
                        "${state.upToDate}"
                ).set((System.currentTimeMillis() - taskTime[task.path]!!).toDouble())
            }
        })
        gradle.addBuildListener(object : BuildAdapter() {
            override fun beforeSettings(settings: Settings) {
                settingsTime[settings.rootProject.path] = System.currentTimeMillis()
            }

            override fun settingsEvaluated(settings: Settings) {
                settingsDuration
                        .labels(settings.rootProject.path)
                        .set((System.currentTimeMillis() - settingsTime[settings.rootProject.path]!!).toDouble())
            }

            override fun buildFinished(result: BuildResult) {
                val host = gradle.rootProject.findProperty("redis.host")?.toString() ?: "127.0.0.1"
                val port = gradle.rootProject.findProperty("redis.port")?.toString()?.toInt() ?: 6379
                Jedis(host, port).use {
                    it.set("${gradle.rootProject.group}:${gradle.rootProject.name}", registry.toPlainText())
                }
            }

        })
        gradle.addProjectEvaluationListener(object : ProjectEvaluationListener {
            override fun beforeEvaluate(project: Project) {
                evaluationTime[project.path] = System.currentTimeMillis()
            }

            override fun afterEvaluate(project: Project, state: ProjectState) {
                evaluationDuration
                        .labels(project.path, "${state.executed}", "${state.failure == null}")
                        .set((System.currentTimeMillis() - evaluationTime[project.path]!!).toDouble())
            }
        })
    }

}

fun CollectorRegistry.toPlainText(): String = StringWriter().apply {
    TextFormat.write004(this, metricFamilySamples())
}.toString()
