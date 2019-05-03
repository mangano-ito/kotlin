package org.jetbrains.konan.resolve

import com.intellij.openapi.externalSystem.model.DataNode
import com.intellij.openapi.externalSystem.model.ProjectKeys
import com.intellij.openapi.externalSystem.service.project.ProjectDataManager
import com.intellij.openapi.externalSystem.util.ExternalSystemApiUtil
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VfsUtilCore
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.openapi.vfs.VirtualFileVisitor
import com.intellij.psi.PsiManager
import com.jetbrains.cidr.lang.symbols.OCSymbol
import com.jetbrains.cidr.lang.symbols.cpp.OCIncludeSymbol
import org.jetbrains.kotlin.idea.KotlinFileType
import org.jetbrains.kotlin.psi.KtFile
import org.jetbrains.plugins.gradle.util.GradleConstants

class KotlinFrameworkTranslator(val project: Project) {
    fun translateModule(konanFile: KonanBridgeVirtualFile): Sequence<OCSymbol> {
        val sources = collectSources(konanFile)
        val ktFile = sources.firstOrNull()?.let { PsiManager.getInstance(project).findFile(it) } as? KtFile
                     ?: return emptySequence()

        val baseDeclarations = KotlinFileTranslator(project).translateBase(ktFile)
        val includes = sources.asSequence().map { include(konanFile, it) }

        return baseDeclarations + includes
    }

    private fun collectSources(konanFile: KonanBridgeVirtualFile): List<VirtualFile> {
        val projectNode = ProjectDataManager.getInstance().getExternalProjectsData(project, GradleConstants.SYSTEM_ID).first().externalProjectStructure as DataNode<*>
        val moduleNodes = ExternalSystemApiUtil.findAll(projectNode, ProjectKeys.MODULE)
        val taskName = ":" + konanFile.target.name
        val moduleNode = moduleNodes.find { module -> module.data.id == taskName } ?: return emptyList()
        val contentRoots = ExternalSystemApiUtil.findAll(moduleNode, ProjectKeys.CONTENT_ROOT)

        val vfs = LocalFileSystem.getInstance()

        return contentRoots.asSequence()
            .mapNotNull { node -> vfs.findFileByPath(node.data.rootPath) }
            //todo process only `./***Main/kotlin/`
            .flatMap { dir -> findAllSources(dir).asSequence() }
            .toList()
    }

    private fun findAllSources(sourceRoot: VirtualFile): List<VirtualFile> {
        val result = arrayListOf<VirtualFile>()

        VfsUtilCore.visitChildrenRecursively(sourceRoot, object : VirtualFileVisitor<Nothing>() {
            override fun visitFile(file: VirtualFile): Boolean {
                if (file.fileType == KotlinFileType.INSTANCE) {
                    result.add(file)
                }
                return true
            }
        })

        return result
    }

    private fun include(konanFile: KonanBridgeVirtualFile, target: VirtualFile): OCIncludeSymbol {
        return OCIncludeSymbol(konanFile, 0, target, OCIncludeSymbol.IncludePath.EMPTY, true, false, 0, null, true)
    }
}
