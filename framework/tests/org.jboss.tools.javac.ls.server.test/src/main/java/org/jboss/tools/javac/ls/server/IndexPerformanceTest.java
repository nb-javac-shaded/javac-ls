package org.jboss.tools.javac.ls.server;

import static org.junit.Assert.*;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import org.jboss.tools.javac.ls.server.model.WorkspaceModel;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

/**
 * Performance tests for indexing large projects.
 *
 * Generates synthetic Java projects with configurable size and measures
 * indexing performance (time, memory, index size).
 */
public class IndexPerformanceTest {

	private File tempProjectDir;
	private List<File> generatedFiles;

	@Before
	public void setUp() throws Exception {
		tempProjectDir = Files.createTempDirectory("perf-test").toFile();
		tempProjectDir.deleteOnExit();
		generatedFiles = new ArrayList<>();
	}

	@After
	public void tearDown() throws Exception {
		if (tempProjectDir != null) {
			deleteRecursively(tempProjectDir);
		}
	}

	/**
	 * Test indexing performance with 1,000 files (~150k lines).
	 * This is a medium-large project size.
	 */
	@Test
	public void testIndex1000Files() throws Exception {
		runPerformanceTest(1000, 150, "1000 files");
	}

	/**
	 * Test indexing performance with 100 files (~15k lines).
	 * Quick smoke test for basic performance.
	 */
	@Test
	public void testIndex100Files() throws Exception {
		runPerformanceTest(100, 150, "100 files");
	}

	/**
	 * Run a performance test with specified parameters.
	 *
	 * @param numFiles number of Java files to generate
	 * @param linesPerFile approximate lines per file
	 * @param testName name for logging
	 */
	private void runPerformanceTest(int numFiles, int linesPerFile, String testName) throws Exception {
		System.out.println("\n=== Performance Test: " + testName + " ===");

		// Generate synthetic project
		long genStart = System.currentTimeMillis();
		generateProject(numFiles, linesPerFile);
		long genTime = System.currentTimeMillis() - genStart;
		System.out.printf("Generated %d files in %,d ms%n", numFiles, genTime);

		// Measure baseline memory
		System.gc();
		Thread.sleep(100);
		Runtime runtime = Runtime.getRuntime();
		long memBefore = runtime.totalMemory() - runtime.freeMemory();

		// Create workspace and add the generated project
		long indexStart = System.currentTimeMillis();
		WorkspaceModel workspace = new WorkspaceModel(tempProjectDir);

		// Add a project that points to the generated code
		workspace.addProject("perftest", tempProjectDir.getAbsolutePath());

		// Index the project
		workspace.startIndexing(true); // synchronous
		long indexTime = System.currentTimeMillis() - indexStart;

		// Measure memory after indexing
		long memAfter = runtime.totalMemory() - runtime.freeMemory();
		long memUsed = memAfter - memBefore;

		// Get index stats
		var index = workspace.getIndexCache().getIndex();
		int typeCount = index.getTypeCount();
		int methodCount = index.getMethodCount();
		int fieldCount = index.getFieldCount();
		int fileCount = index.getIndexedFileCount();

		// Report results
		System.out.printf("Indexing completed in %,d ms%n", indexTime);
		System.out.printf("  Average: %.2f ms/file%n", (double) indexTime / numFiles);
		System.out.printf("  Memory used: %,d bytes (%.2f MB)%n", memUsed, memUsed / (1024.0 * 1024.0));
		System.out.printf("  Files indexed: %d%n", fileCount);
		System.out.printf("  Types indexed: %d%n", typeCount);
		System.out.printf("  Methods indexed: %d%n", methodCount);
		System.out.printf("  Fields indexed: %d%n", fieldCount);
		System.out.println("=====================================\n");

		// Verify we actually indexed something
		assertTrue("Should have indexed types", typeCount > 0);
		assertTrue("Should have indexed methods", methodCount > 0);
		assertTrue("Should have indexed expected number of files", fileCount >= numFiles);

		// Basic performance assertion: should not take more than 100ms per file on average
		// This is very generous - on modern hardware should be much faster
		double msPerFile = (double) indexTime / numFiles;
		assertTrue(String.format("Indexing too slow: %.2f ms/file (max 100)", msPerFile),
			msPerFile < 100);
	}

