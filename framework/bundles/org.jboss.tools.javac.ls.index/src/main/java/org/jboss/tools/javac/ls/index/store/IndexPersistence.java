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
package org.jboss.tools.javac.ls.index.store;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.jboss.tools.javac.ls.index.model.FieldDeclarationEntry;
import org.jboss.tools.javac.ls.index.model.MethodDeclarationEntry;
import org.jboss.tools.javac.ls.index.model.ReferenceEntry;
import org.jboss.tools.javac.ls.index.model.TypeDeclarationEntry;

/**
 * Abstraction for saving/loading index data.
 * Implementations decide format (JSON, binary, etc.) and location.
 */
public interface IndexPersistence {

	/**
	 * Save type declarations to persistent storage.
	 * @param types map of qualified name to type declaration
	 * @throws IOException if save fails
	 */
	void saveTypes(Map<String, TypeDeclarationEntry> types) throws IOException;

	/**
	 * Load type declarations from persistent storage.
	 * @return map of qualified name to type declaration
	 * @throws IOException if load fails
	 */
	Map<String, TypeDeclarationEntry> loadTypes() throws IOException;

	/**
	 * Save subtype hierarchy relationships.
	 * @param subtypes map of supertype qualified name to set of subtype qualified names
	 * @throws IOException if save fails
	 */
	void saveSubtypes(Map<String, Set<String>> subtypes) throws IOException;

	/**
	 * Load subtype hierarchy relationships.
	 * @return map of supertype qualified name to set of subtype qualified names
	 * @throws IOException if load fails
	 */
	Map<String, Set<String>> loadSubtypes() throws IOException;

	/**
	 * Save implementor relationships (interface to implementing classes).
	 * @param implementors map of interface qualified name to set of implementor qualified names
	 * @throws IOException if save fails
	 */
	void saveImplementors(Map<String, Set<String>> implementors) throws IOException;

	/**
	 * Load implementor relationships.
	 * @return map of interface qualified name to set of implementor qualified names
	 * @throws IOException if load fails
	 */
	Map<String, Set<String>> loadImplementors() throws IOException;

	/**
	 * Save type references.
	 * @param typeReferences map of qualified name to list of reference locations
	 * @throws IOException if save fails
	 */
	void saveTypeReferences(Map<String, List<ReferenceEntry>> typeReferences) throws IOException;

	/**
	 * Load type references.
	 * @return map of qualified name to list of reference locations
	 * @throws IOException if load fails
	 */
	Map<String, List<ReferenceEntry>> loadTypeReferences() throws IOException;

	/**
	 * Save name references (simple names for find usages).
	 * @param nameReferences map of simple name to list of reference locations
	 * @throws IOException if save fails
	 */
	void saveNameReferences(Map<String, List<ReferenceEntry>> nameReferences) throws IOException;

	/**
	 * Load name references.
	 * @return map of simple name to list of reference locations
	 * @throws IOException if load fails
	 */
	Map<String, List<ReferenceEntry>> loadNameReferences() throws IOException;

	/**
	 * Save method declarations.
	 * @param methods map of signature key to method declaration
	 * @throws IOException if save fails
	 */
	void saveMethods(Map<String, MethodDeclarationEntry> methods) throws IOException;

	/**
	 * Load method declarations.
	 * @return map of signature key to method declaration
	 * @throws IOException if load fails
	 */
	Map<String, MethodDeclarationEntry> loadMethods() throws IOException;

	/**
	 * Save field declarations.
	 * @param fields map of field key to field declaration
	 * @throws IOException if save fails
	 */
	void saveFields(Map<String, FieldDeclarationEntry> fields) throws IOException;

	/**
	 * Load field declarations.
	 * @return map of field key to field declaration
	 * @throws IOException if load fails
	 */
	Map<String, FieldDeclarationEntry> loadFields() throws IOException;

	/**
	 * Save file-to-types mapping for incremental updates.
	 * @param fileToDeclaredTypes map of file path to set of declared type qualified names
	 * @throws IOException if save fails
	 */
	void saveFileToDeclaredTypes(Map<Path, Set<String>> fileToDeclaredTypes) throws IOException;

	/**
	 * Load file-to-types mapping.
	 * @return map of file path to set of declared type qualified names
	 * @throws IOException if load fails
	 */
	Map<Path, Set<String>> loadFileToDeclaredTypes() throws IOException;

	/**
	 * Check if persisted data exists and is valid.
	 * @return true if persistent storage exists
	 */
	boolean exists();

	/**
	 * Get timestamp of persisted data.
	 * @return timestamp in milliseconds, or 0 if doesn't exist
	 */
	long getTimestamp();

	/**
	 * Save file timestamps for change detection.
	 * @param fileTimestamps map of file path to last modification timestamp
	 * @throws IOException if save fails
	 */
	void saveFileTimestamps(Map<Path, Long> fileTimestamps) throws IOException;

	/**
	 * Load file timestamps.
	 * @return map of file path to last modification timestamp
	 * @throws IOException if load fails
	 */
	Map<Path, Long> loadFileTimestamps() throws IOException;

	/**
	 * Save file-to-type-references mapping for proper cleanup on re-index.
	 * @param fileTypeReferences map of file path to list of type references from that file
	 * @throws IOException if save fails
	 */
	void saveFileTypeReferences(Map<Path, List<ReferenceEntry>> fileTypeReferences) throws IOException;

	/**
	 * Load file-to-type-references mapping.
	 * @return map of file path to list of type references from that file
	 * @throws IOException if load fails
	 */
	Map<Path, List<ReferenceEntry>> loadFileTypeReferences() throws IOException;

	/**
	 * Save file-to-name-references mapping for proper cleanup on re-index.
	 * @param fileNameReferences map of file path to list of name references from that file
	 * @throws IOException if save fails
	 */
	void saveFileNameReferences(Map<Path, List<ReferenceEntry>> fileNameReferences) throws IOException;

	/**
	 * Load file-to-name-references mapping.
	 * @return map of file path to list of name references from that file
	 * @throws IOException if load fails
	 */
	Map<Path, List<ReferenceEntry>> loadFileNameReferences() throws IOException;
}
