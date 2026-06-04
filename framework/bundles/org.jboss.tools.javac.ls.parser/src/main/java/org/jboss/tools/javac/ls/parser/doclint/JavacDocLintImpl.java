/*******************************************************************************
 * Copyright (c) 2025 Red Hat, Inc. and others.
 *
 * This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License 2.0
 * which accompanies this distribution, and is available at
 * https://www.eclipse.org/legal/epl-2.0/
 *
 * SPDX-License-Identifier: EPL-2.0
 *
 * Contributors:
 *     Red Hat, Inc. - initial API and implementation
 *******************************************************************************/
package org.jboss.tools.javac.ls.parser.doclint;

import shaded.com.sun.source.util.JavacTask;
import shaded.com.sun.tools.doclint.DocLint;

/**
 * Service provider implementation that delegates to the shaded doclint library.
 * This class is registered via META-INF/services to provide javadoc validation.
 */
public class JavacDocLintImpl extends DocLint {
	private shaded.jdk.javadoc.internal.doclint.DocLint nameOptionDelegate = new shaded.jdk.javadoc.internal.doclint.DocLint();

	@Override
	public String getName() {
		return nameOptionDelegate.getName();
	}

	@Override
	public boolean isValidOption(String option) {
		return nameOptionDelegate.isValidOption(option);
	}

	@Override
	public void init(JavacTask task, String... options) {
		// Create new instance every time to avoid state contamination
		new shaded.jdk.javadoc.internal.doclint.DocLint().init(task, options);
	}
}