	/**
	 * Generate a synthetic Java project with numbered classes.
	 *
	 * Creates classes in packages org.test.pak0, org.test.pak1, etc.
	 * with simple numbered class names Class0, Class1, etc.
	 *
	 * @param numFiles total number of Java files to generate
	 * @param linesPerFile approximate lines per file
	 */
	private void generateProject(int numFiles, int linesPerFile) throws IOException {
		int classesPerPackage = 20;
		int numPackages = (numFiles + classesPerPackage - 1) / classesPerPackage;

		int classNum = 0;
		for (int pakNum = 0; pakNum < numPackages && classNum < numFiles; pakNum++) {
			String packageName = "org.test.pak" + pakNum;
			File packageDir = new File(tempProjectDir, packageName.replace('.', '/'));
			packageDir.mkdirs();

			int classesInThisPkg = Math.min(classesPerPackage, numFiles - classNum);
			for (int i = 0; i < classesInThisPkg; i++) {
				String className = "Class" + classNum;
				File javaFile = new File(packageDir, className + ".java");

				String content = generateClassContent(packageName, className, classNum, linesPerFile);
				Files.writeString(javaFile.toPath(), content);

				generatedFiles.add(javaFile);
				classNum++;
			}
		}
	}

	/**
	 * Generate content for a single Java class.
	 *
	 * Creates a realistic class with fields, methods, and some cross-references.
	 */
	private String generateClassContent(String packageName, String className, int classNum, int targetLines) {
		StringBuilder sb = new StringBuilder();

		// Package and imports (~5 lines)
		sb.append("package ").append(packageName).append(";\n\n");
		sb.append("import java.util.List;\n");
		sb.append("import java.util.ArrayList;\n");
		sb.append("import java.util.Map;\n\n");

		// Class declaration
		String superClass = (classNum > 0) ? "Class" + (classNum - 1) : "Object";
		sb.append("public class ").append(className);
		if (classNum > 0 && classNum % 10 == 0) {
			// Every 10th class extends previous class
			sb.append(" extends ").append(superClass);
		}
		sb.append(" {\n\n");

		int currentLines = 8; // Package + imports + class decl

		// Generate fields (aim for ~20 lines)
		int numFields = 8;
		for (int i = 0; i < numFields && currentLines < targetLines; i++) {
			sb.append("\tprivate String field").append(i).append(";\n");
			currentLines++;
		}
		sb.append("\tprivate int id;\n");
		sb.append("\tprivate List<String> items = new ArrayList<>();\n\n");
		currentLines += 3;

		// Generate methods to reach target line count
		int methodNum = 0;
		while (currentLines < targetLines - 5) { // Leave room for closing brace
			int methodLines = generateMethod(sb, methodNum, classNum);
			currentLines += methodLines;
			methodNum++;
		}

		sb.append("}\n");

		return sb.toString();
	}

	/**
	 * Generate a single method.
	 *
	 * @return number of lines generated
	 */
	private int generateMethod(StringBuilder sb, int methodNum, int classNum) {
		int lines = 0;

		if (methodNum == 0) {
			// Constructor
			sb.append("\tpublic Class").append(classNum).append("() {\n");
			sb.append("\t\tthis.id = ").append(classNum).append(";\n");
			sb.append("\t}\n\n");
			lines = 4;
		} else if (methodNum == 1) {
			// getId
			sb.append("\tpublic int getId() {\n");
			sb.append("\t\treturn id;\n");
			sb.append("\t}\n\n");
			lines = 4;
		} else if (methodNum == 2) {
			// setId
			sb.append("\tpublic void setId(int id) {\n");
			sb.append("\t\tthis.id = id;\n");
			sb.append("\t}\n\n");
			lines = 4;
		} else {
			// Generic business method
			sb.append("\tpublic String method").append(methodNum).append("(String param) {\n");
			sb.append("\t\tif (param == null) {\n");
			sb.append("\t\t\treturn \"null\";\n");
			sb.append("\t\t}\n");
			sb.append("\t\tString result = param + id;\n");
			sb.append("\t\titems.add(result);\n");
			sb.append("\t\treturn result;\n");
			sb.append("\t}\n\n");
			lines = 9;
		}

		return lines;
	}

	private void deleteRecursively(File file) {
		if (file.isDirectory()) {
			File[] children = file.listFiles();
			if (children != null) {
				for (File child : children) {
					deleteRecursively(child);
				}
			}
		}
		file.delete();
	}
}
