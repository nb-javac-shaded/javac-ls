/*******************************************************************************
 * Copyright (c) 2026 Red Hat, Inc. Distributed under license by Red Hat, Inc.
 * All rights reserved. This program is made available under the terms of the
 * Eclipse Public License v2.0 which accompanies this distribution, and is
 * available at http://www.eclipse.org/legal/epl-v20.html
 *
 * Contributors: Red Hat, Inc.
 ******************************************************************************/
package org.jboss.tools.javac.ls.server;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import org.jboss.tools.javac.ls.server.model.WorkspaceModel;

import shaded.com.sun.source.tree.CompilationUnitTree;
import shaded.com.sun.source.tree.Tree;
import shaded.com.sun.source.util.TreeScanner;
import shaded.com.sun.tools.javac.api.JavacTool;
import shaded.com.sun.tools.javac.file.JavacFileManager;
import shaded.com.sun.tools.javac.tree.JCTree;
import shaded.com.sun.tools.javac.util.Context;
import shaded.javax.tools.JavaFileObject;
import shaded.javax.tools.SimpleJavaFileObject;
import shaded.javax.tools.StandardLocation;
import shaded.org.eclipse.jdt.core.dom.ASTNode;
import shaded.org.eclipse.jdt.core.dom.ASTVisitor;
import shaded.org.eclipse.jdt.core.dom.CompilationUnit;
import shaded.org.eclipse.jdt.core.dom.IBinding;
import shaded.org.eclipse.jdt.core.dom.IMethodBinding;
import shaded.org.eclipse.jdt.core.dom.ITypeBinding;
import shaded.org.eclipse.jdt.core.dom.IVariableBinding;
import shaded.org.eclipse.jdt.core.dom.MethodInvocation;
import shaded.org.eclipse.jdt.core.dom.QualifiedName;
import shaded.org.eclipse.jdt.core.dom.SimpleName;
import shaded.org.eclipse.jdt.core.dom.SimpleType;
import shaded.org.eclipse.jdt.core.dom.Type;

/**
 * Utility for scanning javac AST to find unresolved types (ErrorType).
 * This helps measure compilation quality by detecting type resolution failures.
 */
public class ErrorTypeScanner {

	/**
	 * Result of scanning a compilation unit for errors.
	 */
	public static class ScanResult {
		public final int errorTypeCount;
		public final int totalTypeReferences;

		public ScanResult(int errorTypeCount, int totalTypeReferences) {
			this.errorTypeCount = errorTypeCount;
			this.totalTypeReferences = totalTypeReferences;
		}

		public boolean hasErrors() {
			return errorTypeCount > 0;
		}

		public double getErrorRate() {
			return totalTypeReferences == 0 ? 0.0 :
				(double) errorTypeCount / totalTypeReferences;
		}
	}

	/**
	 * Scan a parsed compilation unit for ErrorType instances.
	 */
	public static ScanResult scanForErrors(CompilationUnitTree compilationUnit) {
		ErrorCountingVisitor visitor = new ErrorCountingVisitor();
		compilationUnit.accept(visitor, null);
		return new ScanResult(visitor.errorTypeCount.get(), visitor.totalTypeRefs.get());
	}

	/**
	 * Scan an already-cached AST from the workspace for unresolved types.
	 * This checks the actual ASTs that were created during indexing.
	 */
	public static ScanResult scanCachedAST(Path file, WorkspaceModel workspace, String projectName) {
		try {
			// Get the classpath that was used during indexing
			List<File> classpath = workspace.getProjectClasspathNonBlocking(projectName, false)
				.stream()
				.map(entry -> new File(entry.getPath()))
				.collect(java.util.stream.Collectors.toList());

			// Parse the file on-demand with bindings
			CompilationUnit cu = workspace.parseFile(
				file.toUri(),
				classpath,
				null // compiler options
			);

			if (cu == null) {
				return null; // Failed to parse
			}

			// Scan the Eclipse JDT DOM AST for unresolved bindings
			BindingErrorVisitor visitor = new BindingErrorVisitor();
			cu.accept(visitor);
			return new ScanResult(visitor.errorCount.get(), visitor.totalTypeRefs.get());
		} catch (Exception e) {
			return null;
		}
	}

	/**
	 * Parse and scan a single file for error types.
	 */
	public static ScanResult scanFile(Path file, List<File> classpath) throws Exception {
		String content = Files.readString(file);

		// Create javac context
		Context context = new Context();
		JavacFileManager fileManager = new JavacFileManager(context, true, null);

		// Set classpath
		if (classpath != null && !classpath.isEmpty()) {
			fileManager.setLocation(StandardLocation.CLASS_PATH, classpath);
		}

		// Create file object
		JavaFileObject sourceFile = new SimpleJavaFileObject(
			file.toUri(),
			JavaFileObject.Kind.SOURCE
		) {
			@Override
			public CharSequence getCharContent(boolean ignoreEncodingErrors) {
				return content;
			}
		};

		// Parse with javac
		JavacTool tool = JavacTool.create();
		var task = tool.getTask(
			null,
			fileManager,
			null,
			List.of("-proc:none"),
			null,
			List.of(sourceFile),
			context
		);

		// Parse and analyze
		Iterable<? extends CompilationUnitTree> units = task.parse();
		task.analyze(); // Perform attribution to resolve types

		CompilationUnitTree cu = units.iterator().next();
		return scanForErrors(cu);
	}

	/**
	 * TreeScanner that counts ErrorType instances in the javac AST.
	 */
	private static class ErrorCountingVisitor extends TreeScanner<Void, Void> {
		final AtomicInteger errorTypeCount = new AtomicInteger(0);
		final AtomicInteger totalTypeRefs = new AtomicInteger(0);

		@Override
		public Void scan(Tree tree, Void p) {
			if (tree instanceof JCTree) {
				JCTree jcTree = (JCTree) tree;

				// Check if this tree node has a type
				if (jcTree.type != null) {
					totalTypeRefs.incrementAndGet();

					// Check for ErrorType
					String typeString = jcTree.type.toString();
					if (jcTree.type.getTag() == shaded.com.sun.tools.javac.code.TypeTag.ERROR ||
						typeString.equals("<error>") ||
						typeString.equals("<any>") ||
						typeString.equals("<none>")) {
						errorTypeCount.incrementAndGet();
					}
				}
			}

			return super.scan(tree, p);
		}
	}

	/**
	 * ASTVisitor that counts unresolved bindings in the Eclipse JDT DOM AST.
	 * This is used to check the quality of already-indexed ASTs.
	 */
	private static class BindingErrorVisitor extends ASTVisitor {
		final AtomicInteger errorCount = new AtomicInteger(0);
		final AtomicInteger totalTypeRefs = new AtomicInteger(0);

		@Override
		public boolean visit(SimpleName node) {
			IBinding binding = node.resolveBinding();
			totalTypeRefs.incrementAndGet();

			// Check for unresolved or recovered bindings
			if (binding == null || binding.isRecovered()) {
				errorCount.incrementAndGet();
			}

			return true;
		}

		@Override
		public boolean visit(SimpleType node) {
			ITypeBinding binding = node.resolveBinding();
			totalTypeRefs.incrementAndGet();

			// Check for unresolved or recovered type bindings
			if (binding == null || binding.isRecovered()) {
				errorCount.incrementAndGet();
			}

			return true;
		}
	}
}
