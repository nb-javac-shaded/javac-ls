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

import org.jboss.tools.javac.ls.api.JavacLSClient;
import org.jboss.tools.javac.ls.api.JavacLSServer;
import org.jboss.tools.javac.ls.api.dao.ProjectInfo;
import org.jboss.tools.javac.ls.api.dao.Status;
import org.jboss.tools.javac.ls.server.model.WorkspaceModel;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class JavacLSServerImpl implements JavacLSServer {
	private static final Logger LOG = LoggerFactory.getLogger(JavacLSServerImpl.class);

	private final JavacLsServerLauncher launcher;
	private final List<JavacLSClient> clients;

	public JavacLSServerImpl(JavacLsServerLauncher launcher) {
		this.launcher = launcher;
		this.clients = new ArrayList<>();
	}

	public void addClient(JavacLSClient client) {
		synchronized(clients) {
			clients.add(client);
		}
	}

	public void removeClient(JavacLSClient client) {
		synchronized(clients) {
			clients.remove(client);
		}
	}

	public List<JavacLSClient> getClients() {
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
	public void shutdown() {
		LOG.info("Received shutdown request");
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
}
