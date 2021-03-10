package io.johnsonlee.gradle.metrics

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
import java.io.File
import java.net.HttpURLConnection
import java.net.URI
import java.net.URL
import java.nio.charset.StandardCharsets
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

const val METRICS_ENDPOINT = "metrics.endpoint"

class GradlePrometheusPlugin : Plugin<Gradle> {

    private val executor: ExecutorService = Executors.newSingleThreadExecutor()

    @Suppress("RemoveCurlyBracesFromTemplate")
    override fun apply(gradle: Gradle) {
        val projectName = repositoryPath
        val settingsTime = ConcurrentHashMap<String, Long>()
        val taskTime = ConcurrentHashMap<String, Long>()
        val evaluationTime = ConcurrentHashMap<String, Long>()

        gradle.addListener(object : TaskExecutionListener {
            override fun beforeExecute(task: Task) {
                taskTime[task.path] = System.currentTimeMillis()
            }

            override fun afterExecute(task: Task, state: TaskState) {
                val durationInMs = (System.currentTimeMillis() - taskTime[task.path]!!).toDouble()
                executor.execute {
                    post("""{
                        |    "metric": "gradle_task_execution_duration_ms",
                        |    "labels": {
                        |        "project": "${projectName}",
                        |        "path": "${task.path}",
                        |        "status": "${state.failure == null}",
                        |        "didWork": "${state.didWork}",
                        |        "executed": "${state.executed}",
                        |        "noSource": "${state.noSource}",
                        |        "skipped": "${state.skipped}",
                        |        "skipMessage": "${(state.skipMessage ?: "")}",
                        |        "upToDate": "${state.upToDate}"
                        |    },
                        |    "value": ${durationInMs}
                        |}""".trimMargin())
                }
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
                val durationInMs = (System.currentTimeMillis() - settingsTime[projectName]!!).toDouble()
                executor.execute {
                    post("""{
                        |    "metric": "gradle_settings_duration_ms",
                        |    "labels": {
                        |        "project": "${projectName}"
                        |    },
                        |    "value": ${durationInMs}
                        |}""".trimMargin())
                }
            }

            override fun buildFinished(result: BuildResult) {
                val durationInMs = (System.currentTimeMillis() - settingsTime[projectName]!!).toDouble()
                executor.execute {
                    post("""{
                        |    "metric": "gradle_build_duration_ms",
                        |    "labels": {
                        |        "project": "${projectName}",
                        |        "tasks": "${gradle.startParameter.taskNames.distinct().joinToString(",")}",
                        |        "status": "${result.failure == null}"
                        |    },
                        |    "value": ${durationInMs}
                        |}""".trimMargin())
                }
            }

        })
        gradle.addProjectEvaluationListener(object : ProjectEvaluationListener {
            override fun beforeEvaluate(project: Project) {
                evaluationTime[project.path] = System.currentTimeMillis()
            }

            override fun afterEvaluate(project: Project, state: ProjectState) {
                val durationInMs = (System.currentTimeMillis() - evaluationTime[project.path]!!).toDouble()
                executor.execute {
                    post("""{
                        |    "metric": "gradle_project_evaluation_duration_ms",
                        |    "labels": {
                        |        "project": "${projectName}",
                        |        "path": "${project.path}",
                        |        "executed": "${state.executed}",
                        |        "status": "${state.failure == null}"
                        |    },
                        |    "value": ${durationInMs}
                        |}""".trimMargin())
                }
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

internal fun post(json: String) {
    val url = System.getenv("METRICS_ENDPOINT") ?: return
    (URL(url).openConnection() as HttpURLConnection).run {
        connectTimeout = 10_000
        readTimeout = 10_000
        requestMethod = "POST"
        setRequestProperty("Content-Type", "application/json; charset=utf-8")
        setRequestProperty("Accept", "application/json")
        doOutput = true
        doInput = true
        outputStream.write(json.toByteArray(StandardCharsets.UTF_8))
        disconnect()
    }
}
