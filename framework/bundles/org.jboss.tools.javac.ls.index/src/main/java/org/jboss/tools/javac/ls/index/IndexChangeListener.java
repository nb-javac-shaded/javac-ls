/*******************************************************************************
 * Copyright (c) 2026 Red Hat, Inc. and others.
 *
 * This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License 2.0
 * which accompanies this distribution, and is available at
 * https://www.eclipse.org/legal/epl-2.0/
 *
 * SPDX-License-Identifier: EPL-2.0
 *******************************************************************************/
package org.jboss.tools.javac.ls.index;

/**
 * Listener interface for receiving notifications when the index changes.
 */
public interface IndexChangeListener {
	/**
	 * Called when the index has been modified.
	 * @param event details about what changed
	 */
	void indexChanged(IndexChangeEvent event);
}
