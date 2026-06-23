/*******************************************************************************
 * Copyright (c) 2026 Red Hat, Inc. Distributed under license by Red Hat, Inc.
 * All rights reserved. This program is made available under the terms of the
 * Eclipse Public License v2.0 which accompanies this distribution, and is
 * available at http://www.eclipse.org/legal/epl-v20.html
 *
 * Contributors: Red Hat, Inc.
 ******************************************************************************/
package org.jboss.tools.javac.ls.server.event;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

import org.eclipse.lsp4j.Diagnostic;
import org.eclipse.lsp4j.DiagnosticSeverity;
import org.eclipse.lsp4j.Position;
import org.eclipse.lsp4j.PublishDiagnosticsParams;
import org.eclipse.lsp4j.Range;
import org.eclipse.lsp4j.services.LanguageClient;
import org.jboss.tools.javac.ls.api.JavacLSClient;
import org.jboss.tools.javac.ls.api.dao.DiagnosticList;
import org.jboss.tools.javac.ls.api.dao.InitializationState;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Manages broadcasting events to all connected clients.
 * Similar to RSP-Server's RemoteEventManager pattern.
 */
public class EventManager {
	private static final Logger LOG = LoggerFactory.getLogger(EventManager.class);

	/**
	 * Broadcast an initialization state change to all clients.
	 *
	 * @param clients the list of connected clients
	 * @param state the new initialization state value
	 */
	public static void fireInitializationStateChanged(List<LanguageClient> clients, int state) {
		fireInitializationStateChanged(clients, state, null);
	}

	/**
	 * Broadcast an initialization state change to all clients with an optional message.
	 *
	 * @param clients the list of connected clients
	 * @param state the new initialization state value
	 * @param message optional message about the state change
	 */
	public static void fireInitializationStateChanged(List<LanguageClient> clients, int state, String message) {
		InitializationState stateDao = new InitializationState(state, message);
		fireInitializationStateChanged(clients, stateDao);
	}

	/**
	 * Broadcast an initialization state change to all clients.
	 *
	 * @param clients the list of connected clients
	 * @param state the initialization state DAO
	 */
	public static void fireInitializationStateChanged(List<LanguageClient> clients, InitializationState state) {
		if (clients == null || clients.isEmpty()) {
			LOG.debug("No clients to notify of initialization state change: {}", state);
			return;
		}

		LOG.debug("Broadcasting initialization state change to {} clients: {}", clients.size(), state);

		for (LanguageClient client : clients) {
			try {
				// Cast to JavacLSClient for custom protocol method
				if (client instanceof JavacLSClient) {
					((JavacLSClient) client).initializationStateChanged(state);
				}
			} catch (Exception e) {
				LOG.error("Error notifying client of initialization state change", e);
			}
		}
	}

	/**
	 * Publish diagnostics for a specific file to all clients (LSP textDocument/publishDiagnostics).
	 *
	 * @param clients the list of connected clients
	 * @param filePath absolute file path
	 * @param diagnosticList diagnostics to publish
	 */
	public static void publishDiagnostics(List<LanguageClient> clients, String filePath, DiagnosticList diagnosticList) {
		if (clients == null || clients.isEmpty()) {
			LOG.debug("No clients to publish diagnostics for: {}", filePath);
			return;
		}

		try {
			// Convert file path to URI
			String uri = filePathToUri(filePath);

			// Convert our diagnostics to LSP diagnostics
			List<Diagnostic> lspDiagnostics = convertToLspDiagnostics(diagnosticList);

			// Create publish diagnostics notification
			PublishDiagnosticsParams params = new PublishDiagnosticsParams();
			params.setUri(uri);
			params.setDiagnostics(lspDiagnostics);

			LOG.debug("Publishing {} diagnostics for {} to {} client(s)",
				lspDiagnostics.size(), uri, clients.size());

			// Send to all connected clients
			for (LanguageClient client : clients) {
				try {
					client.publishDiagnostics(params);
				} catch (Exception e) {
					LOG.error("Error publishing diagnostics to client", e);
				}
			}

		} catch (Exception e) {
			LOG.error("Error publishing diagnostics for {}: {}", filePath, e.getMessage(), e);
		}
	}

	/**
	 * Publish diagnostics for all files in a project to all clients.
	 *
	 * @param clients the list of connected clients
	 * @param diagnosticList project-wide diagnostics (will be grouped by file)
	 */
	public static void publishProjectDiagnostics(List<LanguageClient> clients, DiagnosticList diagnosticList) {
		if (clients == null || clients.isEmpty()) {
			LOG.debug("No clients to publish project diagnostics");
			return;
		}

		// Group diagnostics by file and publish each file separately
		diagnosticList.getDiagnostics().stream()
			.collect(java.util.stream.Collectors.groupingBy(
				org.jboss.tools.javac.ls.api.dao.Diagnostic::getFilePath))
			.forEach((filePath, diagnostics) -> {
				DiagnosticList fileList = new DiagnosticList();
				fileList.getDiagnostics().addAll(diagnostics);
				publishDiagnostics(clients, filePath, fileList);
			});
	}

	/**
	 * Convert file path to LSP URI.
	 */
	public static String filePathToUri(String filePath) {
		try {
			File file = new File(filePath);
			return file.toURI().toString();
		} catch (Exception e) {
			LOG.error("Failed to convert path to URI: {}", filePath, e);
			return "file://" + filePath; // Fallback
		}
	}

	/**
	 * Convert our Diagnostic objects to LSP Diagnostic objects.
	 */
	public static List<Diagnostic> convertToLspDiagnostics(DiagnosticList diagnosticList) {
		List<Diagnostic> lspDiagnostics = new ArrayList<>();

		for (org.jboss.tools.javac.ls.api.dao.Diagnostic diag : diagnosticList.getDiagnostics()) {
			Diagnostic lspDiag = new Diagnostic();

			// Set severity
			switch (diag.getSeverity()) {
				case org.jboss.tools.javac.ls.api.dao.Diagnostic.ERROR:
					lspDiag.setSeverity(DiagnosticSeverity.Error);
					break;
				case org.jboss.tools.javac.ls.api.dao.Diagnostic.WARNING:
					lspDiag.setSeverity(DiagnosticSeverity.Warning);
					break;
				case org.jboss.tools.javac.ls.api.dao.Diagnostic.INFO:
					lspDiag.setSeverity(DiagnosticSeverity.Information);
					break;
				default:
					lspDiag.setSeverity(DiagnosticSeverity.Information);
			}

			// Set message
			lspDiag.setMessage(diag.getMessage());

			// Set range (LSP uses 0-based line numbers)
			int line = Math.max(0, diag.getLineNumber() - 1); // Our diagnostics are 1-based
			int startChar = Math.max(0, diag.getColumnNumber());

			Position start = new Position(line, startChar);
			Position end = new Position(line, startChar + 1); // Default to single char
			lspDiag.setRange(new Range(start, end));

			// Set source
			lspDiag.setSource("javac-ls");

			// Set code if available
			if (diag.getCode() != null && !diag.getCode().isEmpty()) {
				lspDiag.setCode(diag.getCode());
			}

			lspDiagnostics.add(lspDiag);
		}

		return lspDiagnostics;
	}
}
