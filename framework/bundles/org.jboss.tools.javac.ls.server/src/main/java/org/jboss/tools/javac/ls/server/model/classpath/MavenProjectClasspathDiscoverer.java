/*******************************************************************************
 * Copyright (c) 2026 Red Hat, Inc. Distributed under license by Red Hat, Inc.
 * All rights reserved. This program is made available under the terms of the
 * Eclipse Public License v2.0 which accompanies this distribution, and is
 * available at http://www.eclipse.org/legal/epl-v20.html
 *
 * Contributors: Red Hat, Inc.
 ******************************************************************************/
package org.jboss.tools.javac.ls.server.model.classpath;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import org.jboss.tools.javac.ls.server.model.WorkspaceProject;
import org.jboss.tools.javac.ls.server.model.classpath.IJavacClasspathEntry.EntryType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Discovers classpath for Maven projects.
 */
public class MavenProjectClasspathDiscoverer implements IProjectClasspathDiscoverer {
	private static final Logger LOG = LoggerFactory.getLogger(MavenProjectClasspathDiscoverer.class);

	@Override
	public String getId() {
		return "maven";
	}

	@Override
	public boolean accepts(WorkspaceProject proj) {
		if (proj == null || proj.getPath() == null) {
			return false;
		}
		File pomFile = new File(proj.getPath(), "pom.xml");
		return pomFile.exists();
	}

	@Override
	public List<File> getSourceFilesForCacheValidation(WorkspaceProject proj) {
		if (proj == null || proj.getPath() == null) {
			return null;
		}
		File pomFile = new File(proj.getPath(), "pom.xml");
		// Check pom.xml timestamp for cache validation
		return Arrays.asList(pomFile);
	}

	@Override
	public ArrayList<IJavacClasspathEntry> discoverClasspath(WorkspaceProject proj) {
		ArrayList<IJavacClasspathEntry> entries = new ArrayList<>();

		// Add standard Maven source folders
		addSourceFolders(proj, entries);

		// Add Maven dependencies via mvn command
		addMavenDependencies(proj, entries);

		return entries;
	}

	private void addSourceFolders(WorkspaceProject proj, ArrayList<IJavacClasspathEntry> entries) {
		File projectDir = new File(proj.getPath());

		// Standard Maven source folders
		String[] sourceFolders = {
			"src/main/java",
			"src/test/java",
			"target/classes",
			"target/test-classes"
		};

		for (String srcFolder : sourceFolders) {
			File srcDir = new File(projectDir, srcFolder);
			if (srcDir.exists() && srcDir.isDirectory()) {
				entries.add(new JavacClasspathEntry(EntryType.SOURCE, srcDir.getAbsolutePath()));
				LOG.debug("Added source folder: {}", srcDir.getAbsolutePath());
			}
		}
	}

	private void addMavenDependencies(WorkspaceProject proj, ArrayList<IJavacClasspathEntry> entries) {
		File projectDir = new File(proj.getPath());

		// Try mvnw first, fall back to mvn
		String mavenCommand = getMavenCommand(projectDir);
		if (mavenCommand == null) {
			LOG.warn("Maven command not found for project: {}", proj.getName());
			return;
		}

		try {
			List<String> command = new ArrayList<>();
			if (mavenCommand.endsWith("mvnw") || mavenCommand.endsWith("mvnw.cmd")) {
				command.add(mavenCommand);
			} else {
				command.add(mavenCommand);
			}
			command.add("dependency:build-classpath");
			command.add("-DincludeScope=compile");
			command.add("-Dmdep.outputFile=/dev/stdout");
			command.add("-q"); // Quiet mode to reduce output

			ProcessBuilder pb = new ProcessBuilder(command);
			pb.directory(projectDir);
			pb.redirectErrorStream(true);

			LOG.debug("Executing Maven command: {}", String.join(" ", command));
			Process process = pb.start();

			// Read classpath from output
			StringBuilder classpathOutput = new StringBuilder();
			try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
				String line;
				while ((line = reader.readLine()) != null) {
					// Maven outputs the classpath as a single line with path separator
					if (line.contains(".jar") || line.contains(File.separator)) {
						classpathOutput.append(line);
					}
				}
			}

			int exitCode = process.waitFor();
			if (exitCode == 0) {
				parseClasspathOutput(classpathOutput.toString(), entries);
			} else {
				LOG.warn("Maven command exited with code {} for project: {}", exitCode, proj.getName());
			}

		} catch (IOException | InterruptedException e) {
			LOG.error("Error executing Maven command for project: {}", proj.getName(), e);
		}
	}

	private void parseClasspathOutput(String classpathOutput, ArrayList<IJavacClasspathEntry> entries) {
		if (classpathOutput == null || classpathOutput.trim().isEmpty()) {
			return;
		}

		// Maven outputs classpath with system-specific path separator
		String[] paths = classpathOutput.split(File.pathSeparator);
		for (String path : paths) {
			path = path.trim();
			if (!path.isEmpty() && new File(path).exists()) {
				entries.add(new JavacClasspathEntry(EntryType.LIBRARY, path));
				LOG.debug("Added library: {}", path);
			}
		}
	}

	private String getMavenCommand(File projectDir) {
		// Check for Maven Wrapper first (preferred)
		File mvnw = new File(projectDir, "mvnw");
		if (mvnw.exists() && mvnw.canExecute()) {
			return mvnw.getAbsolutePath();
		}

		File mvnwCmd = new File(projectDir, "mvnw.cmd");
		if (mvnwCmd.exists()) {
			return mvnwCmd.getAbsolutePath();
		}

		// Fall back to system mvn
		return "mvn";
	}
}
