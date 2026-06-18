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
 * Constants extracted from CompilationUnitResolver for use in ASTParser.
 */
public class CompilationUnitResolverConstants {

	/**
	 * Bit flags for controlling parser behavior.
	 */
	public static final int RESOLVE_BINDING = 0x1;
	public static final int PARTIAL = 0x2;
	public static final int STATEMENT_RECOVERY = 0x4;
	public static final int IGNORE_METHOD_BODIES = 0x8;
	public static final int BINDING_RECOVERY = 0x10;
	public static final int INCLUDE_RUNNING_VM_BOOTCLASSPATH = 0x20;
	public static final int FORCE_PROBLEM_DETECTION = 0x40;

	/**
	 * Simple dynamic int array for internal use.
	 */
	static class IntArrayList {
		public int[] list = new int[5];
		public int length = 0;

		public void add(int i) {
			if (this.list.length == this.length) {
				System.arraycopy(this.list, 0, this.list = new int[this.length * 2], 0, this.length);
			}
			this.list[this.length++] = i;
		}
	}

	private CompilationUnitResolverConstants() {
		// No instances
	}
}
