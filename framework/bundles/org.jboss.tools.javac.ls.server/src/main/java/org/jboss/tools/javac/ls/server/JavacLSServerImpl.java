/*******************************************************************************
 * Copyright (c) 2026 Red Hat, Inc. Distributed under license by Red Hat, Inc.
 * All rights reserved. This program is made available under the terms of the
 * Eclipse Public License v2.0 which accompanies this distribution, and is
 * available at http://www.eclipse.org/legal/epl-v20.html
 *
 * Contributors: Red Hat, Inc.
 ******************************************************************************/
package org.jboss.tools.javac.ls.server;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;

import org.eclipse.lsp4j.InitializeParams;
import org.eclipse.lsp4j.InitializeResult;
import org.eclipse.lsp4j.ServerCapabilities;
import org.eclipse.lsp4j.services.LanguageClient;
import org.eclipse.lsp4j.services.TextDocumentService;
import org.eclipse.lsp4j.services.WorkspaceService;
import org.jboss.tools.javac.ls.api.JavacLSServer;
import org.jboss.tools.javac.ls.api.dao.DiagnosticList;
import org.jboss.tools.javac.ls.api.dao.ProjectInfo;
import org.jboss.tools.javac.ls.api.dao.Status;
import org.jboss.tools.javac.ls.server.model.WorkspaceModel;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class JavacLSServerImpl implements JavacLSServer {
	private static final Logger LOG = LoggerFactory.getLogger(JavacLSServerImpl.class);

	private final JavacLsServerLauncher launcher;
	private final List<LanguageClient> clients;
	private final TextDocumentService textDocumentService;
	private final WorkspaceService workspaceService;

	public JavacLSServerImpl(JavacLsServerLauncher launcher) {
		this.launcher = launcher;
		this.clients = new ArrayList<>();
		this.textDocumentService = new JavacTextDocumentService(this);
		this.workspaceService = new JavacWorkspaceService(this);
	}

	public void addClient(LanguageClient client) {
		synchronized(clients) {
			clients.add(client);
			LOG.info("Client connected (total: {})", clients.size());
		}
	}

	public void removeClient(LanguageClient client) {
		synchronized(clients) {
			clients.remove(client);
			LOG.info("Client disconnected (remaining: {})", clients.size());
		}
	}

	public List<LanguageClient> getClients() {
		synchronized(clients) {
			return new ArrayList<>(clients);
		}
	}

	@Override
	public CompletableFuture<Status> ping() {
		LOG.info("Received ping request");
		Status status = new Status(Status.OK, "Server is alive", null);
		return CompletableFuture.completedFuture(status);
	}

	@Override
	public void shutdownServer() {
		LOG.info("Received shutdownServer request (custom protocol)");
		launcher.shutdown();
	}

	@Override
	public CompletableFuture<Status> addProject(ProjectInfo project) {
		LOG.info("Received addProject request: {}", project);

		if (project == null || project.getName() == null || project.getPath() == null) {
			return CompletableFuture.completedFuture(
				new Status(Status.ERROR, "javac-ls", "Project name and path are required"));
		}

		WorkspaceModel workspace = launcher.getWorkspaceModel();
		if (workspace == null) {
			return CompletableFuture.completedFuture(
				new Status(Status.ERROR, "javac-ls", "Workspace not initialized"));
		}

		// Add project to workspace model (triggers projectAdded event)
		boolean added = workspace.addProject(project.getName(), project.getPath());
		if (!added) {
			return CompletableFuture.completedFuture(
				new Status(Status.ERROR, "javac-ls", "Project already exists: " + project.getName()));
		}

		// Index the new project in background
		launcher.indexProjectAsync(project.getName());

		return CompletableFuture.completedFuture(
			new Status(Status.OK, "javac-ls", "Project added and indexing started: " + project.getName()));
	}

	@Override
	public CompletableFuture<Status> removeProject(String projectName) {
		LOG.info("Received removeProject request: {}", projectName);

		if (projectName == null || projectName.trim().isEmpty()) {
			return CompletableFuture.completedFuture(
				new Status(Status.ERROR, "javac-ls", "Project name is required"));
		}

		WorkspaceModel workspace = launcher.getWorkspaceModel();
		if (workspace == null) {
			return CompletableFuture.completedFuture(
				new Status(Status.ERROR, "javac-ls", "Workspace not initialized"));
		}

		// Remove project from workspace model (triggers projectRemoved event and cleans up index)
		boolean removed = workspace.removeProject(projectName);
		if (!removed) {
			return CompletableFuture.completedFuture(
				new Status(Status.ERROR, "javac-ls", "Project not found: " + projectName));
		}

		return CompletableFuture.completedFuture(
			new Status(Status.OK, "javac-ls", "Project removed: " + projectName));
	}

	@Override
	public CompletableFuture<DiagnosticList> getProjectDiagnostics(String projectName) {
		LOG.debug("Received getProjectDiagnostics request: {}", projectName);

		if (projectName == null || projectName.trim().isEmpty()) {
			LOG.warn("Project name is required for getProjectDiagnostics");
			return CompletableFuture.completedFuture(new DiagnosticList());
		}

		WorkspaceModel workspace = launcher.getWorkspaceModel();
		if (workspace == null) {
			LOG.warn("Workspace not initialized");
			return CompletableFuture.completedFuture(new DiagnosticList(projectName));
		}

		// Get diagnostics from workspace model (will scan for changes first)
		DiagnosticList diagnostics = workspace.getProjectDiagnostics(projectName);

		LOG.debug("Returning {} diagnostics for project {}", diagnostics.getDiagnostics().size(), projectName);
		return CompletableFuture.completedFuture(diagnostics);
	}

	@Override
	public CompletableFuture<DiagnosticList> getFileDiagnostics(String filePath) {
		LOG.debug("Received getFileDiagnostics request: {}", filePath);

		if (filePath == null || filePath.trim().isEmpty()) {
			LOG.warn("File path is required for getFileDiagnostics");
			return CompletableFuture.completedFuture(new DiagnosticList());
		}

		WorkspaceModel workspace = launcher.getWorkspaceModel();
		if (workspace == null) {
			LOG.warn("Workspace not initialized");
			return CompletableFuture.completedFuture(new DiagnosticList());
		}

		// Get diagnostics from workspace model (will scan for changes first)
		DiagnosticList diagnostics = workspace.getFileDiagnostics(filePath);

		LOG.debug("Returning {} diagnostics for file {}", diagnostics.getDiagnostics().size(), filePath);
		return CompletableFuture.completedFuture(diagnostics);
	}

	// ========== LSP LanguageServer Interface Methods ==========

	@Override
	public CompletableFuture<InitializeResult> initialize(InitializeParams params) {
		LOG.info("LSP initialize request received");

		ServerCapabilities capabilities = new ServerCapabilities();

		// Advertise diagnostic support
		// Note: DiagnosticOptions is LSP 3.17+, may not be in lsp4j 1.0.0
		// For now, we support push diagnostics (textDocument/publishDiagnostics) which is always available
		// TODO: Add pull diagnostics capability when lsp4j supports it

		InitializeResult result = new InitializeResult(capabilities);
		return CompletableFuture.completedFuture(result);
	}

	@Override
	public CompletableFuture<Object> shutdown() {
		LOG.info("LSP shutdown request received");
		// LSP shutdown - prepare for exit but don't actually exit yet
		return CompletableFuture.completedFuture(null);
	}

	@Override
	public void exit() {
		LOG.info("LSP exit notification received");
		launcher.shutdown();
	}

	@Override
	public TextDocumentService getTextDocumentService() {
		return textDocumentService;
	}

	@Override
	public WorkspaceService getWorkspaceService() {
		return workspaceService;
	}

	// Package-protected accessor for services to access launcher
	JavacLsServerLauncher getLauncher() {
		return launcher;
	}
}
