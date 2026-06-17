/*******************************************************************************
 * Copyright (c) 2025 Red Hat, Inc. Distributed under license by Red Hat, Inc.
 * All rights reserved. This program is made available under the terms of the
 * Eclipse Public License v2.0 which accompanies this distribution, and is
 * available at http://www.eclipse.org/legal/epl-v20.html
 *
 * Contributors: Red Hat, Inc.
 ******************************************************************************/
package org.jboss.tools.javac.ls.server.event;

import java.util.List;

import org.jboss.tools.javac.ls.api.JavacLSClient;
import org.jboss.tools.javac.ls.api.dao.InitializationState;
import org.jboss.tools.javac.ls.api.dao.ProjectInfo;
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
	public static void fireInitializationStateChanged(List<JavacLSClient> clients, int state) {
		fireInitializationStateChanged(clients, state, null);
	}

	/**
	 * Broadcast an initialization state change to all clients with an optional message.
	 *
	 * @param clients the list of connected clients
	 * @param state the new initialization state value
	 * @param message optional message about the state change
	 */
	public static void fireInitializationStateChanged(List<JavacLSClient> clients, int state, String message) {
		InitializationState stateDao = new InitializationState(state, message);
		fireInitializationStateChanged(clients, stateDao);
	}

	/**
	 * Broadcast an initialization state change to all clients.
	 *
	 * @param clients the list of connected clients
	 * @param state the initialization state DAO
	 */
	public static void fireInitializationStateChanged(List<JavacLSClient> clients, InitializationState state) {
		if (clients == null || clients.isEmpty()) {
			LOG.debug("No clients to notify of initialization state change: {}", state);
			return;
		}

		LOG.debug("Broadcasting initialization state change to {} clients: {}", clients.size(), state);

		for (JavacLSClient client : clients) {
			try {
				client.initializationStateChanged(state);
			} catch (Exception e) {
				LOG.error("Error notifying client of initialization state change", e);
			}
		}
	}

	/**
	 * Broadcast a project added event to all clients.
	 *
	 * @param clients the list of connected clients
	 * @param projectName the project name
	 * @param projectPath the project path
	 */
	public static void fireProjectAdded(List<JavacLSClient> clients, String projectName, String projectPath) {
		ProjectInfo project = new ProjectInfo(projectName, projectPath);
		fireProjectAdded(clients, project);
	}

	/**
	 * Broadcast a project added event to all clients.
	 *
	 * @param clients the list of connected clients
	 * @param project the project info DAO
	 */
	public static void fireProjectAdded(List<JavacLSClient> clients, ProjectInfo project) {
		if (clients == null || clients.isEmpty()) {
			LOG.debug("No clients to notify of project added: {}", project);
			return;
		}

		LOG.debug("Broadcasting project added to {} clients: {}", clients.size(), project);

		for (JavacLSClient client : clients) {
			try {
				client.projectAdded(project);
			} catch (Exception e) {
				LOG.error("Error notifying client of project added", e);
			}
		}
	}

	/**
	 * Broadcast a project removed event to all clients.
	 *
	 * @param clients the list of connected clients
	 * @param projectName the project name
	 * @param projectPath the project path
	 */
	public static void fireProjectRemoved(List<JavacLSClient> clients, String projectName, String projectPath) {
		ProjectInfo project = new ProjectInfo(projectName, projectPath);
		fireProjectRemoved(clients, project);
	}

	/**
	 * Broadcast a project removed event to all clients.
	 *
	 * @param clients the list of connected clients
	 * @param project the project info DAO
	 */
	public static void fireProjectRemoved(List<JavacLSClient> clients, ProjectInfo project) {
		if (clients == null || clients.isEmpty()) {
			LOG.debug("No clients to notify of project removed: {}", project);
			return;
		}

		LOG.debug("Broadcasting project removed to {} clients: {}", clients.size(), project);

		for (JavacLSClient client : clients) {
			try {
				client.projectRemoved(project);
			} catch (Exception e) {
				LOG.error("Error notifying client of project removed", e);
			}
		}
	}
}
