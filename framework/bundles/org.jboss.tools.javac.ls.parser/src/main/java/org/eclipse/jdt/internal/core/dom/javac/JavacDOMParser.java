/*******************************************************************************
 * Copyright (c) 2025 Red Hat, Inc. and others.
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
 *     Derived from JavacCompilationUnitResolver
 *******************************************************************************/
package org.eclipse.jdt.internal.core.dom.javac;

import java.io.File;
import java.io.IOException;
import java.net.URI;
import java.nio.CharBuffer;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.eclipse.jdt.core.JavaCore;
import org.eclipse.jdt.core.dom.*;
import org.eclipse.jdt.core.dom.JavacDomPackageAccessor;
import org.eclipse.jdt.core.compiler.IProblem;
import org.eclipse.jdt.internal.compiler.classfmt.ClassFileConstants;
import org.eclipse.jdt.internal.compiler.impl.CompilerOptions;
import org.eclipse.jdt.internal.compiler.problem.DefaultProblem;
import org.eclipse.jdt.internal.compiler.problem.ProblemSeverities;
import org.eclipse.jdt.internal.javac.problem.JavacDiagnosticProblemConverter;
import org.eclipse.jdt.internal.javac.problem.JavacProblem;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import shaded.com.sun.tools.javac.api.JavacTool;
import shaded.com.sun.tools.javac.file.JavacFileManager;
import shaded.com.sun.tools.javac.main.Option;
import shaded.com.sun.tools.javac.parser.JavadocTokenizer;
import shaded.com.sun.tools.javac.parser.Scanner;
import shaded.com.sun.tools.javac.parser.ScannerFactory;
import shaded.com.sun.tools.javac.parser.Tokens.Comment.CommentStyle;
import shaded.com.sun.tools.javac.parser.Tokens.TokenKind;
import shaded.com.sun.tools.javac.tree.JCTree.JCCompilationUnit;
import shaded.com.sun.tools.javac.util.Context;
import shaded.com.sun.tools.javac.util.JCDiagnostic;
import shaded.com.sun.tools.javac.util.Log;
import shaded.com.sun.tools.javac.util.Names;
import shaded.com.sun.tools.javac.util.Options;
import shaded.javax.tools.Diagnostic;
import shaded.javax.tools.DiagnosticListener;
import shaded.javax.tools.JavaFileManager;
import shaded.javax.tools.JavaFileObject;
import shaded.javax.tools.SimpleJavaFileObject;
import shaded.javax.tools.StandardLocation;
import shaded.javax.tools.ToolProvider;
import shaded.com.sun.source.util.JavacTask;

/**
 * Simplified parser for converting Java source code to Eclipse DOM AST using javac.
 *
 * This is a streamlined version extracted from JavacCompilationUnitResolver,
 * removing Eclipse workspace dependencies and focusing on core parsing functionality.
 */
public class JavacDOMParser {

	private static final Logger LOG = LoggerFactory.getLogger(JavacDOMParser.class);

