package com.example.open_autoglm_android.virtualdisplay

import com.ai.assistance.showerclient.ShellCommandResult
import com.ai.assistance.showerclient.ShellIdentity
import com.ai.assistance.showerclient.ShellRunner
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import rikka.shizuku.Shizuku
import java.io.InterruptedIOException
import java.io.InputStream
import java.lang.reflect.Method

class ShizukuShellRunner : ShellRunner {

    override suspend fun run(command: String, identity: ShellIdentity): ShellCommandResult =
        withContext(Dispatchers.IO) {
            try {
                if (command.isBlank()) {
                    return@withContext ShellCommandResult(false, "", "Empty command", -1)
                }
                return@withContext when (identity) {
                    ShellIdentity.DEFAULT -> runLocal(command)
                    ShellIdentity.SHELL -> runShizuku(command)
                    ShellIdentity.ROOT -> runShizukuRoot(command)
                }
            } catch (t: Throwable) {
                ShellCommandResult(
                    success = false,
                    stdout = "",
                    stderr = t.message ?: t.javaClass.simpleName,
                    exitCode = -1
                )
            }
        }

    private suspend fun runLocal(command: String): ShellCommandResult {
        val containsOperators = containsShellOperators(command)
        val isBackground = isBackgroundShellCommand(command)
        val process = if (!containsOperators) {
            runCatching { ProcessBuilder(*parseCommand(command)).start() }.getOrNull()
        } else {
            null
        }

        return when {
            process != null -> collect(process, command, allowBackground = false)
            else -> collect(ProcessBuilder(*buildShellArgs(command)).start(), command, allowBackground = isBackground)
        }
    }

    private suspend fun runShizuku(command: String): ShellCommandResult {
        val readinessError = shizukuReadinessError()
        if (readinessError != null) {
            return ShellCommandResult(false, "", readinessError, -1)
        }

        val containsOperators = containsShellOperators(command)
        val isBackground = isBackgroundShellCommand(command)

        val directProcess = if (!containsOperators) {
            runCatching { newShizukuProcess(parseCommand(command)) }.getOrNull()
        } else {
            null
        }

        val directResult =
            if (directProcess != null) collect(directProcess, command, allowBackground = false) else null

        val shouldFallbackToShell =
            directResult != null && directResult.exitCode == 127 &&
                directResult.stderr.contains("not found", ignoreCase = true)

        return when {
            directResult != null && !shouldFallbackToShell -> directResult
            else -> collect(newShizukuProcess(buildShellArgs(command)), command, allowBackground = isBackground)
        }
    }

    private suspend fun runShizukuRoot(command: String): ShellCommandResult {
        val readinessError = shizukuReadinessError()
        if (readinessError != null) {
            return ShellCommandResult(false, "", readinessError, -1)
        }

        val isBackground = isBackgroundShellCommand(command)
        return collect(newShizukuProcess(arrayOf("su", "-c", command)), command, allowBackground = isBackground)
    }

    private suspend fun collect(process: Process, command: String, allowBackground: Boolean): ShellCommandResult {
        if (allowBackground) {
            runCatching { process.inputStream.close() }
            runCatching { process.errorStream.close() }
            runCatching { process.outputStream.close() }
            return ShellCommandResult(true, "", "", 0)
        }

        try {
            val stdoutDeferred = async { readFullyWithRetry(process.inputStream) }
            val stderrDeferred = async { readFullyWithRetry(process.errorStream) }
            val exitCode = process.waitFor()
            val stdout = stdoutDeferred.await()
            val stderr = stderrDeferred.await()
            val success = exitCode == 0 || (command.contains("grep") && exitCode == 1)
            return ShellCommandResult(success, stdout, stderr, exitCode)
        } finally {
            runCatching { process.inputStream.close() }
            runCatching { process.errorStream.close() }
            runCatching { process.outputStream.close() }
            runCatching { process.destroy() }
        }
    }

