package me.rajin.parse

import org.jetbrains.kotlin.cli.common.CLIConfigurationKeys
import org.jetbrains.kotlin.cli.common.messages.MessageCollector
import org.jetbrains.kotlin.cli.jvm.compiler.EnvironmentConfigFiles
import org.jetbrains.kotlin.cli.jvm.compiler.KotlinCoreEnvironment
import org.jetbrains.kotlin.com.intellij.mock.MockProject
import org.jetbrains.kotlin.com.intellij.openapi.Disposable
import org.jetbrains.kotlin.com.intellij.openapi.diagnostic.DefaultLogger
import org.jetbrains.kotlin.com.intellij.openapi.extensions.ExtensionPoint
import org.jetbrains.kotlin.com.intellij.openapi.extensions.Extensions.getRootArea
import org.jetbrains.kotlin.com.intellij.openapi.util.UserDataHolderBase
import org.jetbrains.kotlin.com.intellij.pom.PomModel
import org.jetbrains.kotlin.com.intellij.pom.PomModelAspect
import org.jetbrains.kotlin.com.intellij.pom.PomTransaction
import org.jetbrains.kotlin.com.intellij.pom.impl.PomTransactionBase
import org.jetbrains.kotlin.com.intellij.pom.tree.TreeAspect
import org.jetbrains.kotlin.com.intellij.psi.PsiFileFactory
import org.jetbrains.kotlin.com.intellij.psi.impl.source.tree.TreeCopyHandler
import org.jetbrains.kotlin.config.CompilerConfiguration
import org.jetbrains.kotlin.utils.PathUtil
import sun.reflect.ReflectionFactory
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.atomic.AtomicInteger
import java.util.zip.ZipFile
import org.jetbrains.kotlin.com.intellij.openapi.diagnostic.Logger as DiagnosticLogger

/**
 * Initializes Kotlin compiler and disposes it when it is not needed anymore.
 * code from ktLint
 * https://github.com/pinterest/ktlint/blob/650ce55addeca4c083f22450ae630660c86b5475/ktlint-core/src/main/kotlin/com/pinterest/ktlint/core/internal/KotlinPsiFileFactory.kt
 */
internal class KotlinPsiFileFactory {
    private var psiFileFactory: PsiFileFactory? = null
    private var tempFiles: Path? = null
    private val acquireCounter = AtomicInteger(0)
    private val initializerLock = Any()

    internal fun acquirePsiFileFactory(isFromCli: Boolean): PsiFileFactory = synchronized(initializerLock) {
        acquireCounter.incrementAndGet()
        return psiFileFactory ?: initializePsiFileFactory(isFromCli).also {
            psiFileFactory = it
        }
    }

    internal fun releasePsiFileFactory() = synchronized(initializerLock) {
        val acquiredCount = acquireCounter.decrementAndGet()
        if (acquiredCount == 0) {
            tempFiles?.toFile()?.deleteRecursively()
            tempFiles = null
            psiFileFactory = null
        }
    }

    /**
     * Initialize Kotlin Lexer.
     */
    private fun initializePsiFileFactory(isFromCli: Boolean): PsiFileFactory {
        DiagnosticLogger.setFactory(LoggerFactory::class.java)

        val compilerConfiguration = CompilerConfiguration()
        compilerConfiguration.put(CLIConfigurationKeys.MESSAGE_COLLECTOR_KEY, MessageCollector.NONE)
        tempFiles = if (isFromCli) {
            // Special workaround for KtLint CLI
            // See https://github.com/pinterest/ktlint/issues/1063 for details
            validateCompilerExtensionsFilesPath().also {
                compilerConfiguration.put(
                    CLIConfigurationKeys.INTELLIJ_PLUGIN_ROOT,
                    it.toAbsolutePath().toString()
                )
            }
        } else {
            null
        }

        val project = KotlinCoreEnvironment.createForProduction(
            Disposable {}, //
            compilerConfiguration,
            EnvironmentConfigFiles.JVM_CONFIG_FILES
        ).project as MockProject

        project.enableASTMutations()

        return PsiFileFactory.getInstance(project)
    }

    private fun validateCompilerExtensionsFilesPath(): Path {
        val tempDir = Files.createTempDirectory("ktlint")
        val extensionsDir = tempDir.resolve("META-INF").resolve("extensions")
        if (!Files.exists(extensionsDir)) {
            Files.createDirectories(extensionsDir)
            val resourcesPath = PathUtil.getResourcePathForClass(DiagnosticLogger::class.java)
            ZipFile(resourcesPath).use { zipFile ->
                zipFile.getInputStream(
                    zipFile.getEntry("META-INF/extensions/core.xml")
                ).copyTo(
                    Files.newOutputStream(extensionsDir.resolve("core.xml"))
                )
                zipFile.getInputStream(
                    zipFile.getEntry("META-INF/extensions/compiler.xml")
                ).copyTo(
                    Files.newOutputStream(extensionsDir.resolve("compiler.xml"))
                )
            }
        }

        return tempDir
    }

    /**
     * Enables AST mutations (`ktlint -F ...`).
     */
    private fun MockProject.enableASTMutations() {
        val extensionPoint = "org.jetbrains.kotlin.com.intellij.treeCopyHandler"
        val extensionClassName = TreeCopyHandler::class.java.name
        for (area in arrayOf(extensionArea, getRootArea())) {
            if (!area.hasExtensionPoint(extensionPoint)) {
                area.registerExtensionPoint(extensionPoint, extensionClassName, ExtensionPoint.Kind.INTERFACE)
            }
        }

        registerService(PomModel::class.java, FormatPomModel())
    }

    /**
     * Do not print anything to the stderr when lexer is unable to match input.
     */
    private class LoggerFactory : DiagnosticLogger.Factory {
        override fun getLoggerInstance(
            p: String
        ): DiagnosticLogger = object : DefaultLogger(null) {
            override fun warn(message: String?, t: Throwable?) {}
            override fun error(message: String?, vararg details: String?) {}
        }
    }

    private class FormatPomModel : UserDataHolderBase(), PomModel {

        override fun runTransaction(
            transaction: PomTransaction
        ) {
            (transaction as PomTransactionBase).run()
        }

        @Suppress("UNCHECKED_CAST")
        override fun <T : PomModelAspect> getModelAspect(
            aspect: Class<T>
        ): T? {
            if (aspect == TreeAspect::class.java) {
                // using approach described in https://git.io/vKQTo due to the magical bytecode of TreeAspect
                // (check constructor signature and compare it to the source)
                // (org.jetbrains.kotlin:kotlin-compiler-embeddable:1.0.3)
                val constructor = ReflectionFactory
                    .getReflectionFactory()
                    .newConstructorForSerialization(
                        aspect,
                        Any::class.java.getDeclaredConstructor(*arrayOfNulls<Class<*>>(0))
                    )
                return constructor.newInstance() as T
            }
            return null
        }
    }
}