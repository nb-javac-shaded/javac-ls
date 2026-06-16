package org.jboss.tools.javac.ls.server;

import static org.junit.Assert.*;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Random;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

/**
 * Tests for the InstanceRegistry that tracks running javac-ls instances.
 */
public class InstanceRegistryTest {

	private File tempWorkspaceDir;
	private int testPort;
	private InstanceRegistry registry;

	@Before
	public void setUp() throws IOException {
		// Create temp workspace directory
		tempWorkspaceDir = Files.createTempDirectory("test-registry-workspace").toFile();
		tempWorkspaceDir.deleteOnExit();

		// Use a random high port to avoid conflicts
		testPort = 50000 + new Random().nextInt(15000);
	}

	@After
	public void tearDown() {
		if (registry != null) {
			registry.unregister();
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
	public void testRegistryCreatesFile() {
		// Create and register
		registry = new InstanceRegistry(testPort, tempWorkspaceDir.getAbsolutePath());
		registry.register();

		// Verify file was created
		File runningDir = new File(System.getProperty("user.home"), ".javacls/running");
		assertTrue("Running directory should exist", runningDir.exists());
		assertTrue("Running directory should be a directory", runningDir.isDirectory());

		File registryFile = new File(runningDir, String.valueOf(testPort));
		assertTrue("Registry file should exist: " + registryFile.getAbsolutePath(), registryFile.exists());
	}

	@Test
	public void testRegistryFileContainsWorkspacePath() throws IOException {
		// Create and register
		registry = new InstanceRegistry(testPort, tempWorkspaceDir.getAbsolutePath());
		registry.register();

		// Read file contents
		File runningDir = new File(System.getProperty("user.home"), ".javacls/running");
		File registryFile = new File(runningDir, String.valueOf(testPort));
		String contents = Files.readString(registryFile.toPath());

		// Verify contents match workspace path
		assertEquals("Registry file should contain workspace path",
				tempWorkspaceDir.getAbsolutePath(), contents);
	}

	@Test
	public void testUnregisterDeletesFile() {
		// Create and register
		registry = new InstanceRegistry(testPort, tempWorkspaceDir.getAbsolutePath());
		registry.register();

		File runningDir = new File(System.getProperty("user.home"), ".javacls/running");
		File registryFile = new File(runningDir, String.valueOf(testPort));
		assertTrue("Registry file should exist before unregister", registryFile.exists());

		// Unregister
		registry.unregister();

		// Verify file was deleted
		assertFalse("Registry file should not exist after unregister", registryFile.exists());
	}

	@Test
	public void testMultipleInstancesCanRegister() throws IOException {
		// Create first instance
		int port1 = testPort;
		File workspace1 = tempWorkspaceDir;
		InstanceRegistry registry1 = new InstanceRegistry(port1, workspace1.getAbsolutePath());
		registry1.register();

		// Create second instance with different port
		int port2 = testPort + 1;
		File workspace2 = Files.createTempDirectory("test-registry-workspace2").toFile();
		workspace2.deleteOnExit();
		InstanceRegistry registry2 = new InstanceRegistry(port2, workspace2.getAbsolutePath());
		registry2.register();

		try {
			// Verify both files exist
			File runningDir = new File(System.getProperty("user.home"), ".javacls/running");
			File registryFile1 = new File(runningDir, String.valueOf(port1));
			File registryFile2 = new File(runningDir, String.valueOf(port2));

			assertTrue("First registry file should exist", registryFile1.exists());
			assertTrue("Second registry file should exist", registryFile2.exists());

			// Verify contents are different
			String contents1 = Files.readString(registryFile1.toPath());
			String contents2 = Files.readString(registryFile2.toPath());
			assertNotEquals("Registry files should have different workspace paths", contents1, contents2);

		} finally {
			// Clean up
			registry1.unregister();
			registry2.unregister();
			deleteRecursively(workspace2);
		}
	}

	@Test
	public void testLauncherRegistersAndUnregisters() throws IOException {
		// Create a test launcher
		TestServerLauncher launcher = new TestServerLauncher(tempWorkspaceDir, true);
		launcher.initialize();

		// Verify registry file was created
		File runningDir = new File(System.getProperty("user.home"), ".javacls/running");
		File registryFile = new File(runningDir, String.valueOf(testPort));
		assertTrue("Registry file should exist after launcher initialization", registryFile.exists());

		// Read contents
		String contents = Files.readString(registryFile.toPath());
		assertEquals("Registry should contain workspace path",
				tempWorkspaceDir.getAbsolutePath(), contents);

		// Shutdown
		launcher.shutdown();

		// Verify registry file was deleted
		assertFalse("Registry file should not exist after shutdown", registryFile.exists());
	}

	/**
	 * Test subclass of JavacLsServerLauncher that uses a test port
	 */
	private class TestServerLauncher extends JavacLsServerLauncher {
		private final File workspaceDir;
		private final boolean startupSync;

		public TestServerLauncher(File workspaceDir, boolean startupSync) {
			super(String.valueOf(testPort));
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
}
