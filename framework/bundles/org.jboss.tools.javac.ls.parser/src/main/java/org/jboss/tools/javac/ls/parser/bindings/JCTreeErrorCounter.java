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
package org.jboss.tools.javac.ls.parser.bindings;

import java.util.concurrent.atomic.AtomicInteger;

import shaded.com.sun.tools.javac.code.Type;
import shaded.com.sun.tools.javac.code.Type.ErrorType;
import shaded.com.sun.tools.javac.tree.JCTree;
import shaded.com.sun.tools.javac.tree.JCTree.JCErroneous;
import shaded.com.sun.tools.javac.tree.TreeScanner;

/**
 * Visitor that traverses a JCTree and counts error indicators to measure parse quality.
 *
 * Error indicators include:
 * - ErrorType: unresolved type references
 * - JCErroneous: malformed syntax nodes
 */
public class JCTreeErrorCounter extends TreeScanner {

	private final AtomicInteger errorTypeCount = new AtomicInteger(0);
	private final AtomicInteger erroneousNodeCount = new AtomicInteger(0);
	private final AtomicInteger totalTypeCount = new AtomicInteger(0);
	private final AtomicInteger totalNodeCount = new AtomicInteger(0);

	@Override
	public void scan(JCTree tree) {
		if (tree != null) {
			totalNodeCount.incrementAndGet();

			// Check for erroneous nodes
			if (tree instanceof JCErroneous) {
				erroneousNodeCount.incrementAndGet();
			}

			// Check type if present
			if (tree.type != null) {
				totalTypeCount.incrementAndGet();
				checkType(tree.type);
			}
		}
		super.scan(tree);
	}

	private void checkType(Type type) {
		if (type instanceof ErrorType) {
			errorTypeCount.incrementAndGet();
		}
	}

	public int getErrorTypeCount() {
		return errorTypeCount.get();
	}

	public int getErroneousNodeCount() {
		return erroneousNodeCount.get();
	}

	public int getTotalTypeCount() {
		return totalTypeCount.get();
	}

	public int getTotalNodeCount() {
		return totalNodeCount.get();
	}

	public int getTotalErrorCount() {
		return errorTypeCount.get() + erroneousNodeCount.get();
	}

	public double getErrorPercentage() {
		int total = totalNodeCount.get();
		if (total == 0) {
			return 0.0;
		}
		return (100.0 * getTotalErrorCount()) / total;
	}

	@Override
	public String toString() {
		return String.format(
			"ErrorTypes=%d, ErroneousNodes=%d, TotalErrors=%d, TotalNodes=%d, ErrorRate=%.2f%%",
			errorTypeCount.get(),
			erroneousNodeCount.get(),
			getTotalErrorCount(),
			totalNodeCount.get(),
			getErrorPercentage()
		);
	}

	/**
	 * Scan a tree and return error statistics.
	 */
	public static JCTreeErrorCounter analyze(JCTree tree) {
		JCTreeErrorCounter counter = new JCTreeErrorCounter();
		counter.scan(tree);
		return counter;
	}
}
