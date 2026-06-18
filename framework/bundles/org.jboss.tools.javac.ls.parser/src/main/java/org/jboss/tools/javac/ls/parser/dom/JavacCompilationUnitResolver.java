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
package org.jboss.tools.javac.ls.parser.dom;

import shaded.com.sun.tools.javac.api.MultiTaskListener;
import shaded.com.sun.tools.javac.util.Context;

/**
 * Provides utilities for JavacBindingResolver.
 */
public class JavacCompilationUnitResolver {

	/**
	 * Cleans up context after analysis (nothing left to process)
	 * but keeps it usable by bindings by keeping filemanager available.
	 */
	public static void cleanup(Context context) {
		MultiTaskListener.instance(context).clear();
		// based on shaded.com.sun.tools.javac.api.JavacTaskImpl.cleanup()
		var javac = shaded.com.sun.tools.javac.main.JavaCompiler.instance(context);
		if (javac != null) {
			javac.close();
		}
	}
}
