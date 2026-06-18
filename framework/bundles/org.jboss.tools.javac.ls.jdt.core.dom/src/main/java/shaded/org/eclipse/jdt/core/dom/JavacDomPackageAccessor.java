/*******************************************************************************
 * Copyright (c) 2026 Red Hat, Inc. and others.
 *
 * This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License 2.0
 * which accompanies this distribution, and is available at
 * https://www.eclipse.org/legal/epl-2.0/
 *
 * SPDX-License-Identifier: EPL-2.0
 *
 * Contributors:
 *     Red Hat, Inc. - initial API and implementation
 *******************************************************************************/
package shaded.org.eclipse.jdt.core.dom;


/**
 * Utility class to access package-private methods and fields in the DOM package.
 * This class lives in the shaded.org.eclipse.jdt.core.dom package so it can access
 * package-private members, and exposes them as public static methods for use
 * by JavacConverter and other classes in different packages.
 */
public class JavacDomPackageAccessor {

	/**
	 * Sets the line end table for a compilation unit.
	 * Package-private method: CompilationUnit.setLineEndTable(int[])
	 */
	public static void setLineEndTable(CompilationUnit cu, int[] lineEndTable) {
		cu.setLineEndTable(lineEndTable);
	}

	/**
	 * Sets the comment table for a compilation unit.
	 * Package-private method: CompilationUnit.setCommentTable(Comment[])
	 */
	public static void setCommentTable(CompilationUnit cu, Comment[] commentTable) {
		cu.setCommentTable(commentTable);
	}

	/**
	 * Sets the default node flag for an AST.
	 * Package-private method: AST.setDefaultNodeFlag(int)
	 */
	public static void setDefaultNodeFlag(AST ast, int flag) {
		ast.setDefaultNodeFlag(flag);
	}

	/**
	 * Sets the binding resolver for an AST.
	 * Package-private method: AST.setBindingResolver(AbstractBindingResolver)
	 */
	public static void setBindingResolver(AST ast, BindingResolver resolver) {
		ast.setBindingResolver(resolver);
	}

	/**
	 * Sets the parent of an AST node.
	 * Package-private method: ASTNode.setParent(ASTNode, StructuralPropertyDescriptor)
	 */
	public static void setParent(ASTNode node, ASTNode parent, StructuralPropertyDescriptor property) {
		node.setParent(parent, property);
	}

	/**
	 * Clones an AST node to a different AST.
	 * Package-private method: ASTNode.clone(AST)
	 */
	public static ASTNode clone(ASTNode node, AST target) {
		return node.clone(target);
	}

	/**
	 * Sets whether a yield statement is implicit.
	 * Package-private method: YieldStatement.setImplicit(boolean)
	 */
	public static void setImplicit(YieldStatement yieldStmt, boolean implicit) {
		yieldStmt.setImplicit(implicit);
	}

	/**
	 * Sets the restricted identifier start position for a guarded pattern.
	 * Package-private method: GuardedPattern.setRestrictedIdentifierStartPosition(int)
	 */
	public static void setRestrictedIdentifierStartPosition(GuardedPattern pattern, int position) {
		pattern.setRestrictedIdentifierStartPosition(position);
	}

	/**
	 * Sets the escaped value for a string literal.
	 * Package-private method: StringLiteral.internalSetEscapedValue(String)
	 */
	public static void internalSetEscapedValue(StringLiteral literal, String value) {
		literal.internalSetEscapedValue(value);
	}

	/**
	 * Sets the escaped value for a text block.
	 * Package-private method: TextBlock.internalSetEscapedValue(String, String)
	 */
	public static void internalSetEscapedValue(TextBlock textBlock, String rawValue, String value) {
		textBlock.internalSetEscapedValue(rawValue, value);
	}

	/**
	 * Initializes the comment mapper for a compilation unit.
	 * Package-private method: CompilationUnit.initCommentMapper(char[])
	 */
	public static void initCommentMapper(CompilationUnit cu, char[] source) {
		cu.initCommentMapper(source);
	}

	/**
	 * Sets the problems for a compilation unit.
	 * Package-private method: CompilationUnit.setProblems(IProblem[])
	 */
	public static void setProblems(CompilationUnit cu, shaded.org.eclipse.jdt.core.compiler.IProblem[] problems) {
		cu.setProblems(problems);
	}
}
