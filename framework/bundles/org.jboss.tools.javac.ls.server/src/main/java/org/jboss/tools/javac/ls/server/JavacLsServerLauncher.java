/*******************************************************************************
 * Copyright (c) 2026 Red Hat, Inc. Distributed under license by Red Hat, Inc.
 * All rights reserved. This program is made available under the terms of the
 * Eclipse Public License v2.0 which accompanies this distribution, and is
 * available at http://www.eclipse.org/legal/epl-v20.html
 *
 * Contributors: Red Hat, Inc.
 ******************************************************************************/
package org.jboss.tools.javac.ls.server;

import java.io.IOException;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import org.eclipse.lsp4j.jsonrpc.Launcher;
import org.eclipse.lsp4j.jsonrpc.MessageConsumer;
import org.jboss.tools.javac.ls.api.JavacLSClient;
import org.jboss.tools.javac.ls.api.SocketLauncher;
import org.jboss.tools.javac.ls.server.event.EventManager;
import org.jboss.tools.javac.ls.server.model.WorkspaceModel;
import org.jboss.tools.javac.ls.server.model.WorkspaceModelListener;
import org.jboss.tools.javac.ls.server.model.WorkspaceProject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class JavacLsServerLauncher {
	private static final Logger LOG = LoggerFactory.getLogger(JavacLsServerLauncher.class);

	public static void main(String[] args) throws Exception {
		JavacLsServerLauncher instance = new JavacLsServerLauncher(args[0]);
		instance.initialize();

		// Wait for READY state if configured
		if (ServerFlags.isStartupWaitForReady()) {
			instance.waitForReady();
		}

		instance.launch();
		instance.addShutdownHook();
		instance.shutdownOnInput();
	}

	private void addShutdownHook() {
		Runtime.getRuntime().addShutdownHook(new Thread() {
			@Override
			public void run() {
				shutdown();
			}
		});
	}

	public void shutdownOnInput() throws IOException {
		System.out.println("Enter any character to stop");
		System.in.read();
		shutdown();
	}

	protected JavacLSServerImpl serverImpl;
	private ListenOnSocketRunnable socketRunnable;
	private ServerSocket serverSocket;
	protected String portString;
	private WorkspaceModel workspaceModel;
	private InstanceRegistry instanceRegistry;
	private WorkspaceModelListener workspaceListener;

	public JavacLsServerLauncher(String portString) {
		this.portString = portString;
		this.serverImpl = new JavacLSServerImpl(this);
	}

	/**
	 * Initialize the workspace model. Must be called after construction
	 * and before using the launcher.
	 */
	public void initialize() {
		initialize(isStartupSync());
	}
	
	public void initialize(boolean sync) {
		// Log and ensure workspace directory exists
		String workspacePath = getWorkspacePath();
		java.io.File workspaceDir = getWorkspaceDirectory();
		if (!workspaceDir.exists()) {
			if (workspaceDir.mkdirs()) {
				LOG.info("Created workspace directory: {}", workspacePath);
			} else {
				LOG.warn("Failed to create workspace directory: {}", workspacePath);
			}
		} else {
			LOG.info("Using workspace directory: {}", workspacePath);
		}

		// Register this instance in ~/.javacls/running
		int port = Integer.parseInt(portString);
		instanceRegistry = new InstanceRegistry(port, workspacePath);
		instanceRegistry.register();

		// Load workspace model (loads from cache only, no parsing)
		workspaceModel = new WorkspaceModel(workspaceDir);
		LOG.info("Loaded workspace model with {} projects", workspaceModel.getProjectCount());

		// Register listener to broadcast workspace events to clients
		workspaceListener = new WorkspaceEventBroadcaster();
		workspaceModel.addListener(workspaceListener);

		// Start indexing with binding resolution (sync or async based on flag)
		workspaceModel.startIndexing(sync);
		LOG.info("Started {} indexing with binding resolution", sync ? "synchronous" : "background");
	}

	/**
	 * Get the workspace path from server flags.
	 * Can be overridden in tests to provide a custom workspace path.
	 *
	 * @return workspace path string
	 */
	protected String getWorkspacePath() {
		return ServerFlags.getWorkspacePath();
	}

	/**
	 * Get the workspace directory from server flags.
	 * Can be overridden in tests to provide a custom workspace directory.
	 *
	 * @return workspace directory file
	 */
	protected java.io.File getWorkspaceDirectory() {
		return ServerFlags.getWorkspaceDirectory();
	}

	/**
	 * Check if startup should be synchronous from server flags.
	 * Can be overridden in tests to control indexing behavior.
	 *
	 * @return true if startup should be synchronous
	 */
	protected boolean isStartupSync() {
		return ServerFlags.isStartupSync();
	}

	public List<JavacLSClient> getClients() {
		return serverImpl.getClients();
	}

	public WorkspaceModel getWorkspaceModel() {
		return workspaceModel;
	}

	/**
	 * Index a project asynchronously in the background.
	 * Changes initialization state to INDEXING while indexing, then back to READY.
	 *
	 * @param projectName the name of the project to index
	 */
	public void indexProjectAsync(String projectName) {
		if (workspaceModel == null) {
			LOG.warn("Cannot index project - workspace not initialized");
			return;
		}

		LOG.info("Starting background indexing for project: {}", projectName);
		workspaceModel.indexProjectAsync(projectName);
	}

	/**
	 * Wait for workspace to reach READY state.
	 * Polls the initialization state and blocks until READY.
	 */
	private void waitForReady() {
		LOG.info("Waiting for workspace to reach READY state before opening socket");
		long startTime = System.currentTimeMillis();

		while (!workspaceModel.isReady()) {
			try {
				Thread.sleep(100);
			} catch (InterruptedException e) {
				LOG.error("Interrupted while waiting for READY state", e);
				Thread.currentThread().interrupt();
				return;
			}
		}

		long duration = System.currentTimeMillis() - startTime;
		LOG.info("Workspace reached READY state after {}ms", duration);
	}

	public void launch() throws Exception {
		launch(Integer.parseInt(this.portString));
	}

	public void launch(int port) throws Exception {
		startListening(port, serverImpl);
	}

	protected void startListening(int port, JavacLSServerImpl server) throws IOException {
		ExecutorService threadPool = Executors.newCachedThreadPool();
		serverSocket = new ServerSocket(port);
		socketRunnable = new ListenOnSocketRunnable(serverSocket, server, threadPool);
		threadPool.submit(socketRunnable);
		LOG.info("Server listening on port {}", port);
	}

	public void shutdown() {
		LOG.info("Shutting down server");

		// Unregister workspace listener
		if (workspaceModel != null && workspaceListener != null) {
			workspaceModel.removeListener(workspaceListener);
		}

		// Unregister this instance from the registry
		if (instanceRegistry != null) {
			instanceRegistry.unregister();
		}

		if (socketRunnable != null) {
			socketRunnable.shutdown();
		}
		if (serverSocket != null && !serverSocket.isClosed()) {
			try {
				serverSocket.close();
			} catch (IOException e) {
				LOG.error("Error closing server socket", e);
			}
		}
	}

	/**
	 * Workspace listener that broadcasts events to all connected clients.
	 */
	private class WorkspaceEventBroadcaster implements WorkspaceModelListener {

		@Override
		public void initializationStateChanged(int oldState, int newState) {
			LOG.debug("Broadcasting initialization state change: {} -> {}", oldState, newState);
			EventManager.fireInitializationStateChanged(getClients(), newState);
		}

		@Override
		public void projectAdded(WorkspaceProject project) {
			LOG.debug("Broadcasting project added: {}", project.getName());
			EventManager.fireProjectAdded(getClients(), project.getName(), project.getPath());
		}

		@Override
		public void projectRemoved(WorkspaceProject project) {
			LOG.debug("Broadcasting project removed: {}", project.getName());
			EventManager.fireProjectRemoved(getClients(), project.getName(), project.getPath());
		}
	}

	private static class ListenOnSocketRunnable implements Runnable {
		private final ServerSocket serverSocket;
		private final JavacLSServerImpl server;
		private final ExecutorService threadPool;
		private boolean running = true;

		public ListenOnSocketRunnable(ServerSocket serverSocket, JavacLSServerImpl server,
				ExecutorService threadPool) {
			this.serverSocket = serverSocket;
			this.server = server;
			this.threadPool = threadPool;
		}

		public void shutdown() {
			running = false;
		}

		@Override
		public void run() {
			while (running && !serverSocket.isClosed()) {
				try {
					Socket socket = serverSocket.accept();
					threadPool.submit(new ClientConnectionRunnable(socket, server));
				} catch (IOException e) {
					if (running) {
						LOG.error("Error accepting client connection", e);
					}
				}
			}
		}
	}

	private static class ClientConnectionRunnable implements Runnable {
		private final Socket socket;
		private final JavacLSServerImpl server;

		public ClientConnectionRunnable(Socket socket, JavacLSServerImpl server) {
			this.socket = socket;
			this.server = server;
		}

		@Override
		public void run() {
			JavacLSClient client = null;
			try {
				Launcher<JavacLSClient> launcher = new SocketLauncher<>(server, JavacLSClient.class, socket);
				client = launcher.getRemoteProxy();
				server.addClient(client);
				LOG.info("Client connected from {}", socket.getRemoteSocketAddress());

				// Wait for the connection to close
				launcher.startListening().get();

				// Connection closed
				server.removeClient(client);
				LOG.info("Client disconnected from {}", socket.getRemoteSocketAddress());
			} catch (Exception e) {
				LOG.error("Error handling client connection", e);
				if (client != null) {
					server.removeClient(client);
				}
			} finally {
				try {
					socket.close();
				} catch (IOException e) {
					LOG.error("Error closing client socket", e);
				}
			}
		}
	}
}
