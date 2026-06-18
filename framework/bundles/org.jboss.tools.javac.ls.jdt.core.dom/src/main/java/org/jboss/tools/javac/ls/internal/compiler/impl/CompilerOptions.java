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
package org.jboss.tools.javac.ls.internal.compiler.impl;

import java.util.Map;

import shaded.org.eclipse.jdt.internal.compiler.classfmt.ClassFileConstants;

/**
 * Minimal CompilerOptions to avoid Eclipse JDT dependency.
 * Contains only the constants and basic functionality needed by javac-ls.
 */
public class CompilerOptions {

	// Version constants
	public static final String VERSION_1_1 = "1.1";
	public static final String VERSION_1_2 = "1.2";
	public static final String VERSION_1_3 = "1.3";
	public static final String VERSION_1_4 = "1.4";
	public static final String VERSION_1_5 = "1.5";
	public static final String VERSION_1_6 = "1.6";
	public static final String VERSION_1_7 = "1.7";
	public static final String VERSION_1_8 = "1.8";
	public static final String VERSION_9 = "9";
	public static final String VERSION_10 = "10";
	public static final String VERSION_11 = "11";
	public static final String VERSION_12 = "12";
	public static final String VERSION_13 = "13";
	public static final String VERSION_14 = "14";
	public static final String VERSION_15 = "15";
	public static final String VERSION_16 = "16";
	public static final String VERSION_17 = "17";
	public static final String VERSION_18 = "18";
	public static final String VERSION_19 = "19";
	public static final String VERSION_20 = "20";
	public static final String VERSION_21 = "21";

	public long complianceLevel;
	private Map<String, String> options;

	public CompilerOptions() {
		this.complianceLevel = ClassFileConstants.JDK17; // default to Java 17
	}

	public CompilerOptions(Map<String, String> options) {
		this.options = options;
		this.complianceLevel = versionToJDKLevel(
			options != null ? options.get("org.eclipse.jdt.core.compiler.compliance") : null
		);
	}

	public Map<String, String> getMap() {
		return options;
	}

	public String get(String key) {
		return options != null ? options.get(key) : null;
	}

	/**
	 * Convert version string to ClassFileConstants JDK level.
	 * Supports both old format (1.8) and new format (9, 17, etc.)
	 */
	private long versionToJDKLevel(String version) {
		if (version == null || version.isEmpty()) {
			return ClassFileConstants.JDK17; // default
		}

		switch (version) {
			case "1.1":
				return ClassFileConstants.JDK1_1;
			case "1.2":
				return ClassFileConstants.JDK1_2;
			case "1.3":
				return ClassFileConstants.JDK1_3;
			case "1.4":
				return ClassFileConstants.JDK1_4;
			case "1.5":
			case "5":
				return ClassFileConstants.JDK1_5;
			case "1.6":
			case "6":
				return ClassFileConstants.JDK1_6;
			case "1.7":
			case "7":
				return ClassFileConstants.JDK1_7;
			case "1.8":
			case "8":
				return ClassFileConstants.JDK1_8;
			case "9":
				return ClassFileConstants.JDK9;
			case "10":
				return ClassFileConstants.JDK10;
			case "11":
				return ClassFileConstants.JDK11;
			case "12":
				return ClassFileConstants.JDK12;
			case "13":
				return ClassFileConstants.JDK13;
			case "14":
				return ClassFileConstants.JDK14;
			case "15":
				return ClassFileConstants.JDK15;
			case "16":
				return ClassFileConstants.JDK16;
			case "17":
				return ClassFileConstants.JDK17;
			case "18":
				return ClassFileConstants.JDK18;
			case "19":
				return ClassFileConstants.JDK19;
			case "20":
				return ClassFileConstants.JDK20;
			case "21":
				return ClassFileConstants.JDK21;
			default:
				return ClassFileConstants.JDK17; // default to Java 17 for unknown versions
		}
	}
}
