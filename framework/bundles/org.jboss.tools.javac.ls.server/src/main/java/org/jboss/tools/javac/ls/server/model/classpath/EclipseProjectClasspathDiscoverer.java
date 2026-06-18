/*******************************************************************************
 * Copyright (c) 2026 Red Hat, Inc. Distributed under license by Red Hat, Inc.
 * All rights reserved. This program is made available under the terms of the
 * Eclipse Public License v2.0 which accompanies this distribution, and is
 * available at http://www.eclipse.org/legal/epl-v20.html
 *
 * Contributors: Red Hat, Inc.
 ******************************************************************************/
package org.jboss.tools.javac.ls.server.model.classpath;

import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;

import org.jboss.tools.javac.ls.server.model.WorkspaceProject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;

/**
 * Discovers classpath for Eclipse projects.
 * This is a fallback discoverer with the lowest priority.
 */
public class EclipseProjectClasspathDiscoverer implements IProjectClasspathDiscoverer {

	private static final Logger LOG = LoggerFactory.getLogger(EclipseProjectClasspathDiscoverer.class);

	@Override
	public String getId() {
		return "eclipse";
	}

	@Override
	public boolean accepts(WorkspaceProject proj) {
		if (proj == null || proj.getPath() == null) {
			return false;
		}
		File classpathFile = new File(proj.getPath(), ".classpath");
		return classpathFile.exists();
	}

	@Override
	public List<File> getSourceFilesForCacheValidation(WorkspaceProject proj) {
		if (proj == null || proj.getPath() == null) {
			return null;
		}
		File classpathFile = new File(proj.getPath(), ".classpath");
		// Check .classpath timestamp for cache validation
		return Arrays.asList(classpathFile);
	}

	@Override
	public ArrayList<IJavacClasspathEntry> discoverClasspath(WorkspaceProject proj) {
		ArrayList<IJavacClasspathEntry> entries = new ArrayList<>();

		if (proj == null || proj.getPath() == null) {
			return entries;
		}

		File projectDir = new File(proj.getPath());
		File classpathFile = new File(projectDir, ".classpath");

		if (!classpathFile.exists()) {
			LOG.warn("Eclipse .classpath file not found for project: {}", proj.getName());
			return entries;
		}

		try {
			DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
			DocumentBuilder builder = factory.newDocumentBuilder();
			Document doc = builder.parse(classpathFile);

			NodeList classpathEntries = doc.getElementsByTagName("classpathentry");

			for (int i = 0; i < classpathEntries.getLength(); i++) {
				Element entry = (Element) classpathEntries.item(i);
				String kind = entry.getAttribute("kind");
				String path = entry.getAttribute("path");

				if (path == null || path.isEmpty()) {
					continue;
				}

				if ("src".equals(kind)) {
					// Source folder
					File srcPath = resolvePathRelativeToProject(projectDir, path);
					if (srcPath.exists()) {
						entries.add(new JavacClasspathEntry(IJavacClasspathEntry.EntryType.SOURCE,
								srcPath.getAbsolutePath()));
					}
				} else if ("lib".equals(kind)) {
					// Library JAR
					File libPath = resolvePathRelativeToProject(projectDir, path);
					if (libPath.exists()) {
						entries.add(new JavacClasspathEntry(IJavacClasspathEntry.EntryType.LIBRARY,
								libPath.getAbsolutePath()));
					}
				} else if ("output".equals(kind)) {
					// Output folder (compiled classes)
					File outputPath = resolvePathRelativeToProject(projectDir, path);
					if (outputPath.exists()) {
						entries.add(new JavacClasspathEntry(IJavacClasspathEntry.EntryType.SOURCE,
								outputPath.getAbsolutePath()));
					}
				}
				// Skip "con" (containers like JRE) and "var" (workspace variables) for now
			}

			LOG.info("Discovered {} classpath entries from Eclipse .classpath for project: {}",
					entries.size(), proj.getName());

		} catch (Exception e) {
			LOG.error("Failed to parse Eclipse .classpath file for project: {}", proj.getName(), e);
		}

		return entries;
	}

	/**
	 * Resolve a path from .classpath relative to the project directory.
	 * Handles both relative paths and absolute paths.
	 */
	private File resolvePathRelativeToProject(File projectDir, String path) {
		File resolved = new File(path);
		if (resolved.isAbsolute()) {
			return resolved;
		}
		return new File(projectDir, path);
	}
}
