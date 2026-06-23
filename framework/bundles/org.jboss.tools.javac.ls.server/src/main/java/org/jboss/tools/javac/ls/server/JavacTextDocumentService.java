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
		// TODO: Track opened documents, trigger initial indexing/parsing
	}

	@Override
	public void didChange(DidChangeTextDocumentParams params) {
		LOG.debug("didChange: {}", params.getTextDocument().getUri());
		// TODO: Update document content, re-parse, update diagnostics
	}

	@Override
	public void didClose(DidCloseTextDocumentParams params) {
		LOG.debug("didClose: {}", params.getTextDocument().getUri());
		// TODO: Remove from tracked documents
	}

	@Override
	public void didSave(DidSaveTextDocumentParams params) {
		LOG.debug("didSave: {}", params.getTextDocument().getUri());
		// TODO: Trigger re-indexing after save
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
}
