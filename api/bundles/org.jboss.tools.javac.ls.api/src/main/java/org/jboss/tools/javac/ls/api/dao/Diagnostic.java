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
 * Represents a diagnostic (error, warning, or information message) for a source file.
 */
public class Diagnostic {

	/**
	 * Severity constants
	 */
	public static final int ERROR = 1;
	public static final int WARNING = 2;
	public static final int INFO = 3;

	private int severity;
	private String message;
	private String filePath;
	private int lineNumber;
	private int columnNumber;
	private int startPosition;
	private int endPosition;
	private String code;

	public Diagnostic() {
	}

	public Diagnostic(int severity, String message, String filePath, int lineNumber, int columnNumber) {
		this.severity = severity;
		this.message = message;
		this.filePath = filePath;
		this.lineNumber = lineNumber;
		this.columnNumber = columnNumber;
	}

	public int getSeverity() {
		return severity;
	}

	public void setSeverity(int severity) {
		this.severity = severity;
	}

	public String getMessage() {
		return message;
	}

	public void setMessage(String message) {
		this.message = message;
	}

	public String getFilePath() {
		return filePath;
	}

	public void setFilePath(String filePath) {
		this.filePath = filePath;
	}

	public int getLineNumber() {
		return lineNumber;
	}

	public void setLineNumber(int lineNumber) {
		this.lineNumber = lineNumber;
	}

	public int getColumnNumber() {
		return columnNumber;
	}

	public void setColumnNumber(int columnNumber) {
		this.columnNumber = columnNumber;
	}

	public int getStartPosition() {
		return startPosition;
	}

	public void setStartPosition(int startPosition) {
		this.startPosition = startPosition;
	}

	public int getEndPosition() {
		return endPosition;
	}

	public void setEndPosition(int endPosition) {
		this.endPosition = endPosition;
	}

	public String getCode() {
		return code;
	}

	public void setCode(String code) {
		this.code = code;
	}

	public String getSeverityString() {
		switch (severity) {
			case ERROR: return "ERROR";
			case WARNING: return "WARNING";
			case INFO: return "INFO";
			default: return "UNKNOWN";
		}
	}

	@Override
	public String toString() {
		return String.format("%s [%s:%d:%d] %s",
			getSeverityString(), filePath, lineNumber, columnNumber, message);
	}
}
