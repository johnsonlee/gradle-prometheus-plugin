package io.johnsonlee.gradle.metrics

import kotlin.test.Test
import kotlin.test.assertEquals

class GradlePrometheusPluginTest {

    @Test
    fun `project repository path`() {
        val ssh ="git@github.com:johnsonlee/gradle-prometheus-plugin.git".substringBefore(".git").split('/', ':').filter(String::isNotEmpty).takeLast(2).joinToString(":")
        val http = "https://github.com/johnsonlee/gradle-prometheus-plugin.git".substringBefore(".git").split('/', ':').filter(String::isNotEmpty).takeLast(2).joinToString(":")
        println(ssh)
        println(http)
        assertEquals(ssh, http)
        println(repositoryPath)
    }

}