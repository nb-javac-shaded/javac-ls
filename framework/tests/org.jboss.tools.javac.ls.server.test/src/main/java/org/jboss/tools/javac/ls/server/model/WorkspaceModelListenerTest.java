package org.jboss.tools.javac.ls.server.model;

import static org.junit.Assert.*;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;

import org.jboss.tools.javac.ls.api.dao.InitializationState;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

/**
 * Tests for WorkspaceModelListener functionality.
 */
public class WorkspaceModelListenerTest {

	private File tempWorkspaceDir;
	private WorkspaceModel model;
	private TestListener listener;

	@Before
	public void setUp() throws IOException {
		// Create temp workspace directory
		tempWorkspaceDir = Files.createTempDirectory("test-workspace-listener").toFile();
		tempWorkspaceDir.deleteOnExit();

		// Create workspace model
		model = new WorkspaceModel(tempWorkspaceDir);
		listener = new TestListener();
		model.addListener(listener);
	}

	@After
	public void tearDown() {
		if (model != null) {
			model.shutdown();
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
	public void testInitializationStateChangeNotification() {
		// Clear any initialization events from constructor
		listener.clear();

		// Change state
		model.setInitializationState(InitializationState.STATE_INDEXING);

		// Verify listener was notified
		assertEquals("Should have one state change event", 1, listener.stateChanges.size());
		StateChange change = listener.stateChanges.get(0);
		assertEquals("Old state should be LOADING_CACHE",
				InitializationState.STATE_LOADING_CACHE, change.oldState);
		assertEquals("New state should be INDEXING",
				InitializationState.STATE_INDEXING, change.newState);
	}

	@Test
	public void testProjectAddedNotification() {
		// Add a project
		File projectDir = new File(tempWorkspaceDir, "test-project");
		projectDir.mkdir();
		boolean added = model.addProject("test-project", projectDir.getAbsolutePath());

		assertTrue("Project should be added", added);
		assertEquals("Should have one project added event", 1, listener.projectsAdded.size());

		WorkspaceProject addedProject = listener.projectsAdded.get(0);
		assertEquals("Project name should match", "test-project", addedProject.getName());
		assertEquals("Project path should match", projectDir.getAbsolutePath(), addedProject.getPath());
	}

	@Test
	public void testProjectRemovedNotification() {
		// Add a project first
		File projectDir = new File(tempWorkspaceDir, "test-project");
		projectDir.mkdir();
		model.addProject("test-project", projectDir.getAbsolutePath());
		listener.clear();

		// Remove the project
		boolean removed = model.removeProject("test-project");

		assertTrue("Project should be removed", removed);
		assertEquals("Should have one project removed event", 1, listener.projectsRemoved.size());

		WorkspaceProject removedProject = listener.projectsRemoved.get(0);
		assertEquals("Project name should match", "test-project", removedProject.getName());
		assertEquals("Project path should match", projectDir.getAbsolutePath(), removedProject.getPath());
	}

	@Test
	public void testNoNotificationWhenProjectAddFails() {
		// Add a project
		File projectDir = new File(tempWorkspaceDir, "test-project");
		projectDir.mkdir();
		model.addProject("test-project", projectDir.getAbsolutePath());
		listener.clear();

		// Try to add same project again (should fail)
		boolean added = model.addProject("test-project", projectDir.getAbsolutePath());

		assertFalse("Adding duplicate project should fail", added);
		assertEquals("Should have no project added events", 0, listener.projectsAdded.size());
	}

	@Test
	public void testNoNotificationWhenProjectRemoveFails() {
		// Try to remove non-existent project
		boolean removed = model.removeProject("non-existent");

		assertFalse("Removing non-existent project should fail", removed);
		assertEquals("Should have no project removed events", 0, listener.projectsRemoved.size());
	}

	@Test
	public void testRemoveListener() {
		// Remove the listener
		model.removeListener(listener);

		// Add a project
		File projectDir = new File(tempWorkspaceDir, "test-project");
		projectDir.mkdir();
		model.addProject("test-project", projectDir.getAbsolutePath());

		// Verify listener was NOT notified
		assertEquals("Should have no project added events after removal",
				0, listener.projectsAdded.size());
	}

	@Test
	public void testMultipleListeners() {
		TestListener listener2 = new TestListener();
		model.addListener(listener2);

		// Add a project
		File projectDir = new File(tempWorkspaceDir, "test-project");
		projectDir.mkdir();
		model.addProject("test-project", projectDir.getAbsolutePath());

		// Verify both listeners were notified
		assertEquals("First listener should receive event", 1, listener.projectsAdded.size());
		assertEquals("Second listener should receive event", 1, listener2.projectsAdded.size());
	}

	/**
	 * Test listener implementation that records all events.
	 */
	private static class TestListener implements WorkspaceModelListener {
		List<StateChange> stateChanges = new ArrayList<>();
		List<WorkspaceProject> projectsAdded = new ArrayList<>();
		List<WorkspaceProject> projectsRemoved = new ArrayList<>();

		@Override
		public void initializationStateChanged(int oldState, int newState) {
			stateChanges.add(new StateChange(oldState, newState));
		}

		@Override
		public void projectAdded(WorkspaceProject project) {
			projectsAdded.add(project);
		}

		@Override
		public void projectRemoved(WorkspaceProject project) {
			projectsRemoved.add(project);
		}

		@Override
		public void fileDiagnosticsChanged(String filePath, org.jboss.tools.javac.ls.api.dao.DiagnosticList diagnostics) {
			// No-op for this test
		}

		void clear() {
			stateChanges.clear();
			projectsAdded.clear();
			projectsRemoved.clear();
		}
	}

	private static class StateChange {
		final int oldState;
		final int newState;

		StateChange(int oldState, int newState) {
			this.oldState = oldState;
			this.newState = newState;
		}
	}
}
