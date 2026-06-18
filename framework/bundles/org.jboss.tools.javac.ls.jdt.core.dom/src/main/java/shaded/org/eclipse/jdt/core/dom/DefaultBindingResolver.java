/*******************************************************************************
 * Copyright (c) 2026 Red Hat, Inc. Distributed under license by Red Hat, Inc.
 * All rights reserved. This program is made available under the terms of the
 * Eclipse Public License v2.0 which accompanies this distribution, and is
 * available at http://www.eclipse.org/legal/epl-v20.html
 *
 * Contributors: Red Hat, Inc.
 ******************************************************************************/
package shaded.org.eclipse.jdt.core.dom;


/**
 * Stub implementation of binding resolver for javac-ls.
 * The full implementation requires JDT compiler internals.
 */
class DefaultBindingResolver extends AbstractBindingResolver {

	boolean isRecoveringBindings = false;

	/**
	 * Stub class for binding tables.
	 */
	static class BindingTables {
		// Stub - would contain binding caches in full implementation
	}

	DefaultBindingResolver() {
		super();
	}

	/**
	 * Constructor accepting parameters from original JDT implementation.
	 * Parameters are ignored in this stub implementation.
	 */
	DefaultBindingResolver(Object scope, Object owner, BindingTables tables, boolean flag1, boolean flag2) {
		super();
	}
}
