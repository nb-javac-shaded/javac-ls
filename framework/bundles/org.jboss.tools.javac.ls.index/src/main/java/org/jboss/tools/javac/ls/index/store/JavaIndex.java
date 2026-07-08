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
 *
 * <h2>Architecture Overview</h2>
 * The JavaIndex maintains a searchable catalog of all Java declarations (types, methods, fields)
 * and their references (where they're used) across an entire workspace. It's designed for fast
 * code navigation queries like "Go to Definition", "Find References", and type hierarchy.
 *
 * <h2>Data Organization</h2>
 * The index contains several categories of data:
 *
 * <h3>1. Declarations</h3>
 * Maps from qualified names to declaration entries:
 * - types: "com.example.MyClass" → TypeDeclarationEntry (location, modifiers, superclass, etc.)
 * - methods: "com.example.MyClass.doSomething(String,int)" → MethodDeclarationEntry
 * - fields: "com.example.MyClass.myField" → FieldDeclarationEntry
 *
 * <h3>2. Type Hierarchy</h3>
 * Precomputed bidirectional relationships for fast queries:
 * - subtypes: "java.lang.Object" → {"com.example.MyClass", "com.example.Other", ...}
 * - implementors: "java.io.Serializable" → {"com.example.MyClass", ...}
 *
 * <h3>3. References (Where things are used)</h3>
 * Maps from qualified/simple names to usage locations:
 * - typeReferences: "com.example.MyClass" → [ReferenceEntry@File1:42, ReferenceEntry@File2:17, ...]
 * - nameReferences: "myVariable" → [ReferenceEntry@File3:8, ...]
 *
 * <h3>4. File Tracking (Incremental Updates)</h3>
 * Track which files declare which types, for efficient re-indexing:
 * - fileToDeclaredTypes: fileId → {"com.example.MyClass", "com.example.MyClass$Inner"}
 * - fileTimestamps: fileId → lastModified (for change detection)
 * - fileTypeReferences: fileId → [all type references from this file]
 * - fileNameReferences: fileId → [all name references from this file]
 *
 * When a file changes, we use fileToDeclaredTypes to find what it declared, remove those
 * declarations from the index, then remove the old references using fileTypeReferences/fileNameReferences.
 * This prevents reference leaks when re-indexing.
 *
 * <h3>5. Diagnostics (Compilation Errors/Warnings)</h3>
 * Stored as JSON strings per file:
 * - fileDiagnostics: fileId → [diagnostic JSON strings]
 * Not persisted - diagnostics are transient and re-collected on each index load.
 *
 * <h2>File Path Interning</h2>
 * To reduce memory usage, file paths are "interned" - each unique path is stored once in
 * FilePathRegistry and referenced by integer ID everywhere else. For a large codebase with
 * millions of references, this reduces index size by ~50%.
 *
 * All file-based maps (fileToDeclaredTypes, fileTimestamps, fileTypeReferences, fileNameReferences,
 * fileDiagnostics) use Integer keys (file IDs) instead of Path objects. The Location class also
 * stores fileId instead of Path. FilePathRegistry provides bidirectional mapping:
 * - Path → fileId (for storing)
 * - fileId → Path (for retrieval)
 *
 * <h2>Persistence</h2>
 * The index is persisted to disk via IndexPersistence implementations (e.g., JsonIndexPersistence).
 * Each data structure is saved to a separate file:
 * - types.json: All type declarations
 * - methods.json: All method declarations
 * - fields.json: All field declarations
 * - subtypes.json: Subtype relationships
 * - implementors.json: Interface implementor relationships
 * - type_references.json: Type references
 * - name_references.json: Name references
 * - file_to_types.json: File → declared types mapping (using integer file IDs as keys)
 * - file_timestamps.json: File modification timestamps (using integer file IDs)
 * - file_type_references.json: File → type references tracking (using integer file IDs)
 * - file_name_references.json: File → name references tracking (using integer file IDs)
 * - file_path_registry.json: Path ↔ fileId mapping
 *
 * When saving, the FilePathRegistry is saved first, then all other data. When loading, the
 * FilePathRegistry is loaded first, then all Location objects have their pathRegistry reference
 * restored via rehydrateLocations().
 *
 * Diagnostics (fileDiagnostics) are NOT persisted - they're re-collected during indexing.
 *
 * <h2>Thread Safety</h2>
 * All maps use ConcurrentHashMap for thread-safe concurrent access. The index supports:
 * - Multiple concurrent readers (queries)
 * - Single writer (indexing) with concurrent readers
 * - Proper locking is managed by JavaIndexCache (read/write locks)
 *
 * <h2>Incremental Updates</h2>
 * When a file changes:
 * 1. Call removeFile(path) to clean up old data
 *    - Looks up fileToDeclaredTypes[fileId] to find declared types
 *    - Removes those type/method/field declarations
 *    - Removes old references using fileTypeReferences/fileNameReferences
 * 2. Re-index the file, which adds new declarations and references
 * 3. Update fileTimestamps[fileId] with new modification time
 *
 * This ensures stale data is removed and the index stays consistent.
 *
 * @see FilePathRegistry for file path interning details
 * @see Location for how file paths are stored in location objects
 * @see IndexPersistence for persistence interface
 * @see JsonIndexPersistence for JSON-based persistence implementation
 */
public class JavaIndex {
	private static final Logger LOG = LoggerFactory.getLogger(JavaIndex.class);

	// ============================================================================
	// DECLARATIONS - What's defined in the code
	// ============================================================================
	// Maps qualified name → declaration entry
	// Persisted to: types.json, methods.json, fields.json

	/** Type declarations: "com.example.MyClass" → TypeDeclarationEntry */
	private final Map<String, TypeDeclarationEntry> types = new ConcurrentHashMap<>();

	/** Method declarations: "com.example.MyClass.doSomething(String,int)" → MethodDeclarationEntry */
	private final Map<String, MethodDeclarationEntry> methods = new ConcurrentHashMap<>();

	/** Field declarations: "com.example.MyClass.myField" → FieldDeclarationEntry */
	private final Map<String, FieldDeclarationEntry> fields = new ConcurrentHashMap<>();

	// ============================================================================
	// TYPE HIERARCHY - Precomputed relationships for fast queries
	// ============================================================================
	// Persisted to: subtypes.json, implementors.json

	/** Subtype relationships: "java.lang.Object" → {"com.example.MyClass", ...} */
	private final Map<String, Set<String>> subtypes = new ConcurrentHashMap<>();

	/** Interface implementors: "java.io.Serializable" → {"com.example.MyClass", ...} */
	private final Map<String, Set<String>> implementors = new ConcurrentHashMap<>();

	// ============================================================================
	// REFERENCES - Where things are used
	// ============================================================================
	// Persisted to: type_references.json, name_references.json

	/** Type references: "com.example.MyClass" → [ReferenceEntry locations] */
	private final Map<String, List<ReferenceEntry>> typeReferences = new ConcurrentHashMap<>();

	/** Name references: "myVariable" → [ReferenceEntry locations] */
	private final Map<String, List<ReferenceEntry>> nameReferences = new ConcurrentHashMap<>();

	// ============================================================================
	// FILE TRACKING - For incremental updates (all use integer file IDs)
	// ============================================================================
	// Persisted to: file_to_types.json, file_timestamps.json,
	//               file_type_references.json, file_name_references.json

	/** Which types each file declares: fileId → {"com.example.MyClass", ...}
	 *  Used to remove old declarations when re-indexing a file. */
	private final Map<Integer, Set<String>> fileToDeclaredTypes = new ConcurrentHashMap<>();

	/** File modification times: fileId → lastModified timestamp
	 *  Used for change detection. */
	private final Map<Integer, Long> fileTimestamps = new ConcurrentHashMap<>();

	/** Type references per file: fileId → [ReferenceEntry list]
	 *  Used to remove old type references when re-indexing (prevents reference leaks). */
	private final Map<Integer, List<ReferenceEntry>> fileTypeReferences = new ConcurrentHashMap<>();

	/** Name references per file: fileId → [ReferenceEntry list]
	 *  Used to remove old name references when re-indexing (prevents reference leaks). */
	private final Map<Integer, List<ReferenceEntry>> fileNameReferences = new ConcurrentHashMap<>();

	// ============================================================================
	// DIAGNOSTICS - Compilation errors and warnings (NOT persisted)
	// ============================================================================

	/** Diagnostics per file: fileId → [diagnostic JSON strings]
	 *  Transient data, re-collected during indexing. Not saved to disk. */
	private final Map<Integer, List<String>> fileDiagnostics = new ConcurrentHashMap<>();

	// ============================================================================
	// FILE PATH INTERNING - Reduce memory by storing each path once
	// ============================================================================
	// Persisted to: file_path_registry.json

	/** Maps Path ↔ Integer ID for memory-efficient file path storage.
	 *  Each unique file path is stored once and referenced by ID everywhere else.
	 *  Reduces index size by ~50% for large codebases. */
	private final FilePathRegistry pathRegistry = new FilePathRegistry();

	// ============================================================================
	// CHANGE NOTIFICATION
	// ============================================================================

	/** Listeners notified when index changes (file added/removed/updated) */
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
	 *
	 * A type reference is a usage of a type (class/interface) in code, such as:
	 * - Variable declaration: MyClass obj;
	 * - Constructor call: new MyClass()
	 * - Method parameter: void foo(MyClass param)
	 * - Extends/implements: class MySubclass extends MyClass
	 *
	 * This method adds the reference to TWO maps:
	 * 1. typeReferences (by qualified name) - for "Find References" queries
	 * 2. fileTypeReferences (by file ID) - for cleanup when re-indexing the file
	 *
	 * The dual tracking prevents reference leaks: when a file is re-indexed, we use
	 * fileTypeReferences to find and remove all old references from that file, then
	 * add the new ones.
	 *
	 * Thread-safe for concurrent additions.
	 *
	 * @param qualifiedName the fully qualified type name being referenced (e.g., "com.example.MyClass")
	 * @param reference the reference details (location, kind)
	 * @param sourceFile the file containing this reference
	 */
	public void addTypeReference(String qualifiedName, ReferenceEntry reference, Path sourceFile) {
		// Add to global type references map (for "Find References" queries)
		typeReferences.computeIfAbsent(qualifiedName, k -> Collections.synchronizedList(new ArrayList<>()))
				.add(reference);

		// Add to file-based tracking (for cleanup on re-index)
		int fileId = pathRegistry.getOrRegister(sourceFile);
		fileTypeReferences.computeIfAbsent(fileId, k -> Collections.synchronizedList(new ArrayList<>()))
				.add(reference);
	}

	/**
	 * Add a name reference to the index (for find usages, rename refactoring).
	 *
	 * A name reference is a simple name usage (without package qualification):
	 * - Variable reference: return myVariable;
	 * - Method call: doSomething();
	 * - Field access: this.fieldName
	 *
	 * Like addTypeReference, this adds to both:
	 * 1. nameReferences (by simple name) - for "Find Usages" queries
	 * 2. fileNameReferences (by file ID) - for cleanup on re-index
	 *
	 * Thread-safe for concurrent additions.
	 *
	 * @param name the simple name being referenced (e.g., "myVariable", "doSomething")
	 * @param reference the reference details (location, kind)
	 * @param sourceFile the file containing this reference
	 */
	public void addNameReference(String name, ReferenceEntry reference, Path sourceFile) {
		// Add to global name references map (for "Find Usages" queries)
		nameReferences.computeIfAbsent(name, k -> Collections.synchronizedList(new ArrayList<>()))
				.add(reference);

		// Add to file-based tracking (for cleanup on re-index)
		int fileId = pathRegistry.getOrRegister(sourceFile);
		fileNameReferences.computeIfAbsent(fileId, k -> Collections.synchronizedList(new ArrayList<>()))
				.add(reference);
	}

	/**
	 * Track which types a file declares, for incremental update support.
	 *
	 * When a file is indexed, we record which types it declares (including inner classes).
	 * This allows efficient re-indexing: when the file changes, we look up its declared types,
	 * remove those from the index, then re-index the file.
	 *
	 * Also records the file's modification timestamp for change detection.
	 *
	 * Example: File "MyClass.java" declares ["com.example.MyClass", "com.example.MyClass$Inner"]
	 *
	 * @param file the source file path
	 * @param declaredTypes set of fully qualified type names declared in this file
	 */
	public void trackFileDeclaredTypes(Path file, Set<String> declaredTypes) {
		int fileId = pathRegistry.getOrRegister(file);

		// Store which types this file declares (for cleanup on re-index)
		fileToDeclaredTypes.put(fileId, new HashSet<>(declaredTypes));

		// Store the file's modification time for change detection
		long timestamp = file.toFile().exists() ? file.toFile().lastModified() : System.currentTimeMillis();
		fileTimestamps.put(fileId, timestamp);
	}

	/**
	 * Store diagnostics (compilation errors/warnings) for a file.
	 * Diagnostics are serialized as strings to avoid keeping full IProblem objects in memory.
	 *
	 * @param file the source file
	 * @param diagnosticStrings list of diagnostic strings (JSON or formatted text)
	 */
	public void storeDiagnostics(Path file, List<String> diagnosticStrings) {
		int fileId = pathRegistry.getOrRegister(file);
		if (diagnosticStrings == null || diagnosticStrings.isEmpty()) {
			fileDiagnostics.remove(fileId);
		} else {
			fileDiagnostics.put(fileId, new ArrayList<>(diagnosticStrings));
		}
	}

	/**
	 * Get diagnostics for a specific file.
	 *
	 * @param file the source file
	 * @return list of diagnostic strings, or empty list if none
	 */
	public List<String> getDiagnostics(Path file) {
		int fileId = pathRegistry.getId(file);
		if (fileId == -1) {
			return Collections.emptyList();
		}
		List<String> diags = fileDiagnostics.get(fileId);
		return diags != null ? new ArrayList<>(diags) : Collections.emptyList();
	}

	/**
	 * Get all files that have diagnostics.
	 *
	 * @return set of file paths with diagnostics
	 */
	public Set<Path> getFilesWithDiagnostics() {
		Set<Path> paths = new HashSet<>();
		for (int fileId : fileDiagnostics.keySet()) {
			Path path = pathRegistry.getPath(fileId);
			if (path != null) {
				paths.add(path);
			}
		}
		return paths;
	}

	/**
	 * Remove all index entries for a file (for incremental updates).
	 *
	 * This is the critical method for keeping the index consistent during re-indexing.
	 * It removes ALL data associated with a file before the file is re-indexed.
	 *
	 * What gets removed:
	 * 1. Type/method/field DECLARATIONS that were in this file
	 * 2. Type hierarchy entries (subtypes/implementors) for those types
	 * 3. Type REFERENCES that originated from this file
	 * 4. Name REFERENCES that originated from this file
	 * 5. File timestamps and diagnostics
	 *
	 * Why this prevents reference leaks:
	 * Without tracking which references came from which file, we couldn't remove them.
	 * Old references would accumulate in the index, causing "Find References" to return
	 * stale results pointing to code that no longer exists. The fileTypeReferences and
	 * fileNameReferences maps track this, allowing complete cleanup.
	 *
	 * Typical usage pattern:
	 * 1. File changes
	 * 2. Call removeFile(path) to clean up old data
	 * 3. Re-parse and re-index the file
	 * 4. New declarations and references are added
	 *
	 * @param file the file to remove from the index
	 */
	public void removeFile(Path file) {
		int fileId = pathRegistry.getOrRegister(file);

		// Step 1: Remove type declarations from this file
		Set<String> oldTypes = fileToDeclaredTypes.remove(fileId);
		if (oldTypes != null) {
			for (String qname : oldTypes) {
				// Remove the type itself
				TypeDeclarationEntry removed = types.remove(qname);
				if (removed != null) {
					// Remove from hierarchy indexes (subtypes/implementors)
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

				// Remove methods and fields declared by this type
				methods.entrySet().removeIf(e -> e.getValue().getDeclaringType().equals(qname));
				fields.entrySet().removeIf(e -> e.getValue().getDeclaringType().equals(qname));
			}
		}

		// Step 2: Remove type references FROM this file
		// This prevents stale references from appearing in "Find References" results
		List<ReferenceEntry> fileTypeRefs = fileTypeReferences.remove(fileId);
		if (fileTypeRefs != null) {
			for (ReferenceEntry ref : fileTypeRefs) {
				// Remove this specific reference from the global type references map
				// We iterate all typeReferences lists to find and remove this ref
				for (List<ReferenceEntry> refs : typeReferences.values()) {
					refs.remove(ref);
				}
			}
		}

		// Step 3: Remove name references FROM this file
		List<ReferenceEntry> fileNameRefs = fileNameReferences.remove(fileId);
		if (fileNameRefs != null) {
			for (ReferenceEntry ref : fileNameRefs) {
				// Remove this specific reference from the global name references map
				for (List<ReferenceEntry> refs : nameReferences.values()) {
					refs.remove(ref);
				}
			}
		}

		// Step 4: Remove file metadata
		fileTimestamps.remove(fileId);
		fileDiagnostics.remove(fileId);

		// Notify listeners that the file was removed
		fireIndexChanged(new IndexChangeEvent(file, ChangeKind.FILE_REMOVED));
	}

	/**
	 * Check if a file has been indexed.
	 *
	 * @param file the file path
	 * @return true if the file is in the index
	 */
	public boolean isFileIndexed(Path file) {
		int fileId = pathRegistry.getId(file);
		return fileId != -1 && fileToDeclaredTypes.containsKey(fileId);
	}

	/**
	 * Get the types declared in an indexed file.
	 *
	 * @param file the file path
	 * @return set of qualified type names, or null if file not indexed
	 */
	public Set<String> getFileDeclaredTypes(Path file) {
		int fileId = pathRegistry.getId(file);
		if (fileId == -1) return null;
		Set<String> types = fileToDeclaredTypes.get(fileId);
		return types != null ? new HashSet<>(types) : null;
	}

	/**
	 * Get the timestamp when a file was indexed.
	 *
	 * @param file the file path
	 * @return timestamp in milliseconds, or 0 if file not indexed
	 */
	public long getFileTimestamp(Path file) {
		int fileId = pathRegistry.getId(file);
		if (fileId == -1) return 0L;
		return fileTimestamps.getOrDefault(fileId, 0L);
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
	/**
	 * Save the entire index to persistent storage.
	 *
	 * Persistence Strategy:
	 * Each major data structure is saved to its own file for modularity and easier debugging.
	 * The file path registry is saved as a separate file (file_path_registry.json).
	 *
	 * Saved data:
	 * - types.json: All type declarations
	 * - methods.json: All method declarations
	 * - fields.json: All field declarations
	 * - subtypes.json: Subtype hierarchy relationships
	 * - implementors.json: Interface implementor relationships
	 * - type_references.json: Type usage locations
	 * - name_references.json: Name usage locations
	 * - file_to_types.json: File → declared types mapping (uses integer file IDs)
	 * - file_timestamps.json: File modification times (uses integer file IDs)
	 * - file_type_references.json: File → type refs tracking (uses integer file IDs)
	 * - file_name_references.json: File → name refs tracking (uses integer file IDs)
	 * - file_path_registry.json: Path ↔ Integer ID mapping
	 *
	 * NOT saved:
	 * - fileDiagnostics: Transient data, re-collected during indexing
	 *
	 * File Size Impact of Path Interning:
	 * For a large codebase (e.g., Quarkus with 23,604 files):
	 * - Before: 1.8GB (full file paths repeated in every map)
	 * - After: 948MB (integer IDs in maps, paths stored once in registry)
	 * - Savings: 47.3% reduction
	 *
	 * @param persistence the persistence implementation to use (e.g., JsonIndexPersistence)
	 * @throws IOException if any save operation fails
	 */
	public void saveTo(IndexPersistence persistence) throws IOException {
		// Save all data structures (convert ConcurrentHashMap to HashMap for serialization)
		persistence.saveTypes(new HashMap<>(types));
		persistence.saveSubtypes(convertToHashMap(subtypes));
		persistence.saveImplementors(convertToHashMap(implementors));
		persistence.saveTypeReferences(convertToListHashMap(typeReferences));
		persistence.saveNameReferences(convertToListHashMap(nameReferences));
		persistence.saveMethods(new HashMap<>(methods));
		persistence.saveFields(new HashMap<>(fields));

		// Save file-based maps (all use integer file IDs as keys)
		persistence.saveFileToDeclaredTypes(new HashMap<>(fileToDeclaredTypes));
		persistence.saveFileTimestamps(new HashMap<>(fileTimestamps));
		persistence.saveFileTypeReferences(new HashMap<>(fileTypeReferences));
		persistence.saveFileNameReferences(new HashMap<>(fileNameReferences));

		// Save file path registry (Path ↔ Integer ID mapping)
		persistence.saveFilePathRegistry(pathRegistry.getAllPaths());

		// Note: fileDiagnostics is NOT saved - diagnostics are transient and re-collected on load
	}

	/**
	 * Load the entire index from persistent storage.
	 *
	 * Loading Order:
	 * 1. Clear all existing data
	 * 2. Load file path registry FIRST (required to resolve file IDs in other structures)
	 * 3. Load all other data structures
	 * 4. Rehydrate Location objects (restore their pathRegistry references)
	 *
	 * Why file path registry loads first:
	 * All file-based maps use integer file IDs as keys. The Location objects inside
	 * ReferenceEntry objects store file IDs. After deserialization, Location.pathRegistry
	 * is null (it's transient). We need to load the FilePathRegistry first, then call
	 * rehydrateLocations() to restore pathRegistry references on all Location objects.
	 *
	 * Rehydration Process:
	 * Location stores fileId but not the path itself. The path is retrieved via:
	 *   pathRegistry.getPath(fileId)
	 * After deserialization, pathRegistry is null on all Location objects because it's
	 * marked transient (to avoid circular serialization). rehydrateLocations() walks
	 * all Location objects and sets their pathRegistry to this index's pathRegistry.
	 *
	 * @param persistence the persistence implementation to use (e.g., JsonIndexPersistence)
	 * @throws IOException if any load operation fails
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
		pathRegistry.clear();

		// Load file path registry first (needed to resolve file IDs in other structures)
		Map<Path, Integer> loadedPathRegistry = persistence.loadFilePathRegistry();
		if (loadedPathRegistry != null) {
			pathRegistry.loadPaths(loadedPathRegistry);
		}

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

		Map<Integer, Set<String>> loadedFileToDeclaredTypes = persistence.loadFileToDeclaredTypes();
		if (loadedFileToDeclaredTypes != null) {
			fileToDeclaredTypes.putAll(loadedFileToDeclaredTypes);
		}

		Map<Integer, Long> loadedFileTimestamps = persistence.loadFileTimestamps();
		if (loadedFileTimestamps != null) {
			fileTimestamps.putAll(loadedFileTimestamps);
		}

		Map<Integer, List<ReferenceEntry>> loadedFileTypeRefs = persistence.loadFileTypeReferences();
		if (loadedFileTypeRefs != null) {
			fileTypeReferences.putAll(loadedFileTypeRefs);
		}

		Map<Integer, List<ReferenceEntry>> loadedFileNameRefs = persistence.loadFileNameReferences();
		if (loadedFileNameRefs != null) {
			fileNameReferences.putAll(loadedFileNameRefs);
		}

		// Rehydrate all Location objects with the pathRegistry after deserialization
		rehydrateLocations();

		LOG.info("Loaded index: {} types, {} methods, {} fields",
				types.size(), methods.size(), fields.size());
	}

	/**
	 * Rehydrate all Location objects with the pathRegistry after deserialization.
	 */
	private void rehydrateLocations() {
		for (TypeDeclarationEntry type : types.values()) {
			if (type.getLocation() != null) {
				type.getLocation().setPathRegistry(pathRegistry);
			}
		}

		for (MethodDeclarationEntry method : methods.values()) {
			if (method.getLocation() != null) {
				method.getLocation().setPathRegistry(pathRegistry);
			}
		}

		for (FieldDeclarationEntry field : fields.values()) {
			if (field.getLocation() != null) {
				field.getLocation().setPathRegistry(pathRegistry);
			}
		}

		for (List<ReferenceEntry> refs : typeReferences.values()) {
			for (ReferenceEntry ref : refs) {
				if (ref.getLocation() != null) {
					ref.getLocation().setPathRegistry(pathRegistry);
				}
			}
		}

		for (List<ReferenceEntry> refs : nameReferences.values()) {
			for (ReferenceEntry ref : refs) {
				if (ref.getLocation() != null) {
					ref.getLocation().setPathRegistry(pathRegistry);
				}
			}
		}

		for (List<ReferenceEntry> refs : fileTypeReferences.values()) {
			for (ReferenceEntry ref : refs) {
				if (ref.getLocation() != null) {
					ref.getLocation().setPathRegistry(pathRegistry);
				}
			}
		}

		for (List<ReferenceEntry> refs : fileNameReferences.values()) {
			for (ReferenceEntry ref : refs) {
				if (ref.getLocation() != null) {
					ref.getLocation().setPathRegistry(pathRegistry);
				}
			}
		}
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

	// ===== File Path Registry =====

	/**
	 * Register a file path and get its unique ID.
	 * This is used to reduce memory usage by storing paths only once.
	 *
	 * @param path the file path
	 * @return unique integer ID for this path
	 */
	public int registerPath(Path path) {
		return pathRegistry.getOrRegister(path);
	}

	/**
	 * Resolve a file ID to its path.
	 *
	 * @param fileId the file path ID
	 * @return the Path, or null if ID is invalid
	 */
	public Path getPath(int fileId) {
		return pathRegistry.getPath(fileId);
	}

	/**
	 * Get the file path registry for advanced operations.
	 *
	 * @return the registry
	 */
	public FilePathRegistry getPathRegistry() {
		return pathRegistry;
	}
}
