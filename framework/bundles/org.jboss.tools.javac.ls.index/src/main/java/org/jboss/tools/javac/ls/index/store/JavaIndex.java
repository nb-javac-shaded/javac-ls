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
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

import org.jboss.tools.javac.ls.index.IndexChangeEvent;
import org.jboss.tools.javac.ls.index.IndexChangeEvent.ChangeKind;
import org.jboss.tools.javac.ls.index.IndexChangeListener;
import org.jboss.tools.javac.ls.index.model.FieldDeclarationEntry;
import org.jboss.tools.javac.ls.index.model.MethodDeclarationEntry;
import org.jboss.tools.javac.ls.index.model.ReferenceEntry;
import org.jboss.tools.javac.ls.index.model.TypeDeclarationEntry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * In-memory index of Java source code declarations and references.
 * Thread-safe for concurrent queries and updates.
 */
public class JavaIndex {
	private static final Logger LOG = LoggerFactory.getLogger(JavaIndex.class);

	// Declarations (what's defined in the code)
	private final Map<String, TypeDeclarationEntry> types = new ConcurrentHashMap<>();
	private final Map<String, MethodDeclarationEntry> methods = new ConcurrentHashMap<>();
	private final Map<String, FieldDeclarationEntry> fields = new ConcurrentHashMap<>();

	// Type hierarchy (for fast queries)
	private final Map<String, Set<String>> subtypes = new ConcurrentHashMap<>();
	private final Map<String, Set<String>> implementors = new ConcurrentHashMap<>();

	// References (where things are used)
	private final Map<String, List<ReferenceEntry>> typeReferences = new ConcurrentHashMap<>();
	private final Map<String, List<ReferenceEntry>> nameReferences = new ConcurrentHashMap<>();

	// File tracking (for incremental updates)
	private final Map<Path, Set<String>> fileToDeclaredTypes = new ConcurrentHashMap<>();
	private final Map<Path, Long> fileTimestamps = new ConcurrentHashMap<>();

	// Track which references came from which file (for proper cleanup on re-index)
	private final Map<Path, List<ReferenceEntry>> fileTypeReferences = new ConcurrentHashMap<>();
	private final Map<Path, List<ReferenceEntry>> fileNameReferences = new ConcurrentHashMap<>();

	// Diagnostics (compilation errors and warnings per file)
	private final Map<Path, List<String>> fileDiagnostics = new ConcurrentHashMap<>();

	// Listeners
	private final List<IndexChangeListener> listeners = new CopyOnWriteArrayList<>();

	/**
	 * Add a type declaration to the index.
	 */
	public void addType(TypeDeclarationEntry type) {
		types.put(type.getQualifiedName(), type);

		// Update hierarchy indexes
		if (type.getSuperclass() != null && !type.getSuperclass().isEmpty()) {
			subtypes.computeIfAbsent(type.getSuperclass(), k -> ConcurrentHashMap.newKeySet())
					.add(type.getQualifiedName());
		}

		for (String iface : type.getInterfaces()) {
			implementors.computeIfAbsent(iface, k -> ConcurrentHashMap.newKeySet())
					.add(type.getQualifiedName());
		}
	}

	/**
	 * Add a method declaration to the index.
	 */
	public void addMethod(MethodDeclarationEntry method) {
		methods.put(method.getSignatureKey(), method);
	}

	/**
	 * Add a field declaration to the index.
	 */
	public void addField(FieldDeclarationEntry field) {
		fields.put(field.getFieldKey(), field);
	}

	/**
	 * Add a type reference to the index.
	 * Thread-safe for concurrent additions.
	 *
	 * @param qualifiedName the fully qualified type name being referenced
	 * @param reference the reference details
	 * @param sourceFile the file containing this reference
	 */
	public void addTypeReference(String qualifiedName, ReferenceEntry reference, Path sourceFile) {
		typeReferences.computeIfAbsent(qualifiedName, k -> Collections.synchronizedList(new ArrayList<>()))
				.add(reference);
		fileTypeReferences.computeIfAbsent(sourceFile, k -> Collections.synchronizedList(new ArrayList<>()))
				.add(reference);
	}

