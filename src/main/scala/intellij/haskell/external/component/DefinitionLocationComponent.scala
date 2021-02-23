/*
 * Copyright 2014-2020 Rik van der Kleij
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package intellij.haskell.external.component

import com.github.blemale.scaffeine.{LoadingCache, Scaffeine}
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.project.Project
import com.intellij.psi.PsiFile
import intellij.haskell.external.repl.{ProjectStackRepl, StackReplsManager}
import intellij.haskell.navigation.HaskellReference
import intellij.haskell.psi._
import intellij.haskell.util._
import intellij.haskell.util.index.HaskellModuleNameIndex._

import scala.concurrent.TimeoutException

private[component] object DefinitionLocationComponent {
  private final val LocAtPattern = """(.+):\(([\d]+),([\d]+)\)-\(([\d]+),([\d]+)\)""".r
  private final val PackageModulePattern = """([\w\-\d.]+)(?:-.*)?:([\w.\-]+)""".r

  // importQualifier is only set for identifiers in import declarations
  private case class Key(psiFile: PsiFile, qualifiedNameElement: HaskellQualifiedNameElement, importQualifier: Option[String])

  type DefinitionLocationResult = Either[NoInfo, DefinitionLocation]

  private final val Cache: LoadingCache[Key, DefinitionLocationResult] = Scaffeine().build((k: Key) => findDefinitionLocationResult(k))

  def findDefinitionLocation(psiFile: PsiFile, qualifiedNameElement: HaskellQualifiedNameElement, importQualifier: Option[String]): DefinitionLocationResult = {
    val key = Key(psiFile, qualifiedNameElement, importQualifier)

    try {
      val result = Cache.get(key)
      result match {
        case Right(_) => result
        case Left(ReadActionTimeout(_)) | Left(IndexNotReady) | Left(ModuleNotAvailable(_)) | Left(ReplNotAvailable) =>
          Cache.invalidate(key)
          result
        case _ => result
      }
    } catch {
      case e: TimeoutException => Left(ReadActionTimeout(e.getMessage))
    }
  }

  def findReferringToLocationsCache(qualifiedNameElement: HaskellQualifiedNameElement) = {
    Cache.asMap().filter { case (_, v) => v.toOption.exists(_.namedElement == qualifiedNameElement.getIdentifierElement) }.map(e => (e._1.psiFile, e._1.qualifiedNameElement)).toSeq
  }

  def invalidateNotFound(project: Project): Unit = {
    Cache.asMap().filter { case (k, v) => k.psiFile.getProject == project && v.isLeft && HaskellProjectUtil.isSourceFile(k.psiFile) }.keys.foreach(Cache.invalidate)
  }

  def invalidateAll(project: Project): Unit = {
    val synchronousCache = Cache
    synchronousCache.asMap().filter(_._1.psiFile.getProject == project).keys.foreach(synchronousCache.invalidate)
  }

  def invalidate(project: Project): Unit = {
    val keys = Cache.asMap().collect { case (k, v) if k.psiFile.getProject == project =>
      if (checkValidKey(k)) {
        v.toOption match {
          case Some(definitionLocation) if checkValidLocation(definitionLocation) && checkValidKey(k) && checkValidName(k, definitionLocation) => None
          case _ => Some(k)
        }
      } else {
        Some(k)
      }
    }.flatten
    Cache.invalidateAll(keys)
  }

  private def ignoreException(action: => Boolean): Boolean = {
    try {
      action
    } catch {
      case _: Throwable => false
    }
  }

  private def checkValidKey(key: Key): Boolean = {
    ApplicationUtil.runReadAction(ignoreException(key.qualifiedNameElement.isValid)) && ApplicationUtil.runReadAction(ignoreException(key.qualifiedNameElement.getIdentifierElement.isValid))
  }

  private def checkValidLocation(definitionLocation: DefinitionLocation): Boolean = {
    ApplicationUtil.runReadAction(ignoreException(definitionLocation.namedElement.isValid))
  }

  private def checkValidName(key: Key, definitionLocation: DefinitionLocation): Boolean = {
    val keyName = ApplicationUtil.runReadAction(Option(key.qualifiedNameElement.getIdentifierElement.getName))
    keyName == ApplicationUtil.runReadAction(Option(definitionLocation.namedElement.getName)) &&
      keyName.contains(definitionLocation.originalName)
  }

  private def findDefinitionLocationResult(key: Key): DefinitionLocationResult = {
    val psiFile = key.psiFile
    val project = psiFile.getProject
    val qualifiedNameElement = key.qualifiedNameElement
    val identifierElement = qualifiedNameElement.getIdentifierElement
    val isLibraryFile = HaskellProjectUtil.isLibraryFile(psiFile)

    ProgressManager.checkCanceled()

    lazy val elementType = identifierElement.getNode.getElementType
    lazy val moduleName = HaskellPsiUtil.findModuleName(psiFile)
    lazy val importQualifier = key.importQualifier

    // GHCi :loc-at does not always give right location for qualified identifiers. It depends on the order of import declarations...
    // So in case of qualified identifiers :info is used to find definition location as second solution.
    if (isLibraryFile || importQualifier.isDefined || qualifiedNameElement.getQualifierName.isDefined) {
      ProgressManager.checkCanceled()
      findLocationByImportedIdentifiers(project, psiFile, importQualifier, qualifiedNameElement).orElse(findLocationByNameInfo(project, psiFile, qualifiedNameElement, identifierElement))
    } else if (elementType == HaskellTypes.HS_CONID || elementType == HaskellTypes.HS_CONSYM) {
      // GHCi :loc-at does not always give the right location so using :info in the case is workaround
      ProgressManager.checkCanceled()
      findLocationByNameInfo(project, psiFile, qualifiedNameElement, identifierElement).orElse(findLocationByImportedIdentifiers(project, psiFile, importQualifier, qualifiedNameElement))
    } else {
      ProgressManager.checkCanceled()
      findLocationByRepl(project, psiFile, moduleName, qualifiedNameElement).orElse(findLocationByImportedIdentifiers(project, psiFile, importQualifier, qualifiedNameElement))
    }
  }

  private def findLocationByNameInfo(project: Project, psiFile: PsiFile, qualifiedNameElement: HaskellQualifiedNameElement, identifierElement: HaskellNamedElement): Either[NoInfo, PackageModuleLocation] = {
    val name = identifierElement.getName
    HaskellComponentsManager.findNameInfo(qualifiedNameElement) match {
      case Right(infos) => infos.headOption match {
        case Some(info) =>
          ProgressManager.checkCanceled()

          HaskellReference.findIdentifiersByNameInfo(info, identifierElement, project) match {
            case Right((mn, ne, pn)) => Right(PackageModuleLocation(mn.getOrElse("-"), ne, name, pn))
            case Left(noInfo) =>
              HaskellReference.findIdentifierInFileByName(psiFile, name, prioIdInExpression = true).
                map(ne => Right(PackageModuleLocation(findModuleName(ne), ne, name, None))).getOrElse(Left(noInfo))
          }
        case None => Left(NoInfoAvailable(name, psiFile.getName))
      }
      case Left(noInfo) => Left(noInfo)
    }
  }

  private def findModuleName(namedElement: HaskellNamedElement) = {
    Option(namedElement.getContainingFile).flatMap(HaskellPsiUtil.findModuleName).getOrElse("-")
  }

  import scala.concurrent.ExecutionContext.Implicits.global

  private def findLocationByImportedIdentifiers(project: Project, psiFile: PsiFile, importQualifier: Option[String], qualifiedNameElement: HaskellQualifiedNameElement): Either[NoInfo, PackageModuleLocation] = {
    ProgressManager.checkCanceled()

    val qName = qualifiedNameElement.getName
    val name = qualifiedNameElement.getIdentifierElement.getName

    // Explicitly add module name of identifier
    val qualifiedName = importQualifier match {
      case None => qName
      case Some(q) => q + "." + name
    }

    val moduleIdentifiers = {
      if (HaskellProjectUtil.isSourceFile(psiFile)) {
        FileModuleIdentifiers.findAvailableModuleIdentifiers(psiFile)
      } else {
        HaskellPsiUtil.findModuleName(psiFile).map(moduleName =>
          LibraryPackageInfoComponent.findLibraryModuleName(moduleName) match {
            case Some(true) =>
              ScalaFutureUtil.waitForValue(project,
                HaskellComponentsManager.findModuleIdentifiers(project, moduleName)
                , s"finding library module identifiers in DefinitionLocationComponent for module $moduleName").flatten.getOrElse(Iterable())
            case _ =>
              HaskellPsiUtil.findTopLevelDeclarations(psiFile).
                map(d => d.getName).flatMap(d => BrowseModuleComponent.createLibraryModuleIdentifier(project, d, moduleName).toSeq)
          })
      }.getOrElse(Iterable())
    }.filter(_.name == qualifiedName)


    ProgressManager.checkCanceled()

    val moduleNames = moduleIdentifiers.map(mi => if (mi.moduleName == HaskellProjectUtil.Prelude) mi.preludeBaseModuleName.getOrElse(HaskellProjectUtil.Prelude) else mi.moduleName).toSeq

    HaskellReference.findIdentifiersByModulesAndName(project, moduleNames.filterNot(_ == HaskellProjectUtil.Prelude), name) match {
      case Right((mn, ne)) => Right(PackageModuleLocation(mn, ne, name, None))
      case Left(noInfo) => Left(noInfo)
    }
  }

  private def findLocationByRepl(project: Project, psiFile: PsiFile, moduleName: Option[String], qualifiedNameElement: HaskellQualifiedNameElement): DefinitionLocationResult = {
    ProgressManager.checkCanceled()

    val name = qualifiedNameElement.getIdentifierElement.getName

    val findLocationInfo = for {
      vf <- HaskellFileUtil.findVirtualFile(psiFile)
      sp <- LineColumnPosition.fromOffset(vf, qualifiedNameElement.getTextRange.getStartOffset)
      ep <- LineColumnPosition.fromOffset(vf, qualifiedNameElement.getTextRange.getEndOffset)
    } yield {
      repl: ProjectStackRepl => repl.findLocationInfo(moduleName, psiFile, sp.lineNr, sp.columnNr, ep.lineNr, ep.columnNr, name)
    }

    ProgressManager.checkCanceled()

    val locationInfo = findLocationInfo match {
      case None => Left(NoInfoAvailable(name, psiFile.getContainingFile.getName))
      case Some(f) =>
        StackReplsManager.getProjectRepl(psiFile) match {
          case Some(repl) =>
            if (!repl.available) {
              Left(ReplNotAvailable)
            } else {
              f(repl) match {
                case Some(o) if o.stderrLines.isEmpty && o.stdoutLines.nonEmpty => Right(o.stdoutLines)
                case Some(o) if o.stderrLines.mkString.contains("No matching export in any local modules.") => Left(NoMatchingExport)
                case Some(o) if o.stdoutLines.isEmpty && o.stderrLines.nonEmpty => Right(o.stderrLines) // For some unknown reason REPL write sometimes correct output to stderr
                case None => Left(ReplNotAvailable)
                case _ => Left(NoInfoAvailable(name, psiFile.getName))
              }
            }
          case None => Left(ReplNotAvailable)
        }
    }

    locationInfo match {
      case Right(o) => o.headOption.map(l => createLocationByReplResult(project, psiFile, l, name)) match {
        case Some(r) => r
        case None => Left(NoInfoAvailable(name, psiFile.getName))
      }
      case Left(NoMatchingExport) => HaskellReference.findIdentifierInFileByName(psiFile, name, prioIdInExpression = true) match {
        case Some(ne) => Right(LocalModuleLocation(psiFile, ne, name))
        case None => Left(NoInfoAvailable(name, psiFile.getName))
      }
      case Left(noInfo) => Left(noInfo)
    }
  }

  private def createLocationByReplResult(project: Project, psiFile: PsiFile, output: String, name: String): DefinitionLocationResult = {
    ProgressManager.checkCanceled()

    output match {
      case LocAtPattern(filePath, startLineNr, startColumnNr, _, _) =>
        // Calling here findFile ProgressIndicatorUtils.scheduleWithWriteActionPriority blocks the UI
        HaskellFileUtil.findFile(project, filePath) match {
          case (Some(vf), Some(pf)) =>
            ProgressManager.checkCanceled()

            HaskellReference.findIdentifierByLocation(project, vf, pf, startLineNr.toInt, startColumnNr.toInt, name) match {
              case Some(e) => Right(LocalModuleLocation(pf, e, name))
              case None => Left(NoInfoAvailable(name, psiFile.getName))
            }
          case (_, _) => Left(NoInfoAvailable(name, psiFile.getName))
        }
      case PackageModulePattern(pn, mn) =>
        findFilesByModuleName(project, mn) match {
          case Right(files) =>
            ProgressManager.checkCanceled()

            files.headOption.flatMap(HaskellReference.findIdentifierInFileByName(_, name, prioIdInExpression = true)) match {
              case Some(e) => Right(PackageModuleLocation(mn, e, name, Some(pn)))
              case None => Left(NoInfoAvailable(name, psiFile.getName))
            }
          case Left(noInfo) => Left(noInfo)
        }
      case _ => Left(NoInfoAvailable(name, psiFile.getName))
    }
  }

}

sealed trait DefinitionLocation {
  def namedElement: HaskellNamedElement

  def originalName: String
}

case class PackageModuleLocation(moduleName: String, namedElement: HaskellNamedElement, originalName: String, packageName: Option[String]) extends DefinitionLocation

case class LocalModuleLocation(psiFile: PsiFile, namedElement: HaskellNamedElement, originalName: String) extends DefinitionLocation

