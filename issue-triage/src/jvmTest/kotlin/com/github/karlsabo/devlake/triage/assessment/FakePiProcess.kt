package com.github.karlsabo.devlake.triage.assessment

import java.io.File
import java.io.OutputStream
import java.nio.file.Files
import java.nio.file.Path

internal object FakePiProcess {
    @JvmStatic
    fun main(args: Array<String>) {
        when (args[0]) {
            "assessment" -> assess(args)
            "sleep" -> sleep(args)
            else -> error("Unknown fake pi mode: ${args[0]}")
        }
    }

    private fun assess(args: Array<String>) {
        Files.write(Path.of(args[1]), args.slice(4 until args.size))
        Files.copy(System.`in`, Path.of(args[2]))
        print(Files.readString(Path.of(args[3])))
    }

    private fun sleep(args: Array<String>) {
        Files.writeString(Path.of(args[1]), ProcessHandle.current().pid().toString())
        System.`in`.transferTo(OutputStream.nullOutputStream())
        Thread.sleep(30_000)
    }
}

internal fun fakePiCommand(mode: String, vararg files: Path): List<String> {
    val classpath = listOf(FakePiProcess::class.java, Unit::class.java)
        .map { type -> Path.of(type.protectionDomain.codeSource.location.toURI()).toString() }
        .distinct()
        .joinToString(File.pathSeparator)
    return listOf(
        Path.of(System.getProperty("java.home"), "bin", "java").toString(),
        "-cp",
        classpath,
        FakePiProcess::class.java.name,
        mode,
    ) + files.map(Path::toString)
}
