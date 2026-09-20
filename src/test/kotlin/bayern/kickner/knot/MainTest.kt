package bayern.kickner.knot

import bayern.kickner.knot.config.config
import java.io.File
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertTrue

private val apiKeyPattern = Regex("[A-Za-z0-9_-]{43}")

private class Run(val exitCode: Int, val stdout: String, val stderr: String)

/**
 * Runs KNot's `main` in a separate JVM, the only way to observe exit codes and the stdout/stderr split.
 * `-Xlog:disable` keeps JVM warnings of the host (e.g. about cgroups) out of stdout.
 */
private fun knot(vararg args: String, workingDirectory: File): Run {
    val java = File(System.getProperty("java.home"), "bin/java").absolutePath
    val stdout = File.createTempFile("knot-stdout", ".txt").apply { deleteOnExit() }
    val stderr = File.createTempFile("knot-stderr", ".txt").apply { deleteOnExit() }
    val process = ProcessBuilder(java, "-Xlog:disable", "-cp", System.getProperty("java.class.path"), "bayern.kickner.knot.MainKt", *args)
        .directory(workingDirectory)
        .redirectOutput(stdout)
        .redirectError(stderr)
        .start()
    assertTrue(process.waitFor(60, TimeUnit.SECONDS), "KNot did not exit within 60 s")
    return Run(process.exitValue(), stdout.readText(), stderr.readText())
}

private fun emptyDirectory() = File.createTempFile("knot-cwd", "").apply {
    delete()
    mkdir()
    deleteOnExit()
}

class MainTest {

    @Test
    fun `key prints only a fresh api key to stdout and needs no config`() {
        val run = knot("key", workingDirectory = emptyDirectory())

        assertEquals(0, run.exitCode, run.stderr)
        assertTrue(apiKeyPattern.matches(run.stdout.trim()), "stdout was: ${run.stdout}")
        assertContains(run.stderr, "Keep it secret")
    }

    @Test
    fun `test with an unknown target exits with 1 and names the configured targets`() {
        val directory = emptyDirectory()
        File(directory, "config.json").writeText(config())

        val run = knot("test=nope", workingDirectory = directory)

        assertEquals(1, run.exitCode)
        assertContains(run.stderr, "Unknown target 'nope'. Configured targets: ops")
    }
}
