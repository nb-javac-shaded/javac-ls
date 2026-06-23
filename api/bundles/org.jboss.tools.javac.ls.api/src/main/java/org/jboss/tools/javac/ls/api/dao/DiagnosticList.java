/*******************************************************************************
 * Copyright (c) 2026 Red Hat, Inc. Distributed under license by Red Hat, Inc.
 * All rights reserved. This program is made available under the terms of the
 * Eclipse Public License v2.0 which accompanies this distribution, and is
 * available at http://www.eclipse.org/legal/epl-v20.html
 *
 * Contributors: Red Hat, Inc.
 ******************************************************************************/
package org.jboss.tools.javac.ls.api.dao;

import java.util.ArrayList;
import java.util.List;

/**
 * Holds a list of diagnostics for a file or project.
 */
public class DiagnosticList {

	private List<Diagnostic> diagnostics;
	private String projectName;
	private String filePath;

	public DiagnosticList() {
		this.diagnostics = new ArrayList<>();
	}

	public DiagnosticList(String projectName) {
		this.projectName = projectName;
		this.diagnostics = new ArrayList<>();
	}

	public DiagnosticList(String projectName, String filePath) {
		this.projectName = projectName;
		this.filePath = filePath;
		this.diagnostics = new ArrayList<>();
	}

	public List<Diagnostic> getDiagnostics() {
		return diagnostics;
	}

	public void setDiagnostics(List<Diagnostic> diagnostics) {
		this.diagnostics = diagnostics;
	}

	public void addDiagnostic(Diagnostic diagnostic) {
		this.diagnostics.add(diagnostic);
	}

	public String getProjectName() {
		return projectName;
	}

	public void setProjectName(String projectName) {
		this.projectName = projectName;
	}

	public String getFilePath() {
		return filePath;
	}

	public void setFilePath(String filePath) {
		this.filePath = filePath;
	}

	public int getErrorCount() {
		return (int) diagnostics.stream()
			.filter(d -> d.getSeverity() == Diagnostic.ERROR)
			.count();
	}

	public int getWarningCount() {
		return (int) diagnostics.stream()
			.filter(d -> d.getSeverity() == Diagnostic.WARNING)
			.count();
	}

	public int getInfoCount() {
		return (int) diagnostics.stream()
			.filter(d -> d.getSeverity() == Diagnostic.INFO)
			.count();
	}

	@Override
	public String toString() {
		return String.format("DiagnosticList[project=%s, file=%s, errors=%d, warnings=%d, infos=%d, total=%d]",
			projectName, filePath, getErrorCount(), getWarningCount(), getInfoCount(), diagnostics.size());
	}
}
