package org.jboss.tools.javac.ls.server;

import static org.junit.Assert.*;

import java.io.File;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.jboss.tools.javac.ls.parser.bindings.JavacDOMParser;
import org.junit.Test;

import shaded.org.eclipse.jdt.core.compiler.IProblem;
import shaded.org.eclipse.jdt.core.dom.AST;
import shaded.org.eclipse.jdt.core.dom.CompilationUnit;

/**
 * Test what diagnostics/problems we get from parsing without binding resolution.
 * This helps determine if we need expensive binding resolution during initial indexing
 * just to collect diagnostics.
 */
public class DiagnosticsWithoutBindingsTest {

	@Test
	public void testSyntaxErrors_WithoutBindings() throws Exception {
		String code = """
			package test;

			public class SyntaxErrors {
				public void missingBrace() {
					int x = 5
				// Missing semicolon and closing brace

				public void extraBrace() {
					int y = 10;
				}}
			}
			""";

		CompilationUnit cu = parseWithoutBindings(code, "SyntaxErrors.java");
		IProblem[] problems = cu.getProblems();

		System.out.println("\n=== Syntax Errors (without bindings) ===");
		printProblems(problems);

		assertTrue("Should detect syntax errors without bindings", problems.length > 0);
	}

	@Test
	public void testSemanticErrors_WithoutBindings() throws Exception {
		String code = """
			package test;

			public class SemanticErrors {
				public String undefinedVariable() {
					return undefinedVar; // Undefined variable
				}

				public void undefinedClass() {
					UndefinedClass obj = new UndefinedClass(); // Missing import/class
				}

				public int wrongReturnType() {
					return "string"; // Type mismatch
				}

				public void wrongMethodCall() {
					this.nonExistentMethod(); // Method doesn't exist
				}
			}
			""";

		CompilationUnit cu = parseWithoutBindings(code, "SemanticErrors.java");
		IProblem[] problems = cu.getProblems();

		System.out.println("\n=== Semantic Errors (without bindings) ===");
		printProblems(problems);

		// Semantic errors likely require binding resolution
		System.out.println("Total semantic errors detected: " + problems.length);
	}

	@Test
	public void testMissingImport_WithoutBindings() throws Exception {
		String code = """
			package test;

			public class MissingImport {
				public void useList() {
					List<String> items = new ArrayList<>(); // Missing imports
				}
			}
			""";

		CompilationUnit cu = parseWithoutBindings(code, "MissingImport.java");
		IProblem[] problems = cu.getProblems();

		System.out.println("\n=== Missing Import (without bindings) ===");
		printProblems(problems);

		System.out.println("Total problems: " + problems.length);
	}

	@Test
	public void testValidCode_WithoutBindings() throws Exception {
		String code = """
			package test;

			public class ValidCode {
				private int value;

				public int getValue() {
					return value;
				}

				public void setValue(int value) {
					this.value = value;
				}
			}
			""";

		CompilationUnit cu = parseWithoutBindings(code, "ValidCode.java");
		IProblem[] problems = cu.getProblems();

		System.out.println("\n=== Valid Code (without bindings) ===");
		printProblems(problems);

		assertEquals("Valid code should have no problems", 0, problems.length);
	}

	@Test
	public void testCompareWithAndWithoutBindings() throws Exception {
		String code = """
			package test;

			public class CompareBindings {
				// Syntax error
				public void syntaxError() {
					int x = 5
				// missing semicolon

				// Semantic error
				public void semanticError() {
					return undefinedVariable;
				}
			}
			""";

		System.out.println("\n=== Comparing With vs Without Bindings ===");

		CompilationUnit cuWithoutBindings = parseWithoutBindings(code, "CompareBindings.java");
		IProblem[] problemsWithout = cuWithoutBindings.getProblems();

		CompilationUnit cuWithBindings = parseWithBindings(code, "CompareBindings.java");
		IProblem[] problemsWith = cuWithBindings.getProblems();

		System.out.println("\nWithout bindings: " + problemsWithout.length + " problems");
		printProblems(problemsWithout);

		System.out.println("\nWith bindings: " + problemsWith.length + " problems");
		printProblems(problemsWith);
	}

	private CompilationUnit parseWithoutBindings(String source, String filename) throws Exception {
		JavacDOMParser parser = new JavacDOMParser();
		return parser.parse(
			source,
			filename,
			new ArrayList<File>(), // empty classpath
			AST.JLS21,
			null, // no compiler options
			false // NO binding resolution
		);
	}

	private CompilationUnit parseWithBindings(String source, String filename) throws Exception {
		JavacDOMParser parser = new JavacDOMParser();
		return parser.parse(
			source,
			filename,
			new ArrayList<File>(), // empty classpath
			AST.JLS21,
			null, // no compiler options
			true // WITH binding resolution
		);
	}

	private void printProblems(IProblem[] problems) {
		if (problems == null || problems.length == 0) {
			System.out.println("  No problems found");
			return;
		}

		for (int i = 0; i < problems.length; i++) {
			IProblem p = problems[i];
			System.out.printf("  [%d] Line %d: %s%n",
				i + 1,
				p.getSourceLineNumber(),
				p.getMessage());
		}
	}
}
