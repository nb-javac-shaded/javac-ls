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
 * No-op implementation of binding resolver that does no resolving.
 * This is the default resolver when bindings are not requested.
 */
class NoOpBindingResolver extends AbstractBindingResolver {

	NoOpBindingResolver() {
		super();
	}
}
