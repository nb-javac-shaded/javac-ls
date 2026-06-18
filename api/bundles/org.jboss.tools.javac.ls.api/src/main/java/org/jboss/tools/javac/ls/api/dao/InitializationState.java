/*******************************************************************************
 * Copyright (c) 2026 Red Hat, Inc. Distributed under license by Red Hat, Inc.
 * All rights reserved. This program is made available under the terms of the
 * Eclipse Public License v2.0 which accompanies this distribution, and is
 * available at http://www.eclipse.org/legal/epl-v20.html
 *
 * Contributors: Red Hat, Inc.
 ******************************************************************************/
package org.jboss.tools.javac.ls.api.dao;

/**
 * Represents the initialization state of the workspace.
 */
public class InitializationState {

	/**
	 * Initialization has not started yet.
	 */
	public static final int STATE_NOT_STARTED = 0;

	/**
	 * Loading cached workspace data (projects, index).
	 */
	public static final int STATE_LOADING_CACHE = 1;

	/**
	 * Indexing workspace files (parsing and populating index).
	 */
	public static final int STATE_INDEXING = 2;

	/**
	 * Initialization complete, workspace is ready for requests.
	 */
	public static final int STATE_READY = 3;

	/**
	 * The current state. One of:
	 * <ul>
	 * <li><code>STATE_NOT_STARTED</code></li>
	 * <li><code>STATE_LOADING_CACHE</code></li>
	 * <li><code>STATE_INDEXING</code></li>
	 * <li><code>STATE_READY</code></li>
	 * </ul>
	 */
	private int state;

	/**
	 * Optional human-readable message about the current state.
	 */
	private String message;

	/**
	 * No-arg constructor for JSON serialization.
	 */
	public InitializationState() {
		this.state = STATE_NOT_STARTED;
		this.message = null;
	}

	/**
	 * Create an initialization state.
	 *
	 * @param state the state constant
	 */
	public InitializationState(int state) {
		this.state = state;
		this.message = null;
	}

	/**
	 * Create an initialization state with a message.
	 *
	 * @param state the state constant
	 * @param message optional message
	 */
	public InitializationState(int state, String message) {
		this.state = state;
		this.message = message;
	}

	public int getState() {
		return state;
	}

	public void setState(int state) {
		this.state = state;
	}

	public String getMessage() {
		return message;
	}

	public void setMessage(String message) {
		this.message = message;
	}

	/**
	 * Check if the state represents completion (READY).
	 *
	 * @return true if state is READY
	 */
	public boolean isReady() {
		return state == STATE_READY;
	}

	/**
	 * Get a human-readable string for the state constant.
	 *
	 * @param state the state constant
	 * @return string representation
	 */
	public static String stateToString(int state) {
		switch (state) {
			case STATE_NOT_STARTED:
				return "NOT_STARTED";
			case STATE_LOADING_CACHE:
				return "LOADING_CACHE";
			case STATE_INDEXING:
				return "INDEXING";
			case STATE_READY:
				return "READY";
			default:
				return "UNKNOWN(" + state + ")";
		}
	}

	@Override
	public String toString() {
		StringBuilder sb = new StringBuilder("InitializationState[");
		sb.append(stateToString(state));
		if (message != null) {
			sb.append(": ").append(message);
		}
		sb.append("]");
		return sb.toString();
	}
}