	/**
	 * Add a name reference to the index (for find usages, rename refactoring).
	 * Thread-safe for concurrent additions.
	 *
	 * @param name the simple name being referenced
	 * @param reference the reference details
	 * @param sourceFile the file containing this reference
	 */
	public void addNameReference(String name, ReferenceEntry reference, Path sourceFile) {
		nameReferences.computeIfAbsent(name, k -> Collections.synchronizedList(new ArrayList<>()))
				.add(reference);
		fileNameReferences.computeIfAbsent(sourceFile, k -> Collections.synchronizedList(new ArrayList<>()))
				.add(reference);
	}

	/**
	 * Track that a file declares certain types.
	 * Records the file's current modification timestamp for change detection.
	 */
	public void trackFileDeclaredTypes(Path file, Set<String> declaredTypes) {
		fileToDeclaredTypes.put(file, new HashSet<>(declaredTypes));
		// Store the file's actual modification time for change detection
		long timestamp = file.toFile().exists() ? file.toFile().lastModified() : System.currentTimeMillis();
		fileTimestamps.put(file, timestamp);
	}

	/**
	 * Store diagnostics (compilation errors/warnings) for a file.
	 * Diagnostics are serialized as strings to avoid keeping full IProblem objects in memory.
	 *
	 * @param file the source file
	 * @param diagnosticStrings list of diagnostic strings (JSON or formatted text)
	 */
	public void storeDiagnostics(Path file, List<String> diagnosticStrings) {
		if (diagnosticStrings == null || diagnosticStrings.isEmpty()) {
			fileDiagnostics.remove(file);
		} else {
			fileDiagnostics.put(file, new ArrayList<>(diagnosticStrings));
		}
	}

	/**
	 * Get diagnostics for a specific file.
	 *
	 * @param file the source file
	 * @return list of diagnostic strings, or empty list if none
	 */
	public List<String> getDiagnostics(Path file) {
		List<String> diags = fileDiagnostics.get(file);
		return diags != null ? new ArrayList<>(diags) : Collections.emptyList();
	}

	/**
	 * Get all files that have diagnostics.
	 *
	 * @return set of file paths with diagnostics
	 */
	public Set<Path> getFilesWithDiagnostics() {
		return new HashSet<>(fileDiagnostics.keySet());
	}

	/**
	 * Remove all index entries for a file (for incremental updates).
	 */
	public void removeFile(Path file) {
		Set<String> oldTypes = fileToDeclaredTypes.remove(file);
		if (oldTypes != null) {
			for (String qname : oldTypes) {
				TypeDeclarationEntry removed = types.remove(qname);
				if (removed != null) {
					// Remove from hierarchy indexes
					if (removed.getSuperclass() != null) {
						Set<String> subs = subtypes.get(removed.getSuperclass());
						if (subs != null) {
							subs.remove(qname);
						}
					}
					for (String iface : removed.getInterfaces()) {
						Set<String> impls = implementors.get(iface);
						if (impls != null) {
							impls.remove(qname);
						}
					}
				}

				// Remove methods and fields of this type
				methods.entrySet().removeIf(e -> e.getValue().getDeclaringType().equals(qname));
				fields.entrySet().removeIf(e -> e.getValue().getDeclaringType().equals(qname));
			}
		}

		// Remove type references FROM this file
		List<ReferenceEntry> fileTypeRefs = fileTypeReferences.remove(file);
		if (fileTypeRefs != null) {
			for (ReferenceEntry ref : fileTypeRefs) {
				// Remove this specific reference from the global type references map
				// We need to find which type name this reference belongs to
				for (List<ReferenceEntry> refs : typeReferences.values()) {
					refs.remove(ref);
				}
			}
		}

		// Remove name references FROM this file
		List<ReferenceEntry> fileNameRefs = fileNameReferences.remove(file);
		if (fileNameRefs != null) {
			for (ReferenceEntry ref : fileNameRefs) {
				// Remove this specific reference from the global name references map
				for (List<ReferenceEntry> refs : nameReferences.values()) {
					refs.remove(ref);
				}
			}
		}

		fileTimestamps.remove(file);
		fileDiagnostics.remove(file);
		fireIndexChanged(new IndexChangeEvent(file, ChangeKind.FILE_REMOVED));
	}

