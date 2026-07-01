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
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import org.jboss.tools.javac.ls.server.model.WorkspaceModel;

/**
 * Benchmark for indexing MyBatis repository (~1,400 Java files).
 * Smaller than Quarkus, good for testing basic indexing functionality.
 *
 * Run from the test module directory:
 *   cd framework/tests/org.jboss.tools.javac.ls.server.test
 *   mvn exec:exec -Dexec.executable=java \
 *                 -Dexec.classpathScope=test \
 *                 -Dexec.args="-Xmx8g -classpath %classpath org.jboss.tools.javac.ls.server.MyBatisBenchmark /home/rob/apps/claude/benchmarks/mybatis"
 *
 * Or run directly with java:
 *   java -Xmx8g -cp target/test-classes:target/classes:$(mvn dependency:build-classpath -Dmdep.outputFile=/dev/stdout -q 2>/dev/null) \
 *        org.jboss.tools.javac.ls.server.MyBatisBenchmark /home/rob/apps/claude/benchmarks/mybatis
 */
public class MyBatisBenchmark {

	public static void main(String[] args) throws Exception {
		if (args.length == 0) {
			System.err.println("Usage: MyBatisBenchmark <path-to-mybatis-repo>");
			System.exit(1);
		}

		String mybatisPath = args[0];
		File mybatisDir = new File(mybatisPath);
		if (!mybatisDir.exists() || !mybatisDir.isDirectory()) {
			System.err.println("Directory not found: " + mybatisPath);
			System.exit(1);
		}

		System.out.println("=".repeat(80));
		System.out.println("MyBatis Benchmark");
		System.out.println("=".repeat(80));
		System.out.println("Repository: " + mybatisDir.getAbsolutePath());
		System.out.println();

		// Count Java files
		long javaFileCount = countJavaFiles(mybatisDir.toPath());
		System.out.println("Java files found: " + javaFileCount);
		System.out.println();

		// Create persistent workspace for cache reuse
		File cacheDir = new File(System.getProperty("user.home"), ".cache/javac-ls");
		File workspace = new File(cacheDir, "mybatis-benchmark-workspace");
		workspace.mkdirs();
		System.out.println("Workspace: " + workspace.getAbsolutePath());
		System.out.println("  (Persistent workspace - classpath cache will be reused across runs)");
		System.out.println();

		// Memory before
		Runtime runtime = Runtime.getRuntime();
		runtime.gc();
		Thread.sleep(1000);
		long memoryBeforeMB = (runtime.totalMemory() - runtime.freeMemory()) / 1024 / 1024;
		System.out.println("Memory before indexing: " + memoryBeforeMB + " MB");
		System.out.println();

		// Start benchmark
		System.out.println("-".repeat(80));
		System.out.println("Starting indexing...");
		System.out.println("-".repeat(80));

		long startTime = System.currentTimeMillis();

		WorkspaceModel model = new WorkspaceModel(workspace);
		model.addProject("mybatis", mybatisDir.getAbsolutePath());

		// Check classpath cache status
		File classpathCacheDir = new File(workspace, "classpath");
		boolean cacheExists = classpathCacheDir.exists() && classpathCacheDir.listFiles() != null
			&& classpathCacheDir.listFiles().length > 0;
		System.out.println("Classpath cache: " + (cacheExists ? "EXISTS (will reuse if valid)" : "EMPTY (will create)"));
		System.out.println();

		// Index synchronously (unbatched, file-by-file)
		int filesIndexed = model.indexProject("mybatis");

		long endTime = System.currentTimeMillis();
		long durationMs = endTime - startTime;

		// Memory after
		runtime.gc();
		Thread.sleep(1000);
		long memoryAfterMB = (runtime.totalMemory() - runtime.freeMemory()) / 1024 / 1024;

		// Results
		System.out.println();
		System.out.println("=".repeat(80));
		System.out.println("Results");
		System.out.println("=".repeat(80));
		System.out.println("Files indexed: " + filesIndexed + " / " + javaFileCount);
		System.out.println("Duration: " + formatDuration(durationMs));
		if (durationMs > 0) {
			System.out.println("Throughput: " + String.format("%.2f", filesIndexed * 1000.0 / durationMs) + " files/sec");
		}
		System.out.println();
		System.out.println("Memory before: " + memoryBeforeMB + " MB");
		System.out.println("Memory after: " + memoryAfterMB + " MB");
		System.out.println("Memory delta: " + (memoryAfterMB - memoryBeforeMB) + " MB");
		System.out.println();

		// Index stats
		var index = model.getIndexCache().getIndex();
		if (index != null) {
			System.out.println("Index statistics:");
			System.out.println("  Types: " + index.getAllTypes().size());
			System.out.println("  Methods: " + index.getMethodCount());
			System.out.println("  Fields: " + index.getFields().size());
			System.out.println();
		}

		// Error type analysis - DISABLED for performance testing
		// System.out.println("Compilation quality check (scanning for error types):");
		// try {
		// 	List<Path> allJavaFiles = collectJavaFiles(mybatisDir.toPath());
		// 	System.out.println("  Scanning " + allJavaFiles.size() + " files...");

		// 	int filesWithErrors = 0;
		// 	int totalErrors = 0;
		// 	int totalTypeRefs = 0;
		// 	int filesParsed = 0;

		// 	for (Path file : allJavaFiles) {
		// 		try {
		// 			// Scan the already-parsed AST from the cache
		// 			ErrorTypeScanner.ScanResult result =
		// 				ErrorTypeScanner.scanCachedAST(file, model, "mybatis");
		// 			if (result != null) {
		// 				filesParsed++;
		// 				totalErrors += result.errorTypeCount;
		// 				totalTypeRefs += result.totalTypeReferences;
		// 				if (result.hasErrors()) {
		// 					filesWithErrors++;
		// 				}
		// 			}
		// 		} catch (Exception e) {
		// 			// Skip files that fail to scan
		// 		}
		// 	}

		// 	System.out.println("  Files scanned: " + allJavaFiles.size());
		// 	System.out.println("  Files with cached ASTs: " + filesParsed);
		// 	System.out.println("  Files with error types: " + filesWithErrors + " (" +
		// 		String.format("%.1f%%", 100.0 * filesWithErrors / Math.max(1, filesParsed)) + ")");
		// 	System.out.println("  Total error types found: " + totalErrors);
		// 	System.out.println("  Total type references: " + totalTypeRefs);
		// 	if (totalTypeRefs > 0) {
		// 		System.out.println("  Error rate: " +
		// 			String.format("%.2f%%", 100.0 * totalErrors / totalTypeRefs));
		// 	}
		// } catch (Exception e) {
		// 	System.out.println("  Error during quality check: " + e.getMessage());
		// }
		// System.out.println();

		// Cleanup
		model.shutdown();
		System.out.println("Workspace preserved for cache reuse: " + workspace.getAbsolutePath());
		System.out.println();

		System.out.println("=".repeat(80));
		System.out.println("Benchmark complete");
		System.out.println("=".repeat(80));
	}

	private static long countJavaFiles(Path dir) throws Exception {
		try (Stream<Path> walk = Files.walk(dir)) {
			return walk
				.filter(p -> p.toString().endsWith(".java"))
				.filter(p -> !p.toString().contains("/target/"))
				.filter(p -> !p.toString().contains("/build/"))
				.count();
		}
	}

	private static List<Path> collectJavaFiles(Path dir) throws Exception {
		try (Stream<Path> walk = Files.walk(dir)) {
			return walk
				.filter(p -> p.toString().endsWith(".java"))
				.filter(p -> !p.toString().contains("/target/"))
				.filter(p -> !p.toString().contains("/build/"))
				.collect(Collectors.toList());
		}
	}

	private static String formatDuration(long ms) {
		long seconds = ms / 1000;
		long minutes = seconds / 60;
		seconds = seconds % 60;
		long millis = ms % 1000;

		if (minutes > 0) {
			return String.format("%d min %d.%03d sec", minutes, seconds, millis);
		} else {
			return String.format("%d.%03d sec", seconds, millis);
		}
	}
}
