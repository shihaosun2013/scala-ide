package org.scalaide.core.compiler

import scala.reflect.internal.util.SourceFile

import org.eclipse.jface.text.Region
import org.scalaide.core.compiler.IScalaPresentationCompiler.Implicits.RichResponse
import org.scalaide.util.eclipse.RegionUtils.RichRegion

private object NamePrinter {
  private case class Location(src: SourceFile, offset: Int)
  private val RxAnonOrRefinementString = """<(?:\$anon:|refinement of) (.+)>""".r
}

/**
 * For printing names in an InteractiveCompilationUnit.
 */
class NamePrinter(cu: InteractiveCompilationUnit) {
  import NamePrinter._

  /**
   * Returns the fully qualified name of the symbol at the given offset if available.
   *
   * This method is used by "Copy Qualified Name" in the GUI.
   */
  def qualifiedNameAt(offset: Int): Option[String] = {
    cu.withSourceFile { (src, compiler) =>

      val scalaRegion = new Region(cu.sourceMap(cu.getContents()).scalaPos(offset), 1)
      compiler.askTypeAt(scalaRegion.toRangePos(src)).getOption() match {
        case Some(tree) => qualifiedName(Location(src, offset), compiler)(tree)
        case _ => None
      }
    }.flatten
  }

  private def qualifiedName(loc: Location, comp: IScalaPresentationCompiler)(t: comp.Tree): Option[String] = {
    val resp = comp.asyncExec(qualifiedNameImpl(loc, comp)(t))
    resp.getOption().flatten
  }

  private def qualifiedNameImpl(loc: Location, comp: IScalaPresentationCompiler)(t: comp.Tree): Option[String] = {
    def enclosingDefinition(currentTree: comp.Tree, loc: Location) = {
      def isEnclosingDefinition(t: comp.Tree) = t match {
        case _: comp.DefDef | _: comp.ClassDef | _: comp.ModuleDef | _: comp.PackageDef =>
          t.pos.properlyIncludes(currentTree.pos)
        case _ => false
      }

      comp.askLoadedTyped(loc.src, true).getOption().map { fullTree =>
        comp.locateIn(fullTree, comp.rangePos(loc.src, loc.offset, loc.offset, loc.offset), isEnclosingDefinition)
      }
    }

    def qualifiedNameImplPrefix(loc: Location, t: comp.Tree) = {
      enclosingDefinition(t, loc) match {
        case Some(comp.EmptyTree) | None => ""
        case Some(encDef) => qualifiedNameImpl(loc, comp)(encDef).map(_ + ".").getOrElse("")
      }
    }

    def handleImport(loc: Location, tree: comp.Tree, selectors: List[comp.ImportSelector]) = {
      def isRelevant(selector: comp.ImportSelector) = {
        selector.name != comp.nme.WILDCARD &&
          selector.name == selector.rename
      }

      val suffix = selectors match {
        case List(selector) if isRelevant(selector) => "." + selector.name.toString
        case _ => ""
      }

      (Option(tree.symbol).map(_.fullName + suffix), false)
    }

    def symbolName(symbol: comp.Symbol) = {
      if (symbol.isParameter)
        shortName(symbol.name)
      else
        symbol.fullName
    }

    def vparamssStr(vparamss: List[List[comp.ValDef]]) = {
      if (vparamss.isEmpty) {
        ""
      } else {
        vparamss.map(vparamsStr(_)).mkString("")
      }
    }

    def vparamsStr(vparams: List[comp.ValDef]) = {
      "(" + vparams.map(vparmStr(_)).mkString(", ") + ")"
    }

    def vparmStr(valDef: comp.ValDef) = {
      val name = valDef.name
      val tpt = valDef.tpt

      val declPrinter = new DeclarationPrinter {
        val compiler: comp.type = comp
      }

      name.toString + ": " + declPrinter.showType(tpt.tpe)
    }

    def tparamsStr(tparams: List[comp.TypeDef]) = {
      if (tparams.isEmpty) {
        ""
      } else {
        "[" + tparams.map(tparamStr(_)).mkString(", ") + "]"
      }
    }

    def tparamStr(tparam: comp.TypeDef) = {
      shortName(tparam.name)
    }

    def shortName(name: comp.Name) = {
      val fullName = name.toString
      fullName.split(".").lastOption.getOrElse(fullName)
    }

    def handleIdent(ident: comp.Ident) = {
      ident.name match {
        case typeName: comp.TypeName => (Some(ident.symbol.fullName), false)
        case _ => (Some(ident.symbol.nameString), true)
      }
    }

    def handleValDef(valDef: comp.ValDef) = {
      (Some(valDef.symbol.nameString), true)
    }

    def handleClassDef(classDef: comp.ClassDef) = {
      val (className, qualifiy) = classDef.symbol match {
        case classSym: comp.ClassSymbol if (classSym.isAnonymousClass) =>
          (anonClassSymStr(classSym), true)
        case sym =>
          (sym.nameString, true)
      }

      (Some(className + tparamsStr(classDef.tparams)), qualifiy)
    }

    def handledefDef(defDef: comp.DefDef) = {
      val symName = defDef.symbol.nameString
      (Some(symName + tparamsStr(defDef.tparams) + vparamssStr(defDef.vparamss)), true)
    }

    def anonClassSymStr(classSym: comp.ClassSymbol) = {
      // Using a regular expression to extract information from anonOrRefinementString is not
      // ideal, but the only easy way I found to reuse the functionality already implemented
      // by Definitions.parentsString.
      val symStr = classSym.anonOrRefinementString match {
        case RxAnonOrRefinementString(symStr) => symStr
        case _ => classSym.toString
      }
      s"new $symStr {...}"
    }

    def handleSelect(select: comp.Select) = select.qualifier match {
      case comp.Block(List(stat: comp.ClassDef), _) if stat.symbol.isAnonOrRefinementClass && stat.symbol.isInstanceOf[comp.ClassSymbol] =>
        (Some(anonClassSymStr(stat.symbol.asInstanceOf[comp.ClassSymbol]) + "." + select.symbol.nameString), false)
      case _ => (Some(t.symbol.fullName), false)
    }

    def handleModuleDef(moduleDef: comp.ModuleDef) = {
      if (moduleDef.symbol.isPackageObject)
        (Some(moduleDef.symbol.owner.fullName), false)
      else
        (Some(moduleDef.symbol.name), true)
    }

    def handlePackageDef(packageDef: comp.PackageDef) = {
      val name = {
        if (packageDef.symbol.isEmptyPackage) None
        else Some(packageDef.symbol.fullName)
      }

      (name, false)
    }

    if (t.symbol.isInstanceOf[comp.NoSymbol])
      None
    else {
      val (name, qualify) = t match {
        case select: comp.Select => handleSelect(select)
        case defDef: comp.DefDef => handledefDef(defDef)
        case classDef: comp.ClassDef => handleClassDef(classDef)
        case moduleDef: comp.ModuleDef => handleModuleDef(moduleDef)
        case valDef: comp.ValDef => handleValDef(valDef)
        case comp.Import(tree, selectors) => handleImport(loc, tree, selectors)
        case ident: comp.Ident => handleIdent(ident)
        case packageDef: comp.PackageDef => handlePackageDef(packageDef)
        case _ => (Option(t.symbol).map(symbolName(_)), true)
      }

      val prefix = if (qualify) qualifiedNameImplPrefix(loc, t) else ""
      name.map(prefix + _)
    }
  }
}
