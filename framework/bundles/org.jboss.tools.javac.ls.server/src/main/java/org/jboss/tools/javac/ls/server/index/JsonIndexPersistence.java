/*******************************************************************************
 * Copyright (c) 2025 Red Hat, Inc. and others.
 *
 * This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License 2.0
 * which accompanies this distribution, and is available at
 * https://www.eclipse.org/legal/epl-2.0/
 *
 * SPDX-License-Identifier: EPL-2.0
 *******************************************************************************/
package org.jboss.tools.javac.ls.server.index;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.lang.reflect.Type;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.jboss.tools.javac.ls.index.model.FieldDeclarationEntry;
import org.jboss.tools.javac.ls.index.model.MethodDeclarationEntry;
import org.jboss.tools.javac.ls.index.model.ReferenceEntry;
import org.jboss.tools.javac.ls.index.model.TypeDeclarationEntry;
import org.jboss.tools.javac.ls.index.store.IndexPersistence;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonDeserializationContext;
import com.google.gson.JsonDeserializer;
import com.google.gson.JsonElement;
import com.google.gson.JsonParseException;
import com.google.gson.JsonPrimitive;
import com.google.gson.JsonSerializationContext;
import com.google.gson.JsonSerializer;
import com.google.gson.reflect.TypeToken;

/**
 * JSON-based implementation of IndexPersistence using Gson.
 * Stores index data in separate JSON files within a base directory.
 */
public class JsonIndexPersistence implements IndexPersistence {

	private final Path baseDirectory;
	private final Gson gson;

	private static final String TYPES_FILE = "types.json";
	private static final String SUBTYPES_FILE = "subtypes.json";
	private static final String IMPLEMENTORS_FILE = "implementors.json";
	private static final String TYPE_REFERENCES_FILE = "type_references.json";
	private static final String NAME_REFERENCES_FILE = "name_references.json";
	private static final String METHODS_FILE = "methods.json";
	private static final String FIELDS_FILE = "fields.json";
	private static final String FILE_TO_TYPES_FILE = "file_to_types.json";

	public JsonIndexPersistence(Path baseDirectory) {
		this.baseDirectory = baseDirectory;
		this.gson = new GsonBuilder()
				.setPrettyPrinting()
				.registerTypeHierarchyAdapter(Path.class, new PathSerializer())
				.registerTypeHierarchyAdapter(Path.class, new PathDeserializer())
				.create();
	}

	/**
	 * Custom serializer for java.nio.file.Path to avoid module accessibility issues
	 */
	private static class PathSerializer implements JsonSerializer<Path> {
		@Override
		public JsonElement serialize(Path src, Type typeOfSrc, JsonSerializationContext context) {
			return new JsonPrimitive(src.toString());
		}
	}

	/**
	 * Custom deserializer for java.nio.file.Path to avoid module accessibility issues
	 */
	private static class PathDeserializer implements JsonDeserializer<Path> {
		@Override
		public Path deserialize(JsonElement json, Type typeOfT, JsonDeserializationContext context)
				throws JsonParseException {
			return Paths.get(json.getAsString());
		}
	}

	@Override
	public void saveTypes(Map<String, TypeDeclarationEntry> types) throws IOException {
		ensureDirectoryExists();
		Path file = baseDirectory.resolve(TYPES_FILE);
		try (Writer writer = Files.newBufferedWriter(file, StandardCharsets.UTF_8)) {
			gson.toJson(types, writer);
		}
	}

	@Override
	public Map<String, TypeDeclarationEntry> loadTypes() throws IOException {
		Path file = baseDirectory.resolve(TYPES_FILE);
		if (!Files.exists(file)) {
			return new HashMap<>();
		}
		try (Reader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
			Type type = new TypeToken<Map<String, TypeDeclarationEntry>>(){}.getType();
			Map<String, TypeDeclarationEntry> result = gson.fromJson(reader, type);
			return result != null ? result : new HashMap<>();
		}
	}