	/**
	 * Check if a file has been indexed.
	 *
	 * @param file the file path
	 * @return true if the file is in the index
	 */
	public boolean isFileIndexed(Path file) {
		return fileToDeclaredTypes.containsKey(file);
	}

	/**
	 * Get the types declared in an indexed file.
	 *
	 * @param file the file path
	 * @return set of qualified type names, or null if file not indexed
	 */
	public Set<String> getFileDeclaredTypes(Path file) {
		Set<String> types = fileToDeclaredTypes.get(file);
		return types != null ? new HashSet<>(types) : null;
	}

	/**
	 * Get the timestamp when a file was indexed.
	 *
	 * @param file the file path
	 * @return timestamp in milliseconds, or 0 if file not indexed
	 */
	public long getFileTimestamp(Path file) {
		return fileTimestamps.getOrDefault(file, 0L);
	}

	// ===== Query Methods =====

	/**
	 * Get type declaration by qualified name.
	 */
	public TypeDeclarationEntry getType(String qualifiedName) {
		return types.get(qualifiedName);
	}

	/**
	 * Get all type declarations.
	 */
	public Collection<TypeDeclarationEntry> getAllTypes() {
		return Collections.unmodifiableCollection(types.values());
	}

	/**
	 * Find direct subtypes of a type.
	 */
	public Collection<String> findDirectSubtypes(String typeName) {
		Set<String> subs = subtypes.get(typeName);
		return subs != null ? Collections.unmodifiableSet(subs) : Collections.emptySet();
	}

	/**
	 * Find all subtypes of a type (transitive closure).
	 */
	public Set<String> findAllSubtypes(String typeName) {
		Set<String> result = new HashSet<>();
		Queue<String> queue = new LinkedList<>();
		queue.add(typeName);

		while (!queue.isEmpty()) {
			String current = queue.poll();
			Set<String> direct = subtypes.get(current);
			if (direct != null) {
				for (String sub : direct) {
					if (result.add(sub)) {
						queue.add(sub);
					}
				}
			}
		}
		return result;
	}

	/**
	 * Find direct implementors of an interface.
	 */
	public Collection<String> findDirectImplementors(String interfaceName) {
		Set<String> impls = implementors.get(interfaceName);
		return impls != null ? Collections.unmodifiableSet(impls) : Collections.emptySet();
	}

	/**
	 * Find all implementors of an interface (transitive).
	 */
	public Set<String> findAllImplementors(String interfaceName) {
		Set<String> result = new HashSet<>();
		Queue<String> queue = new LinkedList<>();
		queue.add(interfaceName);

		while (!queue.isEmpty()) {
			String current = queue.poll();

			// Add direct implementors
			Set<String> direct = implementors.get(current);
			if (direct != null) {
				result.addAll(direct);
			}

			// Also check for interfaces that extend this interface
			Set<String> extendingInterfaces = subtypes.get(current);
			if (extendingInterfaces != null) {
				for (String extending : extendingInterfaces) {
					TypeDeclarationEntry type = types.get(extending);
					if (type != null && type.getKind() == TypeDeclarationEntry.TypeKind.INTERFACE) {
						if (!result.contains(extending) && !queue.contains(extending)) {
							queue.add(extending);
						}
					}
				}
			}
		}
		return result;
	}