    private suspend fun readFullyWithRetry(stream: InputStream): String {
        var last: Exception? = null
        repeat(3) { attempt ->
            try {
                return stream.bufferedReader().use { it.readText() }
            } catch (e: Exception) {
                val isInterruptedRead = e is InterruptedIOException && e.message?.contains("read interrupted") == true
                if (!isInterruptedRead) throw e
                last = e
                delay(500L * (attempt + 1))
            }
        }
        return last?.message ?: ""
    }

    private fun shizukuReadinessError(): String? {
        return try {
            if (!Shizuku.pingBinder()) return "Shizuku binder not available"
            val granted = Shizuku.checkSelfPermission() == android.content.pm.PackageManager.PERMISSION_GRANTED
            if (!granted) return "Shizuku permission not granted"
            null
        } catch (e: Exception) {
            e.message ?: "Shizuku not available"
        }
    }

    private fun buildShellArgs(command: String): Array<String> {
        val processedCommand =
            if (command.contains("|") && command.contains("grep")) {
                command.replace(" grep ", " /system/bin/grep ")
            } else {
                command
            }

        val containsRedirection = processedCommand.contains(">")
        val enhancedCommand =
            if (containsRedirection) {
                "umask 0022 && PATH=\$PATH:/system/bin:/system/xbin:/vendor/bin:/vendor/xbin && $processedCommand"
            } else {
                processedCommand
            }

        return if (containsShellOperators(command)) {
            arrayOf("sh", "-e", "-c", enhancedCommand)
        } else {
            arrayOf("sh", "-c", enhancedCommand)
        }
    }

    private fun isBackgroundShellCommand(command: String): Boolean {
        val trimmed = command.trimEnd()
        return trimmed.endsWith("&") && !trimmed.endsWith("&&")
    }

    private fun containsShellOperators(command: String): Boolean {
        var inSingleQuotes = false
        var inDoubleQuotes = false
        var escaped = false
        var i = 0
        while (i < command.length) {
            val c = command[i]
            if (c == '\\' && !escaped) {
                escaped = true
                i++
                continue
            }

            if (c == '\'' && !escaped && !inDoubleQuotes) {
                inSingleQuotes = !inSingleQuotes
            } else if (c == '"' && !escaped && !inSingleQuotes) {
                inDoubleQuotes = !inDoubleQuotes
            } else if (!inSingleQuotes && !inDoubleQuotes && !escaped) {
                if (c == '|' || c == '&' || c == '>' || c == '<' || c == ';') return true
            }

            escaped = false
            i++
        }
        return false
    }

    private fun parseCommand(command: String): Array<String> {
        val result = mutableListOf<String>()
        val currentArg = StringBuilder()
        var i = 0
        var inSingleQuotes = false
        var inDoubleQuotes = false

        while (i < command.length) {
            val c = command[i]

            if (i < command.length - 1 && c == '\\') {
                val nextChar = command[i + 1]
                if (nextChar == '\'' || nextChar == '"') {
                    currentArg.append(nextChar)
                    i += 2
                    continue
                }
            }

            if (c == '\'' && !inDoubleQuotes) {
                inSingleQuotes = !inSingleQuotes
                i++
                continue
            }

            if (c == '"' && !inSingleQuotes) {
                inDoubleQuotes = !inDoubleQuotes
                i++
                continue
            }

            if (c == ' ' && !inSingleQuotes && !inDoubleQuotes) {
                if (currentArg.isNotEmpty()) {
                    result.add(currentArg.toString())
                    currentArg.clear()
                }
                i++
                continue
            }

            currentArg.append(c)
            i++
        }

        if (currentArg.isNotEmpty()) result.add(currentArg.toString())
        return result.toTypedArray()
    }

    private fun newShizukuProcess(command: Array<String>): Process {
        return newProcessMethod.invoke(null, command, null, null) as Process
    }

    private companion object {
        private val newProcessMethod: Method by lazy {
            Shizuku::class.java.getDeclaredMethod(
                "newProcess",
                Array<String>::class.java,
                Array<String>::class.java,
                String::class.java
            ).apply { isAccessible = true }
        }
    }
}
