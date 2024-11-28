@file:Suppress("OPT_IN_USAGE")

package org.starrel.phototool

import kotlinx.cli.*
import kotlinx.coroutines.runBlocking
import org.apache.commons.io.FilenameUtils
import org.apache.commons.io.IOCase
import java.io.File
import java.io.FileFilter
import java.util.*
import java.util.concurrent.Executors
import kotlin.script.experimental.api.*
import kotlin.script.experimental.host.toScriptSource
import kotlin.script.experimental.jvm.dependenciesFromCurrentContext
import kotlin.script.experimental.jvm.jvm
import kotlin.script.experimental.jvmhost.BasicJvmScriptingHost
import kotlin.script.experimental.jvmhost.createJvmCompilationConfigurationFromTemplate
import kotlin.system.exitProcess

val File.yy: String
    get() = Calendar.getInstance().run {
        timeInMillis = lastModified()
        (get(Calendar.YEAR) % 100).toString().padStart(2, '0')
    }
val File.MM: String
    get() = Calendar.getInstance().run {
        timeInMillis = lastModified()
        get(Calendar.MONTH).toString().padStart(2, '0')
    }
val File.dd: String
    get() = Calendar.getInstance().run {
        timeInMillis = lastModified()
        get(Calendar.DAY_OF_MONTH).toString().padStart(2, '0')
    }
val File.HH: String
    get() = Calendar.getInstance().run {
        timeInMillis = lastModified()
        get(Calendar.HOUR_OF_DAY).toString().padStart(2, '0')
    }
val File.mm: String
    get() = Calendar.getInstance().run {
        timeInMillis = lastModified()
        get(Calendar.MINUTE).toString().padStart(2, '0')
    }
val File.ss: String
    get() = Calendar.getInstance().run {
        timeInMillis = lastModified()
        get(Calendar.SECOND).toString().padStart(2, '0')
    }

fun main(args: Array<String>) {
    val parser = ArgParser("phototool")
    parser.subcommands(object : Subcommand("group", "group files") {
        val srcDir by option(ArgType.String, shortName = "s", description = "Source directory").required()
        val dstDir by option(ArgType.String, shortName = "d", description = "Destination directory")
        val recursive by option(ArgType.Boolean, shortName = "r", description = "Recursive").default(false)
        val overwrite by option(ArgType.Boolean, shortName = "o", description = "Overwrite").default(false)

        val groupPattern by option(
            ArgType.String,
            description = "Pattern"
        ).default("\"\${yy}-\${MM}-\${dd}/\${yy}\${MM}\${dd}\${HH}\${mm}_\${nameWithoutExtension}.\${extension}\"")

        val fileNamePattern by argument(ArgType.String).vararg().optional()

        override fun execute() {
            val src = File(srcDir)
            if (!src.isDirectory) error("Not a directory: $srcDir")
            val dst = File(dstDir ?: srcDir)
            if (!dst.isDirectory) error("Not a directory: $dstDir")

            val patterns = fileNamePattern
            if (patterns.isEmpty()) error("No pattern specified")

            val fileFilter = FileFilter {
                it.isFile && it.canRead() && patterns.any { pattern ->
                    FilenameUtils.wildcardMatch(it.name, pattern, IOCase.INSENSITIVE)
                }
            }

            val fileWalker: suspend (suspend (File) -> Unit) -> Unit
            if (recursive) {
                fileWalker = {
                    suspend fun i(dir: File) {
                        dir.listFiles()?.forEach { file ->
                            if (file.isDirectory) {
                                if (!file.canExecute()) return@forEach
                                i(file)
                            } else if (file.isFile) {
                                if (!file.canRead()) return@forEach
                                if (fileFilter.accept(file)) it(file)
                            }
                        }
                    }
                    i(src)
                }
            } else {
                fileWalker = {
                    src.listFiles(fileFilter)?.forEach { file ->
                        it(file)
                    }
                }
            }

            val compilationConfiguration = createJvmCompilationConfigurationFromTemplate<Any> {
                jvm {
                    dependenciesFromCurrentContext(wholeClasspath = true)
                }
                implicitReceivers(File::class)
                defaultImports(
                    "java.util.*",
                    "java.io.File",
                    "org.starrel.phototool.*",
                    "org.apache.commons.io.FilenameUtils"
                )
            }
            val scriptHost = BasicJvmScriptingHost()
            val compile = scriptHost.compiler
            runBlocking {
                val compileResult =
                    compile(groupPattern.toScriptSource("pattern"), compilationConfiguration)
                val compiledScript = when (compileResult) {
                    is ResultWithDiagnostics.Failure -> error("Failed to compile script: $compileResult")
                    is ResultWithDiagnostics.Success<CompiledScript> -> compileResult.value
                }

                fileWalker { file ->
                    val eval = scriptHost.evaluator
                    val evalResult = eval(compiledScript, ScriptEvaluationConfiguration {
                        implicitReceivers(file)
                    })
                    val returnValue = when (evalResult) {
                        is ResultWithDiagnostics.Failure -> error("Failed to evaluate script: $evalResult")
                        is ResultWithDiagnostics.Success<EvaluationResult> -> {
                            val returnValue = evalResult.value.returnValue
                            when (returnValue) {
                                is ResultValue.Error -> error("Error: ${returnValue.error}")
                                ResultValue.NotEvaluated -> error("Not evaluated")
                                is ResultValue.Unit -> error("Unit")
                                is ResultValue.Value -> returnValue.value ?: error("Null value")
                            }
                        }
                    } as? String ?: error("Return value is not a string")

                    val dstFile = dst.resolve(returnValue)
                    if (!dstFile.exists() || overwrite) {
                        file.renameTo(dstFile)
                    }
                }
            }
        }
    })

    parser.subcommands(object : Subcommand("gifconv", "convert hif to jpg") {
        val srcDir by option(ArgType.String, shortName = "s", description = "Source directory")
        val dstDir by option(ArgType.String, shortName = "d", description = "Destination directory")

        val srcPattern by option(ArgType.String, shortName = "p", description = "Source file pattern")
            .default("*.HIF")
        val dstPattern by option(ArgType.String, shortName = "o", description = "Destination file pattern")
            .default("%s.JPG")

        val concurrency by option(ArgType.Int, shortName = "c", description = "Concurrency").default(1)

        val cmdPattern by option(ArgType.String, shortName = "x", description = "Command pattern")
            .default("magick \$SRC \$DST")

        override fun execute() {
            val executor = Executors.newFixedThreadPool(concurrency)

            val actSrcDir = srcDir ?: ""
            val actDstDir = dstDir ?: ""

            val cmds = cmdPattern.split(" ")

            val files = File(actSrcDir).listFiles { dir, name ->
                FilenameUtils.wildcardMatch(name, srcPattern, IOCase.INSENSITIVE)
            } ?: run {
                System.err.println("No files found in $actSrcDir")
                exitProcess(1)
            }

            val count = files.size
            var converted = 0
            files.forEach {
                val newName = dstPattern.format(it.nameWithoutExtension)
                executor.execute {
                    val src = File(it.absolutePath)
                    val dst = File(actDstDir, newName)
                    val process = ProcessBuilder(cmds.map {
                        it.replace("\$SRC", src.absolutePath).replace("\$DST", dst.absolutePath)
                    }).start()
                    process.waitFor()
                    converted++
                    println("$converted/$count")
                }
            }
            executor.shutdown()
            executor.awaitTermination(Long.MAX_VALUE, java.util.concurrent.TimeUnit.MILLISECONDS)
            println("all done")
        }
    })
    parser.parse(args)
}