	/**
	 * Find usages of a type.
	 */
	public Collection<ReferenceEntry> findTypeUsages(String qualifiedName) {
		List<ReferenceEntry> refs = typeReferences.get(qualifiedName);
		return refs != null ? Collections.unmodifiableList(refs) : Collections.emptyList();
	}

	/**
	 * Find all usages of a simple name (field, variable, method, etc.).
	 */
	public Collection<ReferenceEntry> findNameUsages(String name) {
		List<ReferenceEntry> refs = nameReferences.get(name);
		return refs != null ? Collections.unmodifiableList(refs) : Collections.emptyList();
	}

	/**
	 * Get method declaration by signature key.
	 */
	public MethodDeclarationEntry getMethod(String signatureKey) {
		return methods.get(signatureKey);
	}

	/**
	 * Find all methods in a type.
	 */
	public Collection<MethodDeclarationEntry> findMethodsInType(String typeName) {
		List<MethodDeclarationEntry> result = new ArrayList<>();
		for (MethodDeclarationEntry method : methods.values()) {
			if (method.getDeclaringType().equals(typeName)) {
				result.add(method);
			}
		}
		return result;
	}

	/**
	 * Get field declaration by field key.
	 */
	public FieldDeclarationEntry getField(String fieldKey) {
		return fields.get(fieldKey);
	}

	/**
	 * Find all fields in a type.
	 */
	public Collection<FieldDeclarationEntry> findFieldsInType(String typeName) {
		List<FieldDeclarationEntry> result = new ArrayList<>();
		for (FieldDeclarationEntry field : fields.values()) {
			if (field.getDeclaringType().equals(typeName)) {
				result.add(field);
			}
		}
		return result;
	}

	/**
	 * Get all fields in the index.
	 */
	public Map<String, FieldDeclarationEntry> getFields() {
		return Collections.unmodifiableMap(fields);
	}

	// ===== Listener Management =====

	public void addIndexChangeListener(IndexChangeListener listener) {
		listeners.add(listener);
	}

	public void removeIndexChangeListener(IndexChangeListener listener) {
		listeners.remove(listener);
	}

	public void fireIndexChanged(IndexChangeEvent event) {
		for (IndexChangeListener listener : listeners) {
			try {
				listener.indexChanged(event);
			} catch (Exception e) {
				LOG.error("Error notifying index change listener", e);
			}
		}
	}

	// ===== Persistence =====

	/**
	 * Save index to persistent storage.
	 */
	public void saveTo(IndexPersistence persistence) throws IOException {
		persistence.saveTypes(new HashMap<>(types));
		persistence.saveSubtypes(convertToHashMap(subtypes));
		persistence.saveImplementors(convertToHashMap(implementors));
		persistence.saveTypeReferences(convertToListHashMap(typeReferences));
		persistence.saveNameReferences(convertToListHashMap(nameReferences));
		persistence.saveMethods(new HashMap<>(methods));
		persistence.saveFields(new HashMap<>(fields));
		persistence.saveFileToDeclaredTypes(new HashMap<>(fileToDeclaredTypes));
		persistence.saveFileTimestamps(new HashMap<>(fileTimestamps));
		persistence.saveFileTypeReferences(convertPathListHashMap(fileTypeReferences));
		persistence.saveFileNameReferences(convertPathListHashMap(fileNameReferences));
	}

