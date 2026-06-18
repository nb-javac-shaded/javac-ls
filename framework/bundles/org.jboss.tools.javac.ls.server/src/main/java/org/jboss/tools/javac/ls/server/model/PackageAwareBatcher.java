package org.jboss.tools.javac.ls.server.model;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Package-aware batching strategy for parsing Java files.
 *
 * Groups files by package and creates batches that:
 * - Keep packages together when possible (for better cross-file resolution)
 * - Respect size limits (to avoid memory spikes)
 * - Handle both tiny packages (1-2 files) and huge packages (800+ files)
 */
public class PackageAwareBatcher {

	/** Target batch size - aim for this many files per batch */
	private static final int TARGET_BATCH_SIZE = 150;

	/** Maximum batch size - never exceed this */
	private static final int MAX_BATCH_SIZE = 300;

	/** Minimum batch size - don't flush batches smaller than this unless it's the last batch */
	private static final int MIN_BATCH_SIZE = 50;

	/**
	 * Create batches from a list of Java files using package-aware strategy.
	 *
	 * @param files list of Java files to batch
	 * @return list of batches, where each batch is a list of files
	 */
	public List<List<Path>> createBatches(List<Path> files) {
		if (files == null || files.isEmpty()) {
			return List.of();
		}

		// Group files by package
		Map<String, List<Path>> filesByPackage = groupByPackage(files);

		// Create batches using package-aware strategy
		List<List<Path>> batches = new ArrayList<>();
		List<Path> currentBatch = new ArrayList<>();

		for (Map.Entry<String, List<Path>> entry : filesByPackage.entrySet()) {
			List<Path> pkgFiles = entry.getValue();

			if (pkgFiles.size() > MAX_BATCH_SIZE) {
				// Large package: flush current batch, then split package into chunks
				if (!currentBatch.isEmpty()) {
					batches.add(new ArrayList<>(currentBatch));
					currentBatch.clear();
				}

				// Split large package into MAX_BATCH_SIZE chunks
				for (int i = 0; i < pkgFiles.size(); i += MAX_BATCH_SIZE) {
					int end = Math.min(i + MAX_BATCH_SIZE, pkgFiles.size());
					batches.add(new ArrayList<>(pkgFiles.subList(i, end)));
				}

			} else if (currentBatch.size() + pkgFiles.size() > TARGET_BATCH_SIZE) {
				// Adding this package would exceed target: flush current batch if big enough
				if (currentBatch.size() >= MIN_BATCH_SIZE) {
					batches.add(new ArrayList<>(currentBatch));
					currentBatch.clear();
				}
				currentBatch.addAll(pkgFiles);

			} else {
				// Add package to current batch
				currentBatch.addAll(pkgFiles);
			}
		}

		// Don't forget final batch
		if (!currentBatch.isEmpty()) {
			batches.add(currentBatch);
		}

		return batches;
	}

	/**
	 * Group files by package name.
	 *
	 * Package is determined by the directory structure relative to a source root.
	 * For example: src/com/example/Foo.java -> com.example
	 *
	 * @param files list of Java files
	 * @return map of package name to list of files
	 */
	private Map<String, List<Path>> groupByPackage(List<Path> files) {
		Map<String, List<Path>> filesByPackage = new LinkedHashMap<>();

		for (Path file : files) {
			String packageName = extractPackageName(file);
			filesByPackage.computeIfAbsent(packageName, k -> new ArrayList<>()).add(file);
		}

		return filesByPackage;
	}

	/**
	 * Extract package name from file path.
	 *
	 * Looks for common source root patterns (src, src/main/java, src/test/java, etc.)
	 * and extracts the package from the directory structure after that.
	 *
	 * @param file Java file path
	 * @return package name (e.g., "com.example") or "" for default package
	 */
	private String extractPackageName(Path file) {
		Path parent = file.getParent();
		if (parent == null) {
			return "";
		}

		// Convert path to string and look for common patterns
		String pathStr = parent.toString();

		// Look for common source roots
		int srcIndex = findSourceRoot(pathStr);
		if (srcIndex >= 0) {
			// Extract everything after source root
			String afterSrc = pathStr.substring(srcIndex);
			// Convert path separators to dots
			return afterSrc.replace('/', '.').replace('\\', '.');
		}

		// Fallback: use last 2-3 path components as package
		int componentCount = 0;
		StringBuilder pkg = new StringBuilder();
		for (int i = parent.getNameCount() - 1; i >= 0 && componentCount < 3; i--) {
			String component = parent.getName(i).toString();
			if (pkg.length() > 0) {
				pkg.insert(0, '.');
			}
			pkg.insert(0, component);
			componentCount++;
		}

		return pkg.toString();
	}

	/**
	 * Find the index of source root in path string.
	 *
	 * Looks for patterns like:
	 * - /src/
	 * - /src/main/java/
	 * - /src/test/java/
	 * - /source/
	 *
	 * @param pathStr path string
	 * @return index after source root, or -1 if not found
	 */
	private int findSourceRoot(String pathStr) {
		// Try common patterns
		String[] patterns = {
			"/src/main/java/",
			"/src/test/java/",
			"\\src\\main\\java\\",
			"\\src\\test\\java\\",
			"/src/",
			"\\src\\",
			"/source/",
			"\\source\\"
		};

		for (String pattern : patterns) {
			int index = pathStr.indexOf(pattern);
			if (index >= 0) {
				return index + pattern.length();
			}
		}

		return -1;
	}
}
