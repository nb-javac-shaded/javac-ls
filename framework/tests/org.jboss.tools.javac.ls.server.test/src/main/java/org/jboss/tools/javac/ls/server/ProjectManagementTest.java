package org.jboss.tools.javac.ls.server;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.io.File;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;

import org.jboss.tools.javac.ls.api.JavacLSClient;
import org.jboss.tools.javac.ls.api.JavacLSServer;
import org.jboss.tools.javac.ls.api.dao.InitializationState;
import org.jboss.tools.javac.ls.api.dao.ProjectInfo;
import org.jboss.tools.javac.ls.api.dao.Status;
import org.jboss.tools.javac.ls.server.util.ClientLauncher;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

/**
 * Integration tests for project management (add/remove projects).
 */
public class ProjectManagementTest {

	private File tempWorkspaceDir;
	private JavacLsServerLauncher serverLauncher;
	private TestClientLauncher clientLauncher;
	private RecordingClient recordingClient;
	private int testPort;

	@Before
	public void setUp() throws Exception {
		// Create temp workspace directory
		tempWorkspaceDir = Files.createTempDirectory("test-project-mgmt").toFile();
		tempWorkspaceDir.deleteOnExit();

		// Use a random high port to avoid conflicts
		testPort = 50000 + new Random().nextInt(15000);

		// Start server
		serverLauncher = new TestServerLauncher(tempWorkspaceDir, true, testPort);
		serverLauncher.initialize();
		serverLauncher.launch();

		// Wait a moment for server to be ready
		Thread.sleep(500);

		// Connect client with recording wrapper
		recordingClient = new RecordingClient();
		clientLauncher = new TestClientLauncher("localhost", testPort, recordingClient);
		clientLauncher.launch();

		// Wait for connection
		Thread.sleep(500);

		// Clear any startup events
		recordingClient.clear();
	}

	@After
	public void tearDown() throws Exception {
		if (clientLauncher != null) {
			clientLauncher.closeConnection();
		}
		if (serverLauncher != null) {
			serverLauncher.shutdown();
		}
		if (tempWorkspaceDir != null) {
			deleteRecursively(tempWorkspaceDir);
		}
	}

	private void deleteRecursively(File file) {
		if (file.isDirectory()) {
			File[] children = file.listFiles();
			if (children != null) {
				for (File child : children) {
					deleteRecursively(child);
				}
			}
		}
		file.delete();
	}

	@Test
	public void testAddProjectViasAPI() throws Exception {
		// Create a project directory with Java files
		File projectDir = new File(tempWorkspaceDir, "test-project");
		projectDir.mkdirs();
		File srcDir = new File(projectDir, "src");
		srcDir.mkdirs();

		// Add a simple Java file
		File javaFile = new File(srcDir, "Test.java");
		Files.writeString(javaFile.toPath(), """
				public class Test {
					private String value;

					public String getValue() {
						return value;
					}
				}
				""");

		// Add the project via server API
		JavacLSServer server = clientLauncher.getServerProxy();
		ProjectInfo project = new ProjectInfo("test-project", projectDir.getAbsolutePath());
		Status status = server.addProject(project).get();

		// Verify status is OK
		assertTrue("addProject should succeed", status.isOK());
		assertEquals("Status should be OK", Status.OK, status.getSeverity());

		// Verify project is in workspace
		assertTrue("Workspace should have the project",
				serverLauncher.getWorkspaceModel().hasProject("test-project"));

		// Wait for indexing to complete
		Thread.sleep(2000);

		// Verify workspace reaches READY state after indexing
		assertEquals("Workspace should be READY after indexing",
				InitializationState.STATE_READY,
				serverLauncher.getWorkspaceModel().getInitializationState());

		// Verify client received state change events (INDEXING and READY)
		assertTrue("Client should receive initialization state changes",
				recordingClient.stateChanges.size() >= 2);

		// Should see INDEXING state
		boolean sawIndexing = recordingClient.stateChanges.stream()
				.anyMatch(s -> s.getState() == InitializationState.STATE_INDEXING);
		assertTrue("Client should see INDEXING state", sawIndexing);

		// Should see READY state (last event)
		InitializationState lastState = recordingClient.stateChanges.get(recordingClient.stateChanges.size() - 1);
		assertEquals("Final state should be READY",
				InitializationState.STATE_READY, lastState.getState());
	}

	@Test
	public void testRemoveProjectViaAPI() throws Exception {
		// First add a project
		File projectDir = new File(tempWorkspaceDir, "to-remove");
		projectDir.mkdirs();

		serverLauncher.getWorkspaceModel().addProject("to-remove", projectDir.getAbsolutePath());

		// Wait a moment for event to be broadcast
		Thread.sleep(200);

		// Clear events from adding the project
		recordingClient.clear();

		// Remove the project via server API
		JavacLSServer server = clientLauncher.getServerProxy();
		Status status = server.removeProject("to-remove").get();

		// Verify status is OK
		assertTrue("removeProject should succeed", status.isOK());

		// Wait a moment for event to be broadcast
		Thread.sleep(200);

		// Verify project is removed from workspace
		assertFalse("Workspace should not have the project",
				serverLauncher.getWorkspaceModel().hasProject("to-remove"));
	}