	/**
	 * Parse Java source code to DOM AST.
	 *
	 * @param sourceContent the Java source code
	 * @param fileName the file name (e.g., "MyClass.java")
	 * @param classpath list of classpath entries (directories or JARs), or null for system classpath only
	 * @param apiLevel AST API level (e.g., AST.JLS21)
	 * @param compilerOptions compiler options map (source level, compliance, etc.)
	 * @param resolveBindings if true, performs full type resolution; if false, only parses structure
	 * @return CompilationUnit with parsed AST
	 */
	public CompilationUnit parse(
			String sourceContent,
			String fileName,
			List<File> classpath,
			int apiLevel,
			Map<String, String> compilerOptions,
			boolean resolveBindings) {

		if (sourceContent == null) {
			throw new IllegalArgumentException("sourceContent cannot be null");
		}
		if (fileName == null) {
			fileName = "Source.java";
		}
		if (compilerOptions == null) {
			compilerOptions = getDefaultCompilerOptions();
		}

		Context context = new Context();
		try {
			// Set up Names first (for caching)
			Names names = new Names(context) {
				@Override
				public void dispose() {
					// Keep names cached for reuse
				}
			};
			context.put(Names.namesKey, names);

			// Pre-register file manager BEFORE DiagnosticListener
			JavacFileManager.preRegister(context);

			// Now register diagnostic listener - must be before Log is initialized
			final Map<JavaFileObject, CompilationUnit> filesToUnits = new HashMap<>();
			DiagnosticListener<JavaFileObject> diagnosticListener = createDiagnosticListener(filesToUnits, context, compilerOptions);
			context.put(DiagnosticListener.class, diagnosticListener);

			// Configure javac options
			Options javacOptions = Options.instance(context);
			configureJavacOptions(javacOptions, compilerOptions, resolveBindings);

			// Get file manager from context (already registered)
			JavacFileManager fileManager = (JavacFileManager) context.get(JavaFileManager.class);

			// Configure classpath if provided
			if (classpath != null && !classpath.isEmpty()) {
				try {
					fileManager.setLocation(StandardLocation.CLASS_PATH, classpath);
				} catch (IOException ex) {
					LOG.error("Failed to set classpath", ex);
				}
			}

			// Create virtual file object from source
			JavaFileObject fileObject = new VirtualSourceFile(fileName, sourceContent);
			fileManager.cache(fileObject, CharBuffer.wrap(sourceContent));

			// Create AST
			AST ast = createAST(compilerOptions, apiLevel, context);
			CompilationUnit result = ast.newCompilationUnit();

			// Now populate the file-to-unit mapping for the diagnostic listener
			filesToUnits.put(fileObject, result);

			// Create compiler and task using JavacTool with context
			var compiler = ToolProvider.getSystemJavaCompiler();
			JavacTask task = ((JavacTool) compiler).getTask(
				null,           // out
				fileManager,    // file manager
				null,           // diagnostic listener already in context
				List.of(),      // options already set in context
				List.of(),      // classes to compile (none)
				List.of(fileObject),  // source files
				context         // context with options and listener
			);

			// Configure javac to keep comments and positions
			var javac = shaded.com.sun.tools.javac.main.JavaCompiler.instance(context);
			javac.keepComments = javac.genEndPos = javac.lineDebugInfo = true;

			// Parse
			JCCompilationUnit javacUnit = null;
			try {
				// Fully consume the parse iterator to ensure diagnostics are reported
				var parseResults = task.parse();
				for (var element : parseResults) {
					if (javacUnit == null) {
						javacUnit = (JCCompilationUnit) element;
					}
				}

				// After parsing, disable extra features for any further parsing during resolution
				javac.keepComments = javac.genEndPos = javac.lineDebugInfo = false;

				LOG.debug("Parsing complete, diagnostics should now be available");
			} catch (IOException ex) {
				LOG.error("Failed to parse source", ex);
				return result;
			}

			if (javacUnit == null) {
				LOG.warn("No compilation unit produced from parsing");
				return result;
			}

			// Convert javac tree to DOM using JavacConverter
			boolean docEnabled = JavaCore.ENABLED.equals(compilerOptions.get(JavaCore.COMPILER_DOC_COMMENT_SUPPORT));
			JavacConverter converter = new JavacConverter(ast, javacUnit, context, sourceContent, docEnabled, -1);
			converter.populateCompilationUnit(result, javacUnit);

			// Add javadoc diagnostics as problems
			List<IProblem> javadocProblems = new ArrayList<>();
			for (JCDiagnostic d : converter.javadocDiagnostics) {
				// TODO: Convert javadoc diagnostics to problems
				// For now, just log them
				LOG.debug("Javadoc diagnostic: {}", d.getMessage(null));
			}

			// Handle comments
			List<Comment> comments = new ArrayList<>();
			comments.addAll(converter.notAttachedComments);
			Scanner javacScanner = scanForComments(comments, result, context, sourceContent, converter);
			addCommentsToUnit(comments, result);

			// Initialize comment mapper to associate comments with AST nodes
			JavacDomPackageAccessor.initCommentMapper(result, sourceContent.toCharArray());

			// Always analyze to get diagnostics (errors/warnings)
			// Even if we don't resolve bindings, we need analysis for problem reporting
			try {
				// Fully consume the analyze iterator to ensure diagnostics are reported
				var analyzeResults = task.analyze();
				for (var element : analyzeResults) {
					// Just consume the results
				}
				LOG.debug("Analysis complete, diagnostics reported");
				if (resolveBindings) {
					// TODO: Create and attach JavacBindingResolver
					// For now, bindings won't be available
					LOG.debug("Binding resolution requested but not yet implemented");
				}
			} catch (IOException ex) {
				LOG.error("Failed to analyze", ex);
			}

			return result;

		} finally {
			// Cleanup
			try {
				JavaFileManager fm = context.get(JavaFileManager.class);
				if (fm != null) {
					fm.close();
				}
			} catch (IOException ex) {
				LOG.error("Failed to close file manager", ex);
			}
		}
	}

	/**
	 * Get default compiler options for Java 17.
	 */
	private Map<String, String> getDefaultCompilerOptions() {
		Map<String, String> options = new HashMap<>();
		options.put(JavaCore.COMPILER_SOURCE, "17");
		options.put(JavaCore.COMPILER_COMPLIANCE, "17");
		options.put(JavaCore.COMPILER_CODEGEN_TARGET_PLATFORM, "17");
		options.put(JavaCore.COMPILER_DOC_COMMENT_SUPPORT, JavaCore.ENABLED);
		return options;
	}

