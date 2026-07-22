/*******************************************************************************
 * Copyright (c) 2026 Red Hat, Inc. Distributed under license by Red Hat, Inc.
 * All rights reserved. This program is made available under the terms of the
 * Eclipse Public License v2.0 which accompanies this distribution, and is
 * available at http://www.eclipse.org/legal/epl-v20.html
 *
 * Contributors: Red Hat, Inc.
 ******************************************************************************/
package org.jboss.tools.javac.ls.integration;

import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assume.assumeTrue;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.ConnectException;
import java.net.Socket;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.Random;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

import org.eclipse.lsp4j.InitializeParams;
import org.eclipse.lsp4j.InitializeResult;
import org.eclipse.lsp4j.MessageActionItem;
import org.eclipse.lsp4j.MessageParams;
import org.eclipse.lsp4j.PublishDiagnosticsParams;
import org.eclipse.lsp4j.ShowMessageRequestParams;
import org.eclipse.lsp4j.WorkspaceFolder;
import org.jboss.tools.javac.ls.api.JavacLSClient;
import org.jboss.tools.javac.ls.api.JavacLSServer;
import org.jboss.tools.javac.ls.api.SocketLauncher;
import org.jboss.tools.javac.ls.api.dao.InitializationState;
import org.jboss.tools.javac.ls.api.dao.Status;
import org.junit.After;
import org.junit.Test;

import java.util.concurrent.CompletableFuture;

/**
 * End-to-end test that launches the Felix distribution as a subprocess,
 * connects an LSP client over TCP, sends {@code initialize} with a project
 * workspace folder, and verifies the server indexes successfully.
 *
 * <p>Skipped automatically if the distribution zip or benchmark project
 * directory does not exist.
 *
 * <p>System properties:
 * <ul>
 *   <li>{@code javacls.test.distribution.zip} — path to the distribution zip
 *       (set automatically by Maven surefire from the build output)</li>
 *   <li>{@code javacls.test.benchmark.path} — path to the project to index
 *       (default: {@code ~/apps/claude/benchmarks/quarkus})</li>
 * </ul>
 */
public class QuarkusEndToEndTest {

	private static final String SYSPROP_DISTRIBUTION_ZIP = "javacls.test.distribution.zip";
	private static final String SYSPROP_BENCHMARK_PATH = "javacls.test.benchmark.path";
	private static final String DEFAULT_BENCHMARK_PATH =
			System.getProperty("user.home") + "/apps/claude/benchmarks/quarkus";

	private Process serverProcess;
	private SocketLauncher<JavacLSServer> clientLauncher;
	private Path tempDir;

	@After
	public void tearDown() {
		if (clientLauncher != null) {
			clientLauncher.close();
			clientLauncher = null;
		}
		if (serverProcess != null) {
			serverProcess.destroyForcibly();
			serverProcess = null;
		}
		if (tempDir != null) {
			deleteRecursively(tempDir);
			tempDir = null;
		}
	}