	@Test
	public void testAddDuplicateProjectFails() throws Exception {
		// Add a project
		File projectDir = new File(tempWorkspaceDir, "duplicate");
		projectDir.mkdirs();

		JavacLSServer server = clientLauncher.getServerProxy();
		ProjectInfo project = new ProjectInfo("duplicate", projectDir.getAbsolutePath());
		Status status1 = server.addProject(project).get();
		assertTrue("First addProject should succeed", status1.isOK());

		// Try to add same project again
		Status status2 = server.addProject(project).get();

		// Should fail with error
		assertFalse("Second addProject should fail", status2.isOK());
		assertEquals("Should return ERROR status", Status.ERROR, status2.getSeverity());
		assertTrue("Error message should mention 'already exists'",
				status2.getMessage().contains("already exists"));

		// Project count should still be 1
		assertEquals("Should still have only 1 project",
				1, serverLauncher.getWorkspaceModel().getProjectCount());
	}

	@Test
	public void testRemoveNonexistentProjectFails() throws Exception {
		JavacLSServer server = clientLauncher.getServerProxy();
		Status status = server.removeProject("nonexistent").get();

		// Should fail with error
		assertFalse("removeProject should fail for nonexistent project", status.isOK());
		assertEquals("Should return ERROR status", Status.ERROR, status.getSeverity());
		assertTrue("Error message should mention 'not found'",
				status.getMessage().contains("not found"));
	}

	@Test
	public void testAddProjectWithInvalidDataFails() throws Exception {
		JavacLSServer server = clientLauncher.getServerProxy();

		// Try with null project
		Status status1 = server.addProject(null).get();
		assertFalse("addProject with null should fail", status1.isOK());

		// Try with null name
		ProjectInfo projectNoName = new ProjectInfo(null, "/some/path");
		Status status2 = server.addProject(projectNoName).get();
		assertFalse("addProject with null name should fail", status2.isOK());

		// Try with null path
		ProjectInfo projectNoPath = new ProjectInfo("myproject", null);
		Status status3 = server.addProject(projectNoPath).get();
		assertFalse("addProject with null path should fail", status3.isOK());

		// Workspace should still be empty
		assertEquals("Workspace should have no projects",
				0, serverLauncher.getWorkspaceModel().getProjectCount());
	}

	/**
	 * Test server launcher that uses custom port and workspace.
	 */
	private static class TestServerLauncher extends JavacLsServerLauncher {
		private final File workspaceDir;
		private final boolean startupSync;

		public TestServerLauncher(File workspaceDir, boolean startupSync, int port) {
			super(String.valueOf(port));
			this.workspaceDir = workspaceDir;
			this.startupSync = startupSync;
		}

		@Override
		protected String getWorkspacePath() {
			return workspaceDir.getAbsolutePath();
		}

		@Override
		protected File getWorkspaceDirectory() {
			return workspaceDir;
		}

		@Override
		protected boolean isStartupSync() {
			return startupSync;
		}
	}

	/**
	 * Client implementation that records all events received from the server.
	 */
	private static class RecordingClient extends org.jboss.tools.javac.ls.server.util.TestJavacLSClient {
		List<InitializationState> stateChanges = new ArrayList<>();
		private JavacLSServer server;

		public void initialize(JavacLSServer server) {
			this.server = server;
		}

		public JavacLSServer getProxy() {
			return server;
		}

		@Override
		public void initializationStateChanged(InitializationState state) {
			System.out.println("Client received state change: " + state);
			stateChanges.add(state);
		}

		void clear() {
			stateChanges.clear();
		}
	}

	/**
	 * Test client launcher that uses a custom client implementation.
	 */
	private static class TestClientLauncher {
		private final String host;
		private final int port;
		private final RecordingClient client;
		private org.jboss.tools.javac.ls.api.SocketLauncher<JavacLSServer> launcher;
		private java.net.Socket socket;

		public TestClientLauncher(String host, int port, RecordingClient client) {
			this.host = host;
			this.port = port;
			this.client = client;
		}

		public void launch() throws Exception {
			// Connect to the server
			this.socket = new java.net.Socket(host, port);
			// Open a JSON-RPC connection for the opened socket
			this.launcher = new org.jboss.tools.javac.ls.api.SocketLauncher<>(client, JavacLSServer.class, socket);
			// Start listening for incoming messages
			launcher.startListening();
			// Initialize the client with the remote proxy
			client.initialize(launcher.getRemoteProxy());
		}

		public void closeConnection() {
			if (launcher != null) {
				launcher.close();
			}
		}

		public JavacLSServer getServerProxy() {
			return client.getProxy();
		}
	}
}