	/**
	 * Load index from persistent storage.
	 */
	public void loadFrom(IndexPersistence persistence) throws IOException {
		types.clear();
		subtypes.clear();
		implementors.clear();
		typeReferences.clear();
		nameReferences.clear();
		methods.clear();
		fields.clear();
		fileToDeclaredTypes.clear();
		fileTimestamps.clear();
		fileTypeReferences.clear();
		fileNameReferences.clear();

		Map<String, TypeDeclarationEntry> loadedTypes = persistence.loadTypes();
		if (loadedTypes != null) {
			types.putAll(loadedTypes);
		}

		Map<String, Set<String>> loadedSubtypes = persistence.loadSubtypes();
		if (loadedSubtypes != null) {
			for (Map.Entry<String, Set<String>> entry : loadedSubtypes.entrySet()) {
				subtypes.put(entry.getKey(), ConcurrentHashMap.newKeySet());
				subtypes.get(entry.getKey()).addAll(entry.getValue());
			}
		}

		Map<String, Set<String>> loadedImplementors = persistence.loadImplementors();
		if (loadedImplementors != null) {
			for (Map.Entry<String, Set<String>> entry : loadedImplementors.entrySet()) {
				implementors.put(entry.getKey(), ConcurrentHashMap.newKeySet());
				implementors.get(entry.getKey()).addAll(entry.getValue());
			}
		}

		Map<String, List<ReferenceEntry>> loadedTypeRefs = persistence.loadTypeReferences();
		if (loadedTypeRefs != null) {
			typeReferences.putAll(loadedTypeRefs);
		}

		Map<String, List<ReferenceEntry>> loadedNameRefs = persistence.loadNameReferences();
		if (loadedNameRefs != null) {
			nameReferences.putAll(loadedNameRefs);
		}

		Map<String, MethodDeclarationEntry> loadedMethods = persistence.loadMethods();
		if (loadedMethods != null) {
			methods.putAll(loadedMethods);
		}

		Map<String, FieldDeclarationEntry> loadedFields = persistence.loadFields();
		if (loadedFields != null) {
			fields.putAll(loadedFields);
		}

		Map<Path, Set<String>> loadedFileToDeclaredTypes = persistence.loadFileToDeclaredTypes();
		if (loadedFileToDeclaredTypes != null) {
			fileToDeclaredTypes.putAll(loadedFileToDeclaredTypes);
		}

		Map<Path, Long> loadedFileTimestamps = persistence.loadFileTimestamps();
		if (loadedFileTimestamps != null) {
			fileTimestamps.putAll(loadedFileTimestamps);
		}

		Map<Path, List<ReferenceEntry>> loadedFileTypeRefs = persistence.loadFileTypeReferences();
		if (loadedFileTypeRefs != null) {
			fileTypeReferences.putAll(loadedFileTypeRefs);
		}

		Map<Path, List<ReferenceEntry>> loadedFileNameRefs = persistence.loadFileNameReferences();
		if (loadedFileNameRefs != null) {
			fileNameReferences.putAll(loadedFileNameRefs);
		}

		LOG.info("Loaded index: {} types, {} methods, {} fields",
				types.size(), methods.size(), fields.size());
	}

	private Map<String, Set<String>> convertToHashMap(Map<String, Set<String>> source) {
		Map<String, Set<String>> result = new HashMap<>();
		for (Map.Entry<String, Set<String>> entry : source.entrySet()) {
			result.put(entry.getKey(), new HashSet<>(entry.getValue()));
		}
		return result;
	}

	private Map<String, List<ReferenceEntry>> convertToListHashMap(Map<String, List<ReferenceEntry>> source) {
		Map<String, List<ReferenceEntry>> result = new HashMap<>();
		for (Map.Entry<String, List<ReferenceEntry>> entry : source.entrySet()) {
			result.put(entry.getKey(), new ArrayList<>(entry.getValue()));
		}
		return result;
	}

	private Map<Path, List<ReferenceEntry>> convertPathListHashMap(Map<Path, List<ReferenceEntry>> source) {
		Map<Path, List<ReferenceEntry>> result = new HashMap<>();
		for (Map.Entry<Path, List<ReferenceEntry>> entry : source.entrySet()) {
			result.put(entry.getKey(), new ArrayList<>(entry.getValue()));
		}
		return result;
	}

	// ===== Statistics =====

	public int getTypeCount() {
		return types.size();
	}

	public int getMethodCount() {
		return methods.size();
	}

	public int getFieldCount() {
		return fields.size();
	}

	public int getIndexedFileCount() {
		return fileToDeclaredTypes.size();
	}
}
