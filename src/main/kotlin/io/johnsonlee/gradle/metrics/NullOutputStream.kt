package io.johnsonlee.gradle.metrics

import java.io.OutputStream

internal class NullOutputStream : OutputStream() {
    override fun write(c: Int) = Unit
}