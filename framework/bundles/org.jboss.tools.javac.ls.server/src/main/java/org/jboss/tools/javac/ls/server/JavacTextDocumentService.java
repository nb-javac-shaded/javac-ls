/*******************************************************************************
 * Copyright (c) 2026 Red Hat, Inc. Distributed under license by Red Hat, Inc.
 * All rights reserved. This program is made available under the terms of the
 * Eclipse Public License v2.0 which accompanies this distribution, and is
 * available at http://www.eclipse.org/legal/epl-v20.html
 *
 * Contributors: Red Hat, Inc.
 ******************************************************************************/
package org.jboss.tools.javac.ls.server;

import java.net.URI;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;

import org.eclipse.lsp4j.Diagnostic;
import org.eclipse.lsp4j.DiagnosticSeverity;
import org.eclipse.lsp4j.DidChangeTextDocumentParams;
import org.eclipse.lsp4j.DidCloseTextDocumentParams;
import org.eclipse.lsp4j.DidOpenTextDocumentParams;
import org.eclipse.lsp4j.DidSaveTextDocumentParams;
import org.eclipse.lsp4j.DocumentDiagnosticParams;
import org.eclipse.lsp4j.DocumentDiagnosticReport;
import org.eclipse.lsp4j.Position;
import org.eclipse.lsp4j.Range;
import org.eclipse.lsp4j.RelatedFullDocumentDiagnosticReport;
import org.eclipse.lsp4j.services.TextDocumentService;
import org.jboss.tools.javac.ls.api.dao.DiagnosticList;
import org.jboss.tools.javac.ls.server.event.EventManager;
import org.jboss.tools.javac.ls.server.model.WorkspaceModel;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class JavacTextDocumentService implements TextDocumentService {
	private static final Logger LOG = LoggerFactory.getLogger(JavacTextDocumentService.class);

	private final JavacLSServerImpl server;

	public JavacTextDocumentService(JavacLSServerImpl server) {
		this.server = server;
	}

	@Override
	public void didOpen(DidOpenTextDocumentParams params) {
		LOG.debug("didOpen: {}", params.getTextDocument().getUri());
		// NOTE: We do not track opened documents or maintain in-memory document state.
		// This server reads all content directly from the filesystem and does not use
		// the document content sent by the client. No action needed.
	}

	@Override
	public void didChange(DidChangeTextDocumentParams params) {
		LOG.debug("didChange: {}", params.getTextDocument().getUri());
		// NOTE: We do not track document changes or maintain in-memory document state.
		// This server reads all content directly from the filesystem and does not use
		// the incremental changes sent by the client. No action needed.
	}

	@Override
	public void didClose(DidCloseTextDocumentParams params) {
		LOG.debug("didClose: {}", params.getTextDocument().getUri());
		// NOTE: We do not track opened/closed documents or maintain in-memory document state.
		// This server reads all content directly from the filesystem. No action needed.
	}

	@Override
	public void didSave(DidSaveTextDocumentParams params) {
		String uri = params.getTextDocument().getUri();
		LOG.debug("didSave: {}", uri);

		try {
			// Convert URI to file path
			String filePath = uriToFilePath(uri);

			// Get workspace model
			WorkspaceModel workspace = server.getLauncher().getWorkspaceModel();
			if (workspace == null) {
				LOG.warn("Workspace not initialized, cannot reparse saved file: {}", filePath);
				return;
			}

			// Find which project this file belongs to
			String projectName = findProjectForFile(workspace, filePath);
			if (projectName == null) {
				LOG.debug("File is not part of any workspace project: {}", filePath);
				return;
			}

			LOG.info("File saved, re-parsing: {} in project: {}", filePath, projectName);

			// Re-parse the saved file
			// This will:
			// 1. Re-index the file with new content from disk
			// 2. Extract updated diagnostics
			// 3. Fire fileDiagnosticsChanged() which broadcasts to all clients
			java.nio.file.Path path = java.nio.file.Paths.get(filePath);
			workspace.reparseFiles(projectName, java.util.Collections.singletonList(path));

		} catch (Exception e) {
			LOG.error("Error handling didSave for {}: {}", uri, e.getMessage(), e);
		}
	}

	/**
	 * LSP 3.17: Pull diagnostics for a specific document.
	 * Client requests diagnostics on-demand rather than server pushing them.
	 */
	@Override
	public CompletableFuture<DocumentDiagnosticReport> diagnostic(DocumentDiagnosticParams params) {
		String uri = params.getTextDocument().getUri();
		LOG.debug("textDocument/diagnostic request for: {}", uri);

		try {
			// Convert URI to file path
			String filePath = uriToFilePath(uri);

			// Get diagnostics using existing implementation
			WorkspaceModel workspace = server.getLauncher().getWorkspaceModel();
			if (workspace == null) {
				LOG.warn("Workspace not initialized");
				return CompletableFuture.completedFuture(createEmptyDiagnosticReport());
			}

			DiagnosticList diagnosticList = workspace.getFileDiagnostics(filePath);

			// Convert to LSP diagnostics
			List<Diagnostic> lspDiagnostics = EventManager.convertToLspDiagnostics(diagnosticList);

			// Create report
			RelatedFullDocumentDiagnosticReport report = new RelatedFullDocumentDiagnosticReport();
			report.setItems(lspDiagnostics);

			DocumentDiagnosticReport result = new DocumentDiagnosticReport(report);
			return CompletableFuture.completedFuture(result);

		} catch (Exception e) {
			LOG.error("Error getting diagnostics for {}: {}", uri, e.getMessage(), e);
			return CompletableFuture.completedFuture(createEmptyDiagnosticReport());
		}
	}

	/**
	 * Convert LSP URI to file system path.
	 */
	private String uriToFilePath(String uriString) {
		try {
			URI uri = URI.create(uriString);
			return Paths.get(uri).toString();
		} catch (Exception e) {
			LOG.error("Failed to convert URI to path: {}", uriString, e);
			return uriString; // Fallback
		}
	}

	private DocumentDiagnosticReport createEmptyDiagnosticReport() {
		RelatedFullDocumentDiagnosticReport report = new RelatedFullDocumentDiagnosticReport();
		report.setItems(new ArrayList<>());
		return new DocumentDiagnosticReport(report);
	}

	/**
	 * Find which project contains the given file path.
	 */
	private String findProjectForFile(WorkspaceModel workspace, String filePath) {
		for (String projectName : workspace.getProjectNames()) {
			String projectPath = workspace.getProjectPath(projectName);
			if (projectPath != null && filePath.startsWith(projectPath)) {
				return projectName;
			}
		}
		return null;
	}
}
