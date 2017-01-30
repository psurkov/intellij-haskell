// This is a generated file. Not intended for manual editing.
package intellij.haskell.psi;

import org.jetbrains.annotations.NotNull;

import java.util.List;

public interface HaskellCidecls extends HaskellCompositeElement {

  @NotNull
  List<HaskellDataDeclaration> getDataDeclarationList();

  @NotNull
  List<HaskellDefaultDeclaration> getDefaultDeclarationList();

  @NotNull
  List<HaskellExpression> getExpressionList();

  @NotNull
  List<HaskellInlinePragmas> getInlinePragmasList();

  @NotNull
  List<HaskellInstanceDeclaration> getInstanceDeclarationList();

  @NotNull
  List<HaskellMinimalPragma> getMinimalPragmaList();

  @NotNull
  List<HaskellNewtypeDeclaration> getNewtypeDeclarationList();

  @NotNull
  List<HaskellSpecializePragma> getSpecializePragmaList();

}
