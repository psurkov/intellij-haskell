/*
 * Copyright 2014 Rik van der Kleij
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

package com.powertuple.intellij.haskell.external

import com.intellij.openapi.project.Project
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.psi.{PsiDirectory, PsiFile}
import com.powertuple.intellij.haskell.HaskellNotificationGroup
import com.powertuple.intellij.haskell.util.HaskellFileIndex

import scala.annotation.tailrec
import scala.collection.mutable.ListBuffer

private[external] object GhcModiInfo {

  private final val GhcModiInfoPattern = """(.+)-- Defined at (.+):([\d]+):([\d]+)""".r
  private final val GhcModiInfoLibraryPathPattern = """(.+)-- Defined in ‘([\w\.\-]+):([\w\.\-]+)’""".r
  private final val GhcModiInfoLibraryPattern = """(.+)-- Defined in ‘([\w\.\-]+)’""".r

  def findInfoFor(ghcModi: GhcModi, psiFile: PsiFile, expression: String): Seq[ExpressionInfo] = {
    val cmd = s"info ${psiFile.getOriginalFile.getVirtualFile.getPath} $expression"
    val ghcModiOutput = ghcModi.execute(cmd)
    (for {
      outputLine <- ghcModiOutput.outputLines.headOption
      expressionInfos <- createExpressionInfos(outputLine, psiFile.getProject)
    } yield expressionInfos).getOrElse(Seq())
  }

  private def createExpressionInfos(outputLine: String, project: Project): Option[Seq[ExpressionInfo]] = {
    if (outputLine == "Cannot show info") {
      None
    } else {
      val outputLines = outputLine.split("\u0000")
      val expressionStrings = createExpressionStrings(outputLines, ListBuffer()).map(_.mkString)
      Some(expressionStrings.flatMap(s => createExpressionInfo(s, project)))
    }
  }

  @tailrec
  private def createExpressionStrings(outputLines: Array[String], expressions: ListBuffer[Array[String]]): ListBuffer[Array[String]] = {
    if (outputLines.exists(_.contains("Defined"))) {
      val index = outputLines.indexWhere(_.contains("Defined"))
      val pair = outputLines.splitAt(index + 1)
      createExpressionStrings(pair._2, expressions.+=(pair._1))
    } else {
      expressions
    }
  }

  private def createExpressionInfo(expressionInfo: String, project: Project): Option[ExpressionInfo] = {
    expressionInfo match {
      case GhcModiInfoPattern(typeSignature, filePath, lineNr, colNr) => Some(ProjectExpressionInfo(typeSignature.trim, filePath, lineNr.toInt, colNr.toInt))
      case GhcModiInfoLibraryPathPattern(typeSignature, libraryName, module) => if (libraryName == "ghc-prim" || libraryName == "integer-gmp") {
        Some(BuiltInExpressionInfo(typeSignature.trim, libraryName, "GHC.Base"))
      }
      else {
        findModuleFilePath(module, project).map(LibraryExpressionInfo(typeSignature.trim, _, module))
      }
      case GhcModiInfoLibraryPattern(typeSignature, module) => findModuleFilePath(module, project).map(LibraryExpressionInfo(typeSignature, _, module))
      case _ => HaskellNotificationGroup.notifyError(s"Unknown pattern for ghc-modi info output: $expressionInfo"); None
    }
  }

  private def findModuleFilePath(module: String, project: Project): Option[String] = {
    val moduleFilePath = for {
      (name, path) <- getNameAndPathForModule(module)
      val files = HaskellFileIndex.getFilesByName(project, name, GlobalSearchScope.allScope(project))
      file <- files.find(hf => checkPath(hf.getContainingDirectory, path))
      filePath <- Some(file.getVirtualFile.getPath)
    } yield filePath

    moduleFilePath match {
      case Some(p) => Some(p)
      case None => HaskellNotificationGroup.notifyError(s"Could not find file path for `$module`. Please add sources of this library/package to 'Project Settings'/Libraries"); None
    }
  }

  private def getNameAndPathForModule(module: String) = {
    module.split('.').toList.reverse match {
      case n :: d => Some(n, d)
      case _ => HaskellNotificationGroup.notifyError(s"Could not determine path for $module"); None
    }
  }

  private def checkPath(dir: PsiDirectory, dirNames: List[String]): Boolean = {
    dirNames match {
      case h :: t if dir.getName == h => checkPath(dir.getParentDirectory, t)
      case h :: t => false
      case _ => true
    }
  }
}

sealed abstract class ExpressionInfo {
  def typeSignature: String
}

abstract class FileExpressionInfo extends ExpressionInfo {
  def filePath: String
}

case class ProjectExpressionInfo(typeSignature: String, filePath: String, lineNr: Int, colNr: Int) extends FileExpressionInfo

case class LibraryExpressionInfo(typeSignature: String, filePath: String, module: String) extends FileExpressionInfo

case class BuiltInExpressionInfo(typeSignature: String, libraryName: String, module: String) extends ExpressionInfo