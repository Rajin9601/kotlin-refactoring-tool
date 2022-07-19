package me.rajin.parse

import org.jetbrains.kotlin.idea.KotlinLanguage
import org.jetbrains.kotlin.psi.KtFile
import java.io.File

fun parseKtFile(file: File) = parseKtFile(file.readText())

fun parseKtFile(source: String): KtFile {
    val ktPsiFileFactory = KotlinPsiFileFactory()
    val psiFileFactory = ktPsiFileFactory.acquirePsiFileFactory(isFromCli = false)

    val ktFile = psiFileFactory.createFileFromText(
        "xxx_internal_name_xxx.kt",
        KotlinLanguage.INSTANCE,
        source
    ) as KtFile

    ktPsiFileFactory.releasePsiFileFactory()

    return ktFile
}
