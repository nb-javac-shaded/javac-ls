/*******************************************************************************
 * Copyright (c) 2026 Red Hat, Inc. Distributed under license by Red Hat, Inc.
 * All rights reserved. This program is made available under the terms of the
 * Eclipse Public License v2.0 which accompanies this distribution, and is
 * available at http://www.eclipse.org/legal/epl-v20.html
 *
 * Contributors: Red Hat, Inc.
 ******************************************************************************/
package shaded.org.eclipse.jdt.core;

/**
 * Constants extracted from JavaCore for use in DOM classes.
 */
public class JavaCoreConstants {

	/**
	 * Java version constants - these represent compliance levels.
	 */
	public static final String VERSION_1_1 = "1.1"; //$NON-NLS-1$
	public static final String VERSION_1_2 = "1.2"; //$NON-NLS-1$
	public static final String VERSION_1_3 = "1.3"; //$NON-NLS-1$
	public static final String VERSION_1_4 = "1.4"; //$NON-NLS-1$
	public static final String VERSION_1_5 = "1.5"; //$NON-NLS-1$
	public static final String VERSION_1_6 = "1.6"; //$NON-NLS-1$
	public static final String VERSION_1_7 = "1.7"; //$NON-NLS-1$
	public static final String VERSION_1_8 = "1.8"; //$NON-NLS-1$
	public static final String VERSION_9 = "9"; //$NON-NLS-1$
	public static final String VERSION_10 = "10"; //$NON-NLS-1$
	public static final String VERSION_11 = "11"; //$NON-NLS-1$
	public static final String VERSION_12 = "12"; //$NON-NLS-1$
	public static final String VERSION_13 = "13"; //$NON-NLS-1$
	public static final String VERSION_14 = "14"; //$NON-NLS-1$
	public static final String VERSION_15 = "15"; //$NON-NLS-1$
	public static final String VERSION_16 = "16"; //$NON-NLS-1$
	public static final String VERSION_17 = "17"; //$NON-NLS-1$
	public static final String VERSION_18 = "18"; //$NON-NLS-1$
	public static final String VERSION_19 = "19"; //$NON-NLS-1$
	public static final String VERSION_20 = "20"; //$NON-NLS-1$
	public static final String VERSION_21 = "21"; //$NON-NLS-1$
	public static final String VERSION_22 = "22"; //$NON-NLS-1$
	public static final String VERSION_23 = "23"; //$NON-NLS-1$
	public static final String VERSION_24 = "24"; //$NON-NLS-1$
	public static final String VERSION_25 = "25"; //$NON-NLS-1$
	public static final String VERSION_26 = "26"; //$NON-NLS-1$

	/**
	 * Compiler option constants
	 */
	public static final String COMPILER_SOURCE = "shaded.org.eclipse.jdt.core.compiler.source"; //$NON-NLS-1$
	public static final String COMPILER_COMPLIANCE = "shaded.org.eclipse.jdt.core.compiler.compliance"; //$NON-NLS-1$
	public static final String COMPILER_CODEGEN_TARGET_PLATFORM = "shaded.org.eclipse.jdt.core.compiler.codegen.targetPlatform"; //$NON-NLS-1$
	public static final String COMPILER_DOC_COMMENT_SUPPORT = "shaded.org.eclipse.jdt.core.compiler.doc.comment.support"; //$NON-NLS-1$
	public static final String COMPILER_PB_ENABLE_PREVIEW_FEATURES = "shaded.org.eclipse.jdt.core.compiler.problem.enablePreviewFeatures"; //$NON-NLS-1$

	/**
	 * Option value constants
	 */
	public static final String ENABLED = "enabled"; //$NON-NLS-1$
	public static final String DISABLED = "disabled"; //$NON-NLS-1$

	/**
	 * Returns the latest supported Java version.
	 */
	public static String latestSupportedJavaVersion() {
		return VERSION_26;
	}

	/**
	 * Returns default compiler options.
	 */
	public static java.util.Map<String, String> getDefaultOptions() {
		java.util.Map<String, String> options = new java.util.HashMap<>();
		options.put(COMPILER_SOURCE, VERSION_1_8);
		options.put(COMPILER_COMPLIANCE, VERSION_1_8);
		options.put(COMPILER_PB_ENABLE_PREVIEW_FEATURES, DISABLED);
		return options;
	}

	private JavaCoreConstants() {
		// No instances
	}
}