	@Override
	public void saveSubtypes(Map<String, Set<String>> subtypes) throws IOException {
		ensureDirectoryExists();
		Path file = baseDirectory.resolve(SUBTYPES_FILE);
		try (Writer writer = Files.newBufferedWriter(file, StandardCharsets.UTF_8)) {
			gson.toJson(subtypes, writer);
		}
	}

	@Override
	public Map<String, Set<String>> loadSubtypes() throws IOException {
		Path file = baseDirectory.resolve(SUBTYPES_FILE);
		if (!Files.exists(file)) {
			return new HashMap<>();
		}
		try (Reader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
			Type type = new TypeToken<Map<String, Set<String>>>(){}.getType();
			Map<String, Set<String>> result = gson.fromJson(reader, type);
			return result != null ? result : new HashMap<>();
		}
	}

	@Override
	public void saveImplementors(Map<String, Set<String>> implementors) throws IOException {
		ensureDirectoryExists();
		Path file = baseDirectory.resolve(IMPLEMENTORS_FILE);
		try (Writer writer = Files.newBufferedWriter(file, StandardCharsets.UTF_8)) {
			gson.toJson(implementors, writer);
		}
	}

	@Override
	public Map<String, Set<String>> loadImplementors() throws IOException {
		Path file = baseDirectory.resolve(IMPLEMENTORS_FILE);
		if (!Files.exists(file)) {
			return new HashMap<>();
		}
		try (Reader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
			Type type = new TypeToken<Map<String, Set<String>>>(){}.getType();
			Map<String, Set<String>> result = gson.fromJson(reader, type);
			return result != null ? result : new HashMap<>();
		}
	}

	@Override
	public void saveTypeReferences(Map<String, List<ReferenceEntry>> typeReferences) throws IOException {
		ensureDirectoryExists();
		Path file = baseDirectory.resolve(TYPE_REFERENCES_FILE);
		try (Writer writer = Files.newBufferedWriter(file, StandardCharsets.UTF_8)) {
			gson.toJson(typeReferences, writer);
		}
	}

	@Override
	public Map<String, List<ReferenceEntry>> loadTypeReferences() throws IOException {
		Path file = baseDirectory.resolve(TYPE_REFERENCES_FILE);
		if (!Files.exists(file)) {
			return new HashMap<>();
		}
		try (Reader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
			Type type = new TypeToken<Map<String, List<ReferenceEntry>>>(){}.getType();
			Map<String, List<ReferenceEntry>> result = gson.fromJson(reader, type);
			return result != null ? result : new HashMap<>();
		}
	}

	@Override
	public void saveNameReferences(Map<String, List<ReferenceEntry>> nameReferences) throws IOException {
		ensureDirectoryExists();
		Path file = baseDirectory.resolve(NAME_REFERENCES_FILE);
		try (Writer writer = Files.newBufferedWriter(file, StandardCharsets.UTF_8)) {
			gson.toJson(nameReferences, writer);
		}
	}

	@Override
	public Map<String, List<ReferenceEntry>> loadNameReferences() throws IOException {
		Path file = baseDirectory.resolve(NAME_REFERENCES_FILE);
		if (!Files.exists(file)) {
			return new HashMap<>();
		}
		try (Reader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
			Type type = new TypeToken<Map<String, List<ReferenceEntry>>>(){}.getType();
			Map<String, List<ReferenceEntry>> result = gson.fromJson(reader, type);
			return result != null ? result : new HashMap<>();
		}
	}

	@Override
	public void saveMethods(Map<String, MethodDeclarationEntry> methods) throws IOException {
		ensureDirectoryExists();
		Path file = baseDirectory.resolve(METHODS_FILE);
		try (Writer writer = Files.newBufferedWriter(file, StandardCharsets.UTF_8)) {
			gson.toJson(methods, writer);
		}
	}

	@Override
	public Map<String, MethodDeclarationEntry> loadMethods() throws IOException {
		Path file = baseDirectory.resolve(METHODS_FILE);
		if (!Files.exists(file)) {
			return new HashMap<>();
		}
		try (Reader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
			Type type = new TypeToken<Map<String, MethodDeclarationEntry>>(){}.getType();
			Map<String, MethodDeclarationEntry> result = gson.fromJson(reader, type);
			return result != null ? result : new HashMap<>();
		}
	}