	@Test(timeout = 120_000)
	public void testFelixStartupAndQuarkusIndex() throws Exception {
		// --- Preconditions ---
		File distributionZip = getDistributionZip();
		assumeTrue("Distribution zip not found at " + distributionZip +
				" — build the distribution first or set -D" + SYSPROP_DISTRIBUTION_ZIP,
				distributionZip.exists());

		File benchmarkDir = getBenchmarkProjectDir();
		assumeTrue("Benchmark directory not found at " + benchmarkDir +
				" — set -D" + SYSPROP_BENCHMARK_PATH + "=<path> to override",
				benchmarkDir.exists() && benchmarkDir.isDirectory());

		// --- Setup ---
		tempDir = Files.createTempDirectory("javacls-e2e-");
		unzip(distributionZip, tempDir.toFile());

		File distDir = tempDir.resolve("javacls-distribution").toFile();
		assertTrue("Distribution directory not found after unzip", distDir.isDirectory());
		assertTrue("felix.jar not found in distribution",
				new File(distDir, "bin/felix.jar").isFile());

		File workspaceDir = Files.createTempDirectory("javacls-e2e-workspace-").toFile();
		int port = 10000 + new Random().nextInt(1000);

		// --- Launch Felix subprocess ---
		System.out.println("Launching Felix on port " + port + " ...");
		ProcessBuilder pb = new ProcessBuilder(
				"java",
				"-Xmx4g",
				"-Djavacls.server.port=" + port,
				"-Djavacls.workspace.path=" + workspaceDir.getAbsolutePath(),
				"-jar", "bin/felix.jar"
		);
		pb.directory(distDir);
		pb.redirectErrorStream(true);
		serverProcess = pb.start();

		// Drain subprocess output in background so it doesn't block
		Thread outputDrainer = new Thread(() -> {
			try (BufferedReader reader = new BufferedReader(
					new InputStreamReader(serverProcess.getInputStream()))) {
				String line;
				while ((line = reader.readLine()) != null) {
					System.out.println("  [felix] " + line);
				}
			} catch (IOException e) {
				// process died, expected during teardown
			}
		}, "felix-output-drainer");
		outputDrainer.setDaemon(true);
		outputDrainer.start();

		// --- Wait for server to accept TCP connections ---
		System.out.println("Waiting for server to accept connections on port " + port + " ...");
		waitForPort(port, 30_000);
		System.out.println("Server is accepting connections.");

		// --- Connect LSP client ---
		CountDownLatch readyLatch = new CountDownLatch(1);

		JavacLSClient testClient = new JavacLSClient() {
			@Override
			public void initializationStateChanged(InitializationState state) {
				System.out.println("  [client] initializationStateChanged: " +
						InitializationState.stateToString(state.getState()));
				if (state.getState() == InitializationState.STATE_READY) {
					readyLatch.countDown();
				}
			}
			@Override public void telemetryEvent(Object object) {}
			@Override public void publishDiagnostics(PublishDiagnosticsParams diagnostics) {}
			@Override public void showMessage(MessageParams messageParams) {}
			@Override public CompletableFuture<MessageActionItem> showMessageRequest(ShowMessageRequestParams requestParams) {
				return CompletableFuture.completedFuture(null);
			}
			@Override public void logMessage(MessageParams message) {}
		};

		Socket socket = new Socket("localhost", port);
		clientLauncher = new SocketLauncher<>(testClient, JavacLSServer.class, socket);
		clientLauncher.startListening();
		JavacLSServer serverProxy = clientLauncher.getRemoteProxy();

		// --- Send LSP initialize ---
		System.out.println("Sending LSP initialize with workspace: " + benchmarkDir.getAbsolutePath());
		long initStart = System.currentTimeMillis();

		InitializeParams params = new InitializeParams();
		params.setWorkspaceFolders(List.of(
				new WorkspaceFolder(benchmarkDir.toURI().toString(), benchmarkDir.getName())));
		InitializeResult result = serverProxy.initialize(params).get(10, TimeUnit.SECONDS);

		assertNotNull("InitializeResult should not be null", result);
		assertNotNull("Capabilities should not be null", result.getCapabilities());
		System.out.println("LSP initialize completed.");

		// --- Wait for indexing to finish ---
		System.out.println("Waiting for server to reach READY state ...");
		boolean ready = readyLatch.await(90, TimeUnit.SECONDS);
		long indexTime = System.currentTimeMillis() - initStart;
		assertTrue("Server did not reach READY state within 90 seconds (elapsed: " + indexTime + "ms)", ready);

		System.out.println("Server reached READY in " + indexTime + "ms.");

		// --- Verify server is alive and responsive ---
		Status pingStatus = serverProxy.ping().get(5, TimeUnit.SECONDS);
		assertNotNull("Ping response should not be null", pingStatus);
		assertTrue("Ping should return OK status, got: " + pingStatus.getMessage(),
				pingStatus.isOK());

		System.out.println();
		System.out.println("=== Quarkus End-to-End Test Results ===");
		System.out.printf("  Index time:     %,d ms%n", indexTime);
		System.out.printf("  Ping status:    %s%n", pingStatus.getMessage());
		System.out.println("=======================================");
	}

	private static File getDistributionZip() {
		String path = System.getProperty(SYSPROP_DISTRIBUTION_ZIP);
		if (path != null) {
			return new File(path);
		}
		// Fallback: try relative path from working directory
		return new File("../distribution/distribution/target/org.jboss.tools.javac.ls.distribution-0.0.1-SNAPSHOT.zip");
	}

	private static File getBenchmarkProjectDir() {
		String path = System.getProperty(SYSPROP_BENCHMARK_PATH, DEFAULT_BENCHMARK_PATH);
		return new File(path);
	}

	private static void waitForPort(int port, long timeoutMs) throws Exception {
		long deadline = System.currentTimeMillis() + timeoutMs;
		while (System.currentTimeMillis() < deadline) {
			try (Socket probe = new Socket("localhost", port)) {
				return;
			} catch (ConnectException e) {
				Thread.sleep(500);
			}
		}
		throw new AssertionError("Server did not start listening on port " + port +
				" within " + timeoutMs + "ms");
	}

	private static void unzip(File zipFile, File destDir) throws IOException {
		try (ZipInputStream zis = new ZipInputStream(new FileInputStream(zipFile))) {
			ZipEntry entry;
			while ((entry = zis.getNextEntry()) != null) {
				File target = new File(destDir, entry.getName());
				if (entry.isDirectory()) {
					target.mkdirs();
				} else {
					target.getParentFile().mkdirs();
					Files.copy(zis, target.toPath());
				}
				zis.closeEntry();
			}
		}
	}

	private static void deleteRecursively(Path dir) {
		try {
			Files.walk(dir)
				.sorted(Comparator.reverseOrder())
				.forEach(p -> {
					try { Files.delete(p); } catch (IOException e) { /* best effort */ }
				});
		} catch (IOException e) {
			// best effort
		}
	}
}