	/**
	 * Configure javac options from compiler options map.
	 */
	private void configureJavacOptions(Options javacOptions, Map<String, String> compilerOptions, boolean resolveBindings) {
		// Don't fold string constants - keep as authored
		javacOptions.put("allowStringFolding", Boolean.FALSE.toString());

		// Set source and target
		String source = compilerOptions.get(JavaCore.COMPILER_SOURCE);
		if (source != null) {
			source = normalizeVersion(source);
			javacOptions.put(Option.SOURCE, source);
		}

		String target = compilerOptions.get(JavaCore.COMPILER_CODEGEN_TARGET_PLATFORM);
		if (target != null) {
			target = normalizeVersion(target);
			javacOptions.put(Option.TARGET, target);
		}

		// Preview features
		if (JavaCore.ENABLED.equals(compilerOptions.get("org.eclipse.jdt.core.compiler.problem.enablePreviewFeatures"))) {
			javacOptions.put(Option.PREVIEW, Boolean.toString(true));
		}

		// Documentation
		if (JavaCore.ENABLED.equals(compilerOptions.get(JavaCore.COMPILER_DOC_COMMENT_SUPPORT))) {
			if (!resolveBindings) {
				// Minimal doclint for parsing only
				javacOptions.put(Option.XDOCLINT_CUSTOM, "reference");
			} else {
				javacOptions.put(Option.XDOCLINT, Boolean.toString(true));
				javacOptions.put(Option.XDOCLINT_CUSTOM, "all");
			}
		}

		// Linting - minimal for now
		if (!resolveBindings) {
			javacOptions.put(Option.XLINT_CUSTOM, "raw");
		}

		// No annotation processing for now
		javacOptions.put(Option.PROC, "none");
	}

	/**
	 * Normalize version string (1.8 → 8, etc.)
	 */
	private String normalizeVersion(String version) {
		if (CompilerOptions.VERSION_1_8.equals(version)) {
			return "8";
		}
		return version;
	}

	/**
	 * Create AST with correct settings.
	 */
	private AST createAST(Map<String, String> options, int level, Context context) {
		AST ast = AST.newAST(level, JavaCore.ENABLED.equals(options.get("org.eclipse.jdt.core.compiler.problem.enablePreviewFeatures")));
		JavacDomPackageAccessor.setDefaultNodeFlag(ast, ASTNode.ORIGINAL);

		// Note: Scanner configuration (sourceLevel, complianceLevel, previewEnabled)
		// is not available in this version of JDT's AST. The API level passed to newAST()
		// determines the parser behavior.

		return ast;
	}

	/**
	 * Create diagnostic listener that forwards problems to the CompilationUnit.
	 */
	private DiagnosticListener<JavaFileObject> createDiagnosticListener(
			Map<JavaFileObject, CompilationUnit> filesToUnits,
			Context context,
			Map<String, String> compilerOptions) {

		// Create the JavacDiagnosticProblemConverter
		JavacDiagnosticProblemConverter problemConverter = new JavacDiagnosticProblemConverter(compilerOptions, context);

		// Collect problems for each compilation unit
		Map<CompilationUnit, List<org.eclipse.jdt.core.compiler.IProblem>> unitProblems = new HashMap<>();

		return new DiagnosticListener<JavaFileObject>() {
			@Override
			public void report(Diagnostic<? extends JavaFileObject> diagnostic) {
				LOG.debug("Diagnostic received: {} at line {}", diagnostic.getMessage(null), diagnostic.getLineNumber());
				JavaFileObject source = diagnostic.getSource();
				if (source != null) {
					CompilationUnit unit = filesToUnits.get(source);

					// If not found, javac may have wrapped it - match by URI instead
					if (unit == null) {
						URI sourceUri = source.toUri();
						for (var entry : filesToUnits.entrySet()) {
							if (entry.getKey().toUri().equals(sourceUri)) {
								unit = entry.getValue();
								break;
							}
						}
					}

					if (unit != null) {
						// Convert javac Diagnostic to Eclipse IProblem using the sophisticated converter
						JavacProblem[] problems = problemConverter.createJavacProblems(diagnostic);
						if (problems != null && problems.length > 0) {
							for (JavacProblem problem : problems) {
								unitProblems.computeIfAbsent(unit, k -> new ArrayList<>()).add(problem);
							}

							// Set problems on unit after collection
							List<org.eclipse.jdt.core.compiler.IProblem> allProblems = unitProblems.get(unit);
							org.eclipse.jdt.core.compiler.IProblem[] problemArray = new org.eclipse.jdt.core.compiler.IProblem[allProblems.size()];
							for (int i = 0; i < allProblems.size(); i++) {
								problemArray[i] = allProblems.get(i);
							}
							JavacDomPackageAccessor.setProblems(unit, problemArray);
							LOG.debug("Set {} problems on unit", allProblems.size());
						}
					}
				}
			}
		};
	}