	@Override
	public void saveFields(Map<String, FieldDeclarationEntry> fields) throws IOException {
		ensureDirectoryExists();
		Path file = baseDirectory.resolve(FIELDS_FILE);
		try (Writer writer = Files.newBufferedWriter(file, StandardCharsets.UTF_8)) {
			gson.toJson(fields, writer);
		}
	}

	@Override
	public Map<String, FieldDeclarationEntry> loadFields() throws IOException {
		Path file = baseDirectory.resolve(FIELDS_FILE);
		if (!Files.exists(file)) {
			return new HashMap<>();
		}
		try (Reader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
			Type type = new TypeToken<Map<String, FieldDeclarationEntry>>(){}.getType();
			Map<String, FieldDeclarationEntry> result = gson.fromJson(reader, type);
			return result != null ? result : new HashMap<>();
		}
	}

	@Override
	public void saveFileToDeclaredTypes(Map<Path, Set<String>> fileToDeclaredTypes) throws IOException {
		ensureDirectoryExists();
		Path file = baseDirectory.resolve(FILE_TO_TYPES_FILE);

		// Convert Path keys to String for JSON serialization
		Map<String, Set<String>> stringMap = new HashMap<>();
		for (Map.Entry<Path, Set<String>> entry : fileToDeclaredTypes.entrySet()) {
			stringMap.put(entry.getKey().toString(), entry.getValue());
		}

		try (Writer writer = Files.newBufferedWriter(file, StandardCharsets.UTF_8)) {
			gson.toJson(stringMap, writer);
		}
	}

	@Override
	public Map<Path, Set<String>> loadFileToDeclaredTypes() throws IOException {
		Path file = baseDirectory.resolve(FILE_TO_TYPES_FILE);
		if (!Files.exists(file)) {
			return new HashMap<>();
		}

		try (Reader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
			Type type = new TypeToken<Map<String, Set<String>>>(){}.getType();
			Map<String, Set<String>> stringMap = gson.fromJson(reader, type);

			// Convert String keys back to Path
			Map<Path, Set<String>> result = new HashMap<>();
			if (stringMap != null) {
				for (Map.Entry<String, Set<String>> entry : stringMap.entrySet()) {
					result.put(Paths.get(entry.getKey()), new HashSet<>(entry.getValue()));
				}
			}
			return result;
		}
	}

	@Override
	public boolean exists() {
		return Files.exists(baseDirectory) && Files.exists(baseDirectory.resolve(TYPES_FILE));
	}

	@Override
	public long getTimestamp() {
		Path typesFile = baseDirectory.resolve(TYPES_FILE);
		if (!Files.exists(typesFile)) {
			return 0;
		}
		try {
			return Files.getLastModifiedTime(typesFile).toMillis();
		} catch (IOException e) {
			return 0;
		}
	}

	private void ensureDirectoryExists() throws IOException {
		if (!Files.exists(baseDirectory)) {
			Files.createDirectories(baseDirectory);
		}
	}

	/**
	 * Delete all index files
	 */
	public void clear() throws IOException {
		if (Files.exists(baseDirectory)) {
			Files.deleteIfExists(baseDirectory.resolve(TYPES_FILE));
			Files.deleteIfExists(baseDirectory.resolve(SUBTYPES_FILE));
			Files.deleteIfExists(baseDirectory.resolve(IMPLEMENTORS_FILE));
			Files.deleteIfExists(baseDirectory.resolve(TYPE_REFERENCES_FILE));
			Files.deleteIfExists(baseDirectory.resolve(NAME_REFERENCES_FILE));
			Files.deleteIfExists(baseDirectory.resolve(METHODS_FILE));
			Files.deleteIfExists(baseDirectory.resolve(FIELDS_FILE));
			Files.deleteIfExists(baseDirectory.resolve(FILE_TO_TYPES_FILE));
		}
	}
}
