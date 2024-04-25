package com.replaymod.gradle.remap

import org.jetbrains.kotlin.analyzer.AnalysisResult
import org.jetbrains.kotlin.cli.common.config.KotlinSourceRoot
import org.jetbrains.kotlin.cli.jvm.compiler.KotlinCoreEnvironment
import org.jetbrains.kotlin.cli.jvm.compiler.NoScopeRecordCliBindingTrace
import org.jetbrains.kotlin.cli.jvm.compiler.TopDownAnalyzerFacadeForJVM
import org.jetbrains.kotlin.com.intellij.psi.search.GlobalSearchScope
import org.jetbrains.kotlin.psi.KtFile

fun analyze1521(environment: KotlinCoreEnvironment, ktFiles: List<KtFile>): AnalysisResult {
    return TopDownAnalyzerFacadeForJVM.analyzeFilesWithJavaIntegration(
        environment.project,
        ktFiles,
        NoScopeRecordCliBindingTrace(),
        environment.configuration,
        { scope: GlobalSearchScope -> environment.createPackagePartProvider(scope) }
    )
}

fun kotlinSourceRoot1521(path: String, isCommon: Boolean) = KotlinSourceRoot(path, isCommon)