	/**
	 * Convert javac Diagnostic to Eclipse IProblem.
	 */
	private IProblem convertDiagnostic(shaded.javax.tools.Diagnostic<? extends JavaFileObject> diagnostic, String fileName) {
		// Map javac diagnostic kind to Eclipse severity
		int severity;
		switch (diagnostic.getKind()) {
			case ERROR:
				severity = ProblemSeverities.Error;
				break;
			case WARNING:
			case MANDATORY_WARNING:
				severity = ProblemSeverities.Warning;
				break;
			default:
				severity = ProblemSeverities.Info;
		}

		// Get position information
		int startPosition = (int) diagnostic.getStartPosition();
		int endPosition = (int) diagnostic.getEndPosition();
		int lineNumber = (int) diagnostic.getLineNumber();
		int columnNumber = (int) diagnostic.getColumnNumber();

		// Get message
		String message = diagnostic.getMessage(null);

		// Create DefaultProblem
		return new DefaultProblem(
			fileName.toCharArray(),
			message,
			0, // problem id - using 0 for generic javac problems
			new String[0], // string arguments
			severity,
			startPosition >= 0 ? startPosition : 0,
			endPosition >= 0 ? endPosition : 0,
			lineNumber >= 0 ? lineNumber : 0,
			columnNumber >= 0 ? columnNumber : 0
		);
	}

	/**
	 * Scan for comments in the source using javac's tokenizer.
	 */
	private Scanner scanForComments(
			List<Comment> comments,
			CompilationUnit unit,
			Context context,
			String rawText,
			JavacConverter converter) {

		ScannerFactory scannerFactory = ScannerFactory.instance(context);
		JavadocTokenizer commentTokenizer = new JavadocTokenizer(scannerFactory, rawText.toCharArray(), rawText.length()) {
			@Override
			protected shaded.com.sun.tools.javac.parser.Tokens.Comment processComment(int pos, int endPos, CommentStyle style) {
				// Workaround Java bug 9077218 - very short javadoc becomes block comment
				if (style == CommentStyle.JAVADOC_BLOCK && endPos - pos <= 4) {
					style = CommentStyle.BLOCK;
				}
				var res = super.processComment(pos, endPos, style);
				if (noCommentAt(unit, pos)) {
					Comment comment = converter.convert(res, pos, endPos);
					comments.add(comment);
				}
				return res;
			}
		};

		// Directly drive the tokenizer to trigger comment callbacks
		shaded.com.sun.tools.javac.parser.Tokens.Token token;
		do {
			token = commentTokenizer.readToken();
		} while (token != null && token.kind != TokenKind.EOF);

		// Return a scanner for compatibility (though not used after this method)
		return scannerFactory.newScanner(rawText, false);
	}

	/**
	 * Check if there's already a comment at the given position.
	 */
	private static boolean noCommentAt(CompilationUnit unit, int pos) {
		if (unit.getCommentList() == null) {
			return true;
		}
		return unit.getCommentList().stream()
				.allMatch(other -> pos < ((Comment)other).getStartPosition() || pos >= ((Comment)other).getStartPosition() + ((Comment)other).getLength());
	}

	/**
	 * Add comments to the CompilationUnit.
	 */
	private static void addCommentsToUnit(List<Comment> comments, CompilationUnit unit) {
		List<Comment> working = unit.getCommentList() == null ? new ArrayList<>() : new ArrayList<>(unit.getCommentList());

		for (Comment c : comments) {
			if (c.getStartPosition() >= 0) {
				if (noCommentAt(working, c.getStartPosition())) {
					working.add(c);
				}
			}
		}

		working.sort(Comparator.comparingInt(Comment::getStartPosition));
		JavacDomPackageAccessor.setCommentTable(unit, working.toArray(Comment[]::new));
	}

	/**
	 * Check if there's already a comment at the given position in a list.
	 */
	private static boolean noCommentAt(List<Comment> comments, int pos) {
		return comments.stream()
				.allMatch(other -> pos < other.getStartPosition() || pos >= other.getStartPosition() + other.getLength());
	}

	/**
	 * Virtual file object for in-memory source code.
	 */
	public static final class VirtualSourceFile extends SimpleJavaFileObject {

		private final CharSequence source;

		public VirtualSourceFile(String fileName, CharSequence source) {
			super(URI.create("mem:///" + fileName), Kind.SOURCE);
			this.source = source;
		}

		@Override
		public CharSequence getCharContent(boolean ignoreEncodingErrors) {
			return source;
		}
	}
}
