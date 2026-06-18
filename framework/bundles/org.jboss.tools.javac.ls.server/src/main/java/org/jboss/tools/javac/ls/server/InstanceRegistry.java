/*******************************************************************************
 * Copyright (c) 2026 Red Hat, Inc. Distributed under license by Red Hat, Inc.
 * All rights reserved. This program is made available under the terms of the
 * Eclipse Public License v2.0 which accompanies this distribution, and is
 * available at http://www.eclipse.org/legal/epl-v20.html
 *
 * Contributors: Red Hat, Inc.
 ******************************************************************************/
package org.jboss.tools.javac.ls.server;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Manages registration of running javac-ls instances in ~/.javacls/running
 * Each instance creates a file named with its port number containing the workspace path.
 */
public class InstanceRegistry {
	private static final Logger LOG = LoggerFactory.getLogger(InstanceRegistry.class);

	private static final String REGISTRY_DIR_NAME = ".javacls";
	private static final String RUNNING_DIR_NAME = "running";

	private final int port;
	private final String workspacePath;
	private File registryFile;

	public InstanceRegistry(int port, String workspacePath) {
		this.port = port;
		this.workspacePath = workspacePath;
	}

	/**
	 * Register this instance by creating a file in ~/.javacls/running/
	 * Filename: port number
	 * Content: workspace path
	 *
	 * @throws IllegalStateException if the port is already in use by another instance
	 */
	public void register() {
		try {
			File runningDir = getRunningDirectory();
			if (!runningDir.exists()) {
				if (!runningDir.mkdirs()) {
					LOG.error("Failed to create instance registry directory: {}", runningDir.getAbsolutePath());
					return;
				}
			}

			// Clean up stale entries before registering
			cleanStaleEntries(runningDir);

			// Check if our port already has a registry file with an active server
			registryFile = new File(runningDir, String.valueOf(port));
			if (registryFile.exists() && isPortListening(port)) {
				String existingWorkspace = Files.readString(registryFile.toPath());
				String errorMsg = String.format(
					"Port %d is already in use by another javac-ls instance (workspace: %s)",
					port, existingWorkspace);
				LOG.error(errorMsg);
				throw new IllegalStateException(errorMsg);
			}

			Path registryPath = registryFile.toPath();

			// Write workspace path to file
			Files.writeString(registryPath, workspacePath,
					StandardOpenOption.CREATE,
					StandardOpenOption.TRUNCATE_EXISTING);

			// Backup cleanup mechanism
			registryFile.deleteOnExit();

			LOG.info("Registered javac-ls instance: port={}, workspace={}, registry={}",
					port, workspacePath, registryFile.getAbsolutePath());

		} catch (IOException e) {
			LOG.error("Failed to register javac-ls instance", e);
			throw new RuntimeException("Failed to register javac-ls instance", e);
		}
	}

	/**
	 * Unregister this instance by deleting the registry file
	 */
	public void unregister() {
		if (registryFile != null && registryFile.exists()) {
			try {
				if (registryFile.delete()) {
					LOG.info("Unregistered javac-ls instance: port={}", port);
				} else {
					LOG.warn("Failed to delete registry file: {}", registryFile.getAbsolutePath());
				}
			} catch (Exception e) {
				LOG.error("Error unregistering javac-ls instance", e);
			}
			registryFile = null;
		}
	}

	/**
	 * Clean up stale registry entries.
	 * A file is considered stale if:
	 * 1. It's at least 3 minutes old (to allow for startup time)
	 * 2. The port is not listening
	 *
	 * Only checks the oldest 20 files to limit startup time.
	 */
	private void cleanStaleEntries(File runningDir) {
		if (!runningDir.exists()) {
			return;
		}

		long now = System.currentTimeMillis();
		long staleThresholdMs = 3 * 60 * 1000; // 3 minutes

		File[] files = runningDir.listFiles();
		if (files == null || files.length == 0) {
			return;
		}

		// Sort by last modified time (oldest first) and take only first 20
		java.util.Arrays.sort(files, java.util.Comparator.comparingLong(File::lastModified));
		int maxToCheck = Math.min(20, files.length);

		for (int i = 0; i < maxToCheck; i++) {
			File file = files[i];
			try {
				// Only check files that are at least 3 minutes old
				long fileAge = now - file.lastModified();
				if (fileAge < staleThresholdMs) {
					continue;
				}

				// Check if this is a valid port number
				int filePort = Integer.parseInt(file.getName());

				// If port is not listening, remove the file
				if (!isPortListening(filePort)) {
					if (file.delete()) {
						LOG.info("Removed stale registry file: {} (age: {}ms, port not listening)",
								file.getName(), fileAge);
					} else {
						LOG.warn("Failed to delete stale registry file: {}", file.getName());
					}
				}
			} catch (NumberFormatException e) {
				// Not a port number, ignore
				LOG.debug("Ignoring non-port file in registry: {}", file.getName());
			}
		}
	}

	/**
	 * Check if a port is currently listening.
	 * Attempts to connect to localhost on the given port with a 200ms timeout.
	 */
	private boolean isPortListening(int testPort) {
		try (java.net.Socket socket = new java.net.Socket()) {
			socket.connect(new java.net.InetSocketAddress("localhost", testPort), 200);
			return true;
		} catch (IOException e) {
			return false;
		}
	}

	/**
	 * Get the ~/.javacls/running directory
	 */
	private static File getRunningDirectory() {
		String userHome = System.getProperty("user.home");
		return new File(new File(userHome, REGISTRY_DIR_NAME), RUNNING_DIR_NAME);
	}

	/**
	 * Get the ~/.javacls directory
	 */
	public static File getJavacLsDirectory() {
		String userHome = System.getProperty("user.home");
		return new File(userHome, REGISTRY_DIR_NAME);
	}
}
