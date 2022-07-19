package me.rajin.usecase.ribConvert
import me.rajin.parse.parseKtFile
import org.jetbrains.kotlin.name.FqName
import org.jetbrains.kotlin.psi.KtCallExpression
import org.jetbrains.kotlin.psi.KtClass
import org.jetbrains.kotlin.psi.KtNameReferenceExpression
import org.jetbrains.kotlin.psi.KtNamedFunction
import org.jetbrains.kotlin.psi.KtPsiFactory
import org.jetbrains.kotlin.psi.psiUtil.collectDescendantsOfType
import org.jetbrains.kotlin.psi.psiUtil.findDescendantOfType
import org.jetbrains.kotlin.psi.psiUtil.referenceExpression
import org.jetbrains.kotlin.resolve.ImportPath
import java.io.File
import java.nio.file.Files
import java.nio.file.Paths
import kotlin.io.path.isDirectory
import kotlin.io.path.isRegularFile
import kotlin.io.path.name

fun main(args: Array<String>) {
    Files.walk(Paths.get("/Users/david/vcnc/tada-android/tada-rider/src/main/java/kr/co/vcnc/tada/rider"))
        .filter { it.name.endsWith("View.kt") }
        .filter { it.isRegularFile() && it.isDirectory().not() }
        .forEach {
            processFile(it.toFile())
        }
}

fun processFile(file: File) {
    // 원하는 것 : untilDetach(this) -> autoDispose(scope)
    // scope 는 해당 함수의 인자로 넣는다.
    // 이렇게 했을때 결국 bindView 에서 시작되어야 함.
    println("process start: ${file.path}")
    val ast = parseKtFile(file)
    val classAST = ast.declarations.filterIsInstance<KtClass>()[0]
    if (classAST.superTypeListEntries.any { it.text == "RiderRibView" }.not()) return
    val functionList = classAST.declarations.filterIsInstance<KtNamedFunction>()
    var scopeAddFunctionList = emptyList<KtNamedFunction>()
    var scopeAddFunctionSet = emptySet<String>()
    // scopeAddFunctionList 생성
    while (true) {
        val beforeSize = scopeAddFunctionList.size
        scopeAddFunctionList = functionList.filter {
            if (it.name == "bindView") return@filter true
            val untilDetachCall = it.bodyExpression?.collectDescendantsOfType<KtCallExpression> { (it.referenceExpression() as? KtNameReferenceExpression)?.getReferencedName() == "untilDetach" }
            if (untilDetachCall?.isNotEmpty() == true) return@filter true
            it.bodyExpression?.findDescendantOfType<KtCallExpression> {
                val functionName = (it.referenceExpression() as? KtNameReferenceExpression)?.getReferencedName() ?: return@findDescendantOfType false
                scopeAddFunctionSet.contains(functionName)
            } != null
        }
        scopeAddFunctionSet = scopeAddFunctionList.map { it.name!! }.toSet()
        if (beforeSize == scopeAddFunctionList.size) break
    }

    scopeAddFunctionList.forEach {
        if (knownScopeFunctions.contains(it.name).not()) {
            println("error : not recognized function [${it.name}]")
        }
    }

    // scopeAddFunction 을 부르는 곳에 다 scope 넣어주기
    scopeAddFunctionList.forEach {
        it.bodyExpression?.collectDescendantsOfType<KtCallExpression> {
            val functionName = (it.referenceExpression() as? KtNameReferenceExpression)?.getReferencedName() ?: return@collectDescendantsOfType false
            scopeAddFunctionSet.contains(functionName)
        }?.forEach {
            val factory = KtPsiFactory(it)
            it.valueArgumentList?.addArgument(factory.createArgument("scope"))
        }

        it.bodyExpression?.collectDescendantsOfType<KtCallExpression> {
            (it.referenceExpression() as? KtNameReferenceExpression)?.getReferencedName() == "untilDetach"
        }?.forEach {
            val factory = KtPsiFactory(it)
            it.replace(factory.createExpression("autoDispose(scope)"))
        }
    }

    // scopeAddFunction 에 scope 넣어주기
    scopeAddFunctionList.forEach {
        val factory = KtPsiFactory(it)
        val param = factory.createParameter("scope: ScopeProvider")
        it.valueParameterList?.addParameter(param)
    }

    // import 문 넣기
    val factory = KtPsiFactory(ast.importList!!)
    fun createImport(pckage: String) {
        ast.importList?.add(factory.createWhiteSpace("\n"))
        ast.importList?.add(factory.createImportDirective(ImportPath(FqName(pckage), isAllUnder = false)))
    }
    createImport("autodispose2.ScopeProvider")
    createImport("autodispose2.autoDispose")

    // check: 문제가 없는지
    // 1: scopeAddFunction 이 private 인가
    // 2: scopeAddFunction 리스트가 우리가 원하는 리스트가 맞는가

    file.writeText(ast.text)
}

val knownScopeFunctions = setOf("bindView", "bindUI", "bindActions", "bindAction")