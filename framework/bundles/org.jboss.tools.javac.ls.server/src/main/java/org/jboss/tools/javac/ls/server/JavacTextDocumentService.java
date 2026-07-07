/*******************************************************************************
 * Copyright (c) 2026 Red Hat, Inc. Distributed under license by Red Hat, Inc.
 * All rights reserved. This program is made available under the terms of the
 * Eclipse Public License v2.0 which accompanies this distribution, and is
 * available at http://www.eclipse.org/legal/epl-v20.html
 *
 * Contributors: Red Hat, Inc.
 ******************************************************************************/
package org.jboss.tools.javac.ls.server;

import java.io.IOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CompletableFuture;

import org.eclipse.lsp4j.DefinitionParams;
import org.eclipse.lsp4j.Diagnostic;
import org.eclipse.lsp4j.DiagnosticSeverity;
import org.eclipse.lsp4j.DidChangeTextDocumentParams;
import org.eclipse.lsp4j.DidCloseTextDocumentParams;
import org.eclipse.lsp4j.DidOpenTextDocumentParams;
import org.eclipse.lsp4j.DidSaveTextDocumentParams;
import org.eclipse.lsp4j.DocumentDiagnosticParams;
import org.eclipse.lsp4j.DocumentDiagnosticReport;
import org.eclipse.lsp4j.Location;
import org.eclipse.lsp4j.LocationLink;
import org.eclipse.lsp4j.Position;
import org.eclipse.lsp4j.Range;
import org.eclipse.lsp4j.ReferenceParams;
import org.eclipse.lsp4j.RelatedFullDocumentDiagnosticReport;
import org.eclipse.lsp4j.jsonrpc.messages.Either;
import org.eclipse.lsp4j.services.TextDocumentService;
import org.jboss.tools.javac.ls.api.dao.DiagnosticList;
import org.jboss.tools.javac.ls.index.store.JavaIndex;
import org.jboss.tools.javac.ls.search.engine.SearchEngine;
import org.jboss.tools.javac.ls.search.match.SearchMatch;
import org.jboss.tools.javac.ls.search.pattern.FieldPattern;
import org.jboss.tools.javac.ls.search.pattern.MethodPattern;
import org.jboss.tools.javac.ls.search.pattern.SearchPattern;
import org.jboss.tools.javac.ls.search.pattern.TypePattern;
import org.jboss.tools.javac.ls.server.event.EventManager;
import org.jboss.tools.javac.ls.server.model.WorkspaceModel;
import org.jboss.tools.javac.ls.server.model.WorkspaceProject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import shaded.org.eclipse.jdt.core.dom.ASTNode;
import shaded.org.eclipse.jdt.core.dom.CompilationUnit;
import shaded.org.eclipse.jdt.core.dom.IBinding;
import shaded.org.eclipse.jdt.core.dom.IMethodBinding;
import shaded.org.eclipse.jdt.core.dom.ITypeBinding;
import shaded.org.eclipse.jdt.core.dom.IVariableBinding;
import shaded.org.eclipse.jdt.core.dom.Name;
import shaded.org.eclipse.jdt.core.dom.NodeFinder;
import shaded.org.eclipse.jdt.core.dom.SimpleName;
import shaded.org.eclipse.jdt.core.dom.VariableDeclaration;

public class JavacTextDocumentService implements TextDocumentService {
	private static final Logger LOG = LoggerFactory.getLogger(JavacTextDocumentService.class);

	private final JavacLSServerImpl server;

	public JavacTextDocumentService(JavacLSServerImpl server) {
		this.server = server;
	}

	@Override
	public void didOpen(DidOpenTextDocumentParams params) {
		LOG.debug("didOpen: {}", params.getTextDocument().getUri());
		// NOTE: We do not track opened documents or maintain in-memory document state.
		// This server reads all content directly from the filesystem and does not use
		// the document content sent by the client. No action needed.
	}

	@Override
	public void didChange(DidChangeTextDocumentParams params) {
		LOG.debug("didChange: {}", params.getTextDocument().getUri());
		// NOTE: We do not track document changes or maintain in-memory document state.
		// This server reads all content directly from the filesystem and does not use
		// the incremental changes sent by the client. No action needed.
	}

	@Override
	public void didClose(DidCloseTextDocumentParams params) {
		LOG.debug("didClose: {}", params.getTextDocument().getUri());
		// NOTE: We do not track opened/closed documents or maintain in-memory document state.
		// This server reads all content directly from the filesystem. No action needed.
	}

	@Override
	public void didSave(DidSaveTextDocumentParams params) {
		String uri = params.getTextDocument().getUri();
		LOG.debug("didSave: {}", uri);

		try {
			// Convert URI to file path
			String filePath = uriToFilePath(uri);

			// Get workspace model
			WorkspaceModel workspace = server.getLauncher().getWorkspaceModel();
			if (workspace == null) {
				LOG.warn("Workspace not initialized, cannot reparse saved file: {}", filePath);
				return;
			}

			// Find which project this file belongs to
			String projectName = findProjectForFile(workspace, filePath);
			if (projectName == null) {
				LOG.debug("File is not part of any workspace project: {}", filePath);
				return;
			}

			LOG.info("File saved, re-parsing: {} in project: {}", filePath, projectName);

			// Re-parse the saved file
			// This will:
			// 1. Re-index the file with new content from disk
			// 2. Extract updated diagnostics
			// 3. Fire fileDiagnosticsChanged() which broadcasts to all clients
			java.nio.file.Path path = java.nio.file.Paths.get(filePath);
			workspace.reparseFiles(projectName, java.util.Collections.singletonList(path));

		} catch (Exception e) {
			LOG.error("Error handling didSave for {}: {}", uri, e.getMessage(), e);
		}
	}

	/**
	 * LSP 3.17: Pull diagnostics for a specific document.
	 * Client requests diagnostics on-demand rather than server pushing them.
	 */
	@Override
	public CompletableFuture<DocumentDiagnosticReport> diagnostic(DocumentDiagnosticParams params) {
		String uri = params.getTextDocument().getUri();
		LOG.debug("textDocument/diagnostic request for: {}", uri);

		try {
			// Convert URI to file path
			String filePath = uriToFilePath(uri);

			// Get diagnostics using existing implementation
			WorkspaceModel workspace = server.getLauncher().getWorkspaceModel();
			if (workspace == null) {
				LOG.warn("Workspace not initialized");
				return CompletableFuture.completedFuture(createEmptyDiagnosticReport());
			}

			DiagnosticList diagnosticList = workspace.getFileDiagnostics(filePath);

			// Convert to LSP diagnostics
			List<Diagnostic> lspDiagnostics = EventManager.convertToLspDiagnostics(diagnosticList);

			// Create report
			RelatedFullDocumentDiagnosticReport report = new RelatedFullDocumentDiagnosticReport();
			report.setItems(lspDiagnostics);

			DocumentDiagnosticReport result = new DocumentDiagnosticReport(report);
			return CompletableFuture.completedFuture(result);

		} catch (Exception e) {
			LOG.error("Error getting diagnostics for {}: {}", uri, e.getMessage(), e);
			return CompletableFuture.completedFuture(createEmptyDiagnosticReport());
		}
	}

	/**
	 * Convert LSP URI to file system path.
	 */
	private String uriToFilePath(String uriString) {
		try {
			URI uri = URI.create(uriString);
			return Paths.get(uri).toString();
		} catch (Exception e) {
			LOG.error("Failed to convert URI to path: {}", uriString, e);
			return uriString; // Fallback
		}
	}

	private DocumentDiagnosticReport createEmptyDiagnosticReport() {
		RelatedFullDocumentDiagnosticReport report = new RelatedFullDocumentDiagnosticReport();
		report.setItems(new ArrayList<>());
		return new DocumentDiagnosticReport(report);
	}

	/**
	 * Find which project contains the given file path.
	 */
	private String findProjectForFile(WorkspaceModel workspace, String filePath) {
		for (String projectName : workspace.getProjectNames()) {
			String projectPath = workspace.getProjectPath(projectName);
			if (projectPath != null && filePath.startsWith(projectPath)) {
				return projectName;
			}
		}
		return null;
	}

	/**
	 * LSP: Go to definition.
	 * Returns the location(s) where the symbol under the cursor is defined.
	 */
	@Override
	public CompletableFuture<Either<List<? extends Location>, List<? extends LocationLink>>> definition(DefinitionParams params) {
		String uri = params.getTextDocument().getUri();
		Position position = params.getPosition();
		LOG.debug("textDocument/definition request for: {} at {}:{}", uri, position.getLine(), position.getCharacter());

		try {
			// Convert URI to file path
			String filePath = uriToFilePath(uri);
			Path path = Paths.get(filePath);

			// Read file content
			String content;
			try {
				content = Files.readString(path);
			} catch (IOException e) {
				LOG.error("Cannot read file: {}", filePath, e);
				return CompletableFuture.completedFuture(Either.forLeft(Collections.emptyList()));
			}

			// Get workspace model
			WorkspaceModel workspace = server.getLauncher().getWorkspaceModel();
			if (workspace == null) {
				LOG.warn("Workspace not initialized");
				return CompletableFuture.completedFuture(Either.forLeft(Collections.emptyList()));
			}

			// Find which project this file belongs to
			String projectName = findProjectForFile(workspace, filePath);
			if (projectName == null) {
				LOG.debug("File is not part of any workspace project: {}", filePath);
				return CompletableFuture.completedFuture(Either.forLeft(Collections.emptyList()));
			}

			// Parse file with bindings
			CompilationUnit cu = parseFileWithBindings(workspace, projectName, path);
			if (cu == null) {
				LOG.warn("Cannot parse file: {}", filePath);
				return CompletableFuture.completedFuture(Either.forLeft(Collections.emptyList()));
			}

			// Convert position to offset
			int offset = positionToOffset(content, position);
			if (offset < 0) {
				LOG.debug("Invalid position: {}:{}", position.getLine(), position.getCharacter());
				return CompletableFuture.completedFuture(Either.forLeft(Collections.emptyList()));
			}

			// Find node at cursor position
			ASTNode node = NodeFinder.perform(cu, offset, 0);
			if (node == null) {
				LOG.debug("No AST node at offset {}", offset);
				return CompletableFuture.completedFuture(Either.forLeft(Collections.emptyList()));
			}

			// Extract Name node
			Name nameNode = extractNameNode(node);
			if (nameNode == null) {
				LOG.debug("No name node found at offset {}", offset);
				return CompletableFuture.completedFuture(Either.forLeft(Collections.emptyList()));
			}

			// Resolve binding
			IBinding binding = nameNode.resolveBinding();
			if (binding == null) {
				LOG.debug("Cannot resolve binding for name: {}", nameNode.getFullyQualifiedName());
				return CompletableFuture.completedFuture(Either.forLeft(Collections.emptyList()));
			}

			// Handle different binding types
			List<Location> locations = findDefinitionLocations(workspace, projectName, binding, cu, filePath, content);
			return CompletableFuture.completedFuture(Either.forLeft(locations));

		} catch (Exception e) {
			LOG.error("Error in textDocument/definition for {}: {}", uri, e.getMessage(), e);
			return CompletableFuture.completedFuture(Either.forLeft(Collections.emptyList()));
		}
	}

	/**
	 * Parse a file with binding resolution enabled.
	 */
	private CompilationUnit parseFileWithBindings(WorkspaceModel workspace, String projectName, Path filePath) {
		try {
			// Get classpath and sourcepath for the project
			List<java.io.File> classpath = new ArrayList<>();
			List<String> sourcepath = new ArrayList<>();
			var classpathEntries = workspace.getProjectClasspathNonBlocking(projectName, false);
			if (classpathEntries != null) {
				for (var entry : classpathEntries) {
					if (entry.getPath() != null) {
						if (entry.getType() == org.jboss.tools.javac.ls.server.model.classpath.IJavacClasspathEntry.EntryType.SOURCE) {
							sourcepath.add(entry.getPath());
						} else {
							classpath.add(new java.io.File(entry.getPath()));
						}
					}
				}
			}

			// Configure compiler options with sourcepath
			java.util.Map<String, String> compilerOptions = new java.util.HashMap<>();
			if (!sourcepath.isEmpty()) {
				compilerOptions.put("javac.sourcepath", String.join(java.io.File.pathSeparator, sourcepath));
			}

			// Parse file on-demand with bindings
			URI fileUri = filePath.toUri();
			return workspace.parseFile(fileUri, classpath, compilerOptions);
		} catch (Exception e) {
			LOG.error("Error parsing file: {}", filePath, e);
			return null;
		}
	}

	/**
	 * Convert LSP Position (line, character) to string offset.
	 */
	private int positionToOffset(String content, Position position) {
		int line = position.getLine();
		int character = position.getCharacter();

		String[] lines = content.split("\n", -1);
		if (line >= lines.length) {
			return -1;
		}

		int offset = 0;
		for (int i = 0; i < line; i++) {
			offset += lines[i].length() + 1; // +1 for newline
		}

		if (character > lines[line].length()) {
			return -1;
		}

		return offset + character;
	}

	/**
	 * Extract Name or SimpleName node from the AST node at cursor.
	 */
	private Name extractNameNode(ASTNode node) {
		if (node instanceof Name) {
			return (Name) node;
		}

		// Traverse up to find a Name node
		ASTNode current = node;
		while (current != null) {
			if (current instanceof Name) {
				return (Name) current;
			}
			current = current.getParent();
		}

		return null;
	}

	/**
	 * Find definition locations for the given binding.
	 */
	private List<Location> findDefinitionLocations(WorkspaceModel workspace, String projectName,
			IBinding binding, CompilationUnit currentCU, String filePath, String content) {

		if (binding instanceof ITypeBinding) {
			return findTypeDefinition(workspace, projectName, (ITypeBinding) binding);
		} else if (binding instanceof IMethodBinding) {
			return findMethodDefinition(workspace, projectName, (IMethodBinding) binding);
		} else if (binding instanceof IVariableBinding) {
			IVariableBinding varBinding = (IVariableBinding) binding;
			if (varBinding.isField()) {
				return findFieldDefinition(workspace, projectName, varBinding);
			} else {
				// Local variable or parameter - find in current AST
				return findLocalDeclaration(currentCU, varBinding, filePath, content);
			}
		}

		return Collections.emptyList();
	}

	/**
	 * Find type definition using search index.
	 */
	private List<Location> findTypeDefinition(WorkspaceModel workspace, String projectName, ITypeBinding typeBinding) {
		String qualifiedName = typeBinding.getQualifiedName();
		if (qualifiedName == null || qualifiedName.isEmpty()) {
			return Collections.emptyList();
		}

		LOG.debug("Searching for type definition: {}", qualifiedName);

		TypePattern pattern = new TypePattern(qualifiedName, SearchPattern.MatchRule.EXACT_MATCH,
				TypePattern.SearchFor.DECLARATIONS);

		return searchForDefinitions(workspace, projectName, pattern);
	}

	/**
	 * Find method definition using search index.
	 */
	private List<Location> findMethodDefinition(WorkspaceModel workspace, String projectName, IMethodBinding methodBinding) {
		String methodName = methodBinding.getName();
		if (methodName == null || methodName.isEmpty()) {
			return Collections.emptyList();
		}

		LOG.debug("Searching for method definition: {}", methodName);

		MethodPattern pattern = new MethodPattern(methodName, null, SearchPattern.MatchRule.EXACT_MATCH,
				MethodPattern.SearchFor.DECLARATIONS);

		return searchForDefinitions(workspace, projectName, pattern);
	}

	/**
	 * Find field definition using search index.
	 */
	private List<Location> findFieldDefinition(WorkspaceModel workspace, String projectName, IVariableBinding varBinding) {
		String fieldName = varBinding.getName();
		if (fieldName == null || fieldName.isEmpty()) {
			return Collections.emptyList();
		}

		LOG.debug("Searching for field definition: {}", fieldName);

		FieldPattern pattern = new FieldPattern(fieldName, null, SearchPattern.MatchRule.EXACT_MATCH,
				FieldPattern.SearchFor.DECLARATIONS);

		return searchForDefinitions(workspace, projectName, pattern);
	}

	/**
	 * Search for definitions using the search engine.
	 */
	private List<Location> searchForDefinitions(WorkspaceModel workspace, String projectName, SearchPattern pattern) {
		JavaIndex index = workspace.getIndexCache().getIndex();
		if (index == null) {
			LOG.warn("Index not available");
			return Collections.emptyList();
		}

		List<Location> locations = new ArrayList<>();
		SearchEngine searchEngine = new SearchEngine();

		searchEngine.search(pattern, index,
			(Path file) -> {
				// FileReader callback - read file content
				try {
					return Files.readString(file);
				} catch (IOException e) {
					LOG.error("Cannot read file: {}", file, e);
					return null;
				}
			},
			(SearchMatch match) -> {
				// SearchRequestor callback - convert match to Location
				if (match.getKind().name().contains("DECLARATION")) {
					try {
						Location location = searchMatchToLocation(match);
						locations.add(location);
					} catch (Exception e) {
						LOG.error("Error converting search match to location", e);
					}
				}
			}
		);

		return locations;
	}

	/**
	 * Find local variable or parameter declaration in the current AST.
	 * Since we're already in the context of a definition request, we have the file path.
	 */
	private List<Location> findLocalDeclaration(CompilationUnit cu, IVariableBinding varBinding, String filePath, String content) {
		// Find the declaring node in the AST
		ASTNode declaringNode = cu.findDeclaringNode(varBinding);
		if (declaringNode == null) {
			LOG.debug("Cannot find declaring node for local variable: {}", varBinding.getName());
			return Collections.emptyList();
		}

		// Get the name node from the declaration
		SimpleName nameNode = null;
		if (declaringNode instanceof VariableDeclaration) {
			nameNode = ((VariableDeclaration) declaringNode).getName();
		} else if (declaringNode instanceof SimpleName) {
			nameNode = (SimpleName) declaringNode;
		}

		if (nameNode == null) {
			return Collections.emptyList();
		}

		int offset = nameNode.getStartPosition();
		int length = nameNode.getLength();

		Position start = offsetToPosition(content, offset);
		Position end = offsetToPosition(content, offset + length);

		String uri = filePathToUri(filePath);
		Location location = new Location(uri, new Range(start, end));
		return Collections.singletonList(location);
	}

	/**
	 * Convert SearchMatch to LSP Location.
	 */
	private Location searchMatchToLocation(SearchMatch match) throws IOException {
		String filePath = match.getFile().toString();
		String content = Files.readString(match.getFile());

		int offset = match.getOffset();
		int length = match.getLength();

		Position start = offsetToPosition(content, offset);
		Position end = offsetToPosition(content, offset + length);

		String uri = filePathToUri(filePath);
		return new Location(uri, new Range(start, end));
	}

	/**
	 * Convert string offset to LSP Position (line, character).
	 */
	private Position offsetToPosition(String content, int offset) {
		String[] lines = content.split("\n", -1);
		int currentOffset = 0;

		for (int line = 0; line < lines.length; line++) {
			int lineLength = lines[line].length();
			if (currentOffset + lineLength >= offset) {
				int character = offset - currentOffset;
				return new Position(line, character);
			}
			currentOffset += lineLength + 1; // +1 for newline
		}

		// Fallback: end of document
		return new Position(lines.length - 1, lines[lines.length - 1].length());
	}

	/**
	 * Convert file path to LSP URI.
	 */
	private String filePathToUri(String filePath) {
		return Paths.get(filePath).toUri().toString();
	}

	/**
	 * LSP: Find references.
	 * Returns all locations where the symbol under the cursor is referenced.
	 */
	@Override
	public CompletableFuture<List<? extends Location>> references(ReferenceParams params) {
		String uri = params.getTextDocument().getUri();
		Position position = params.getPosition();
		boolean includeDeclaration = params.getContext().isIncludeDeclaration();
		LOG.debug("textDocument/references request for: {} at {}:{}, includeDeclaration={}",
			uri, position.getLine(), position.getCharacter(), includeDeclaration);

		try {
			// Convert URI to file path
			String filePath = uriToFilePath(uri);
			Path path = Paths.get(filePath);

			// Read file content
			String content;
			try {
				content = Files.readString(path);
			} catch (IOException e) {
				LOG.error("Cannot read file: {}", filePath, e);
				return CompletableFuture.completedFuture(Collections.emptyList());
			}

			// Get workspace model
			WorkspaceModel workspace = server.getLauncher().getWorkspaceModel();
			if (workspace == null) {
				LOG.warn("Workspace not initialized");
				return CompletableFuture.completedFuture(Collections.emptyList());
			}

			// Find which project this file belongs to
			String projectName = findProjectForFile(workspace, filePath);
			if (projectName == null) {
				LOG.debug("File is not part of any workspace project: {}", filePath);
				return CompletableFuture.completedFuture(Collections.emptyList());
			}

			// Parse file with bindings
			CompilationUnit cu = parseFileWithBindings(workspace, projectName, path);
			if (cu == null) {
				LOG.warn("Cannot parse file: {}", filePath);
				return CompletableFuture.completedFuture(Collections.emptyList());
			}

			// Convert position to offset
			int offset = positionToOffset(content, position);
			if (offset < 0) {
				LOG.debug("Invalid position: {}:{}", position.getLine(), position.getCharacter());
				return CompletableFuture.completedFuture(Collections.emptyList());
			}

			// Find node at cursor position
			ASTNode node = NodeFinder.perform(cu, offset, 0);
			if (node == null) {
				LOG.debug("No AST node at offset {}", offset);
				return CompletableFuture.completedFuture(Collections.emptyList());
			}

			// Extract Name node
			Name nameNode = extractNameNode(node);
			if (nameNode == null) {
				LOG.debug("No name node found at offset {}", offset);
				return CompletableFuture.completedFuture(Collections.emptyList());
			}

			// Resolve binding
			IBinding binding = nameNode.resolveBinding();
			if (binding == null) {
				LOG.debug("Cannot resolve binding for name: {}", nameNode.getFullyQualifiedName());
				return CompletableFuture.completedFuture(Collections.emptyList());
			}

			// Find all references to this binding
			List<Location> locations = findReferences(workspace, projectName, binding, includeDeclaration, cu, filePath, content);
			return CompletableFuture.completedFuture(locations);

		} catch (Exception e) {
			LOG.error("Error in textDocument/references for {}: {}", uri, e.getMessage(), e);
			return CompletableFuture.completedFuture(Collections.emptyList());
		}
	}

	/**
	 * Find all references to the given binding.
	 */
	private List<Location> findReferences(WorkspaceModel workspace, String projectName,
			IBinding binding, boolean includeDeclaration, CompilationUnit currentCU, String filePath, String content) {

		if (binding instanceof ITypeBinding) {
			return findTypeReferences(workspace, projectName, (ITypeBinding) binding, includeDeclaration);
		} else if (binding instanceof IMethodBinding) {
			return findMethodReferences(workspace, projectName, (IMethodBinding) binding, includeDeclaration);
		} else if (binding instanceof IVariableBinding) {
			IVariableBinding varBinding = (IVariableBinding) binding;
			if (varBinding.isField()) {
				return findFieldReferences(workspace, projectName, varBinding, includeDeclaration);
			} else {
				// Local variable or parameter - find in current AST only
				return findLocalReferences(currentCU, varBinding, filePath, content, includeDeclaration);
			}
		}

		return Collections.emptyList();
	}

	/**
	 * Find type references using search index.
	 */
	private List<Location> findTypeReferences(WorkspaceModel workspace, String projectName,
			ITypeBinding typeBinding, boolean includeDeclaration) {
		String qualifiedName = typeBinding.getQualifiedName();
		if (qualifiedName == null || qualifiedName.isEmpty()) {
			return Collections.emptyList();
		}

		LOG.debug("Searching for type references: {}", qualifiedName);

		TypePattern pattern = new TypePattern(qualifiedName, SearchPattern.MatchRule.EXACT_MATCH,
				includeDeclaration ? TypePattern.SearchFor.ALL_OCCURRENCES : TypePattern.SearchFor.REFERENCES);

		return searchForMatches(workspace, projectName, pattern, includeDeclaration);
	}

	/**
	 * Find method references using search index.
	 */
	private List<Location> findMethodReferences(WorkspaceModel workspace, String projectName,
			IMethodBinding methodBinding, boolean includeDeclaration) {
		String methodName = methodBinding.getName();
		if (methodName == null || methodName.isEmpty()) {
			return Collections.emptyList();
		}

		LOG.debug("Searching for method references: {}", methodName);

		MethodPattern pattern = new MethodPattern(methodName, null, SearchPattern.MatchRule.EXACT_MATCH,
				includeDeclaration ? MethodPattern.SearchFor.ALL_OCCURRENCES : MethodPattern.SearchFor.REFERENCES);

		return searchForMatches(workspace, projectName, pattern, includeDeclaration);
	}

	/**
	 * Find field references using search index.
	 */
	private List<Location> findFieldReferences(WorkspaceModel workspace, String projectName,
			IVariableBinding varBinding, boolean includeDeclaration) {
		String fieldName = varBinding.getName();
		if (fieldName == null || fieldName.isEmpty()) {
			return Collections.emptyList();
		}

		LOG.debug("Searching for field references: {}", fieldName);

		FieldPattern pattern = new FieldPattern(fieldName, null, SearchPattern.MatchRule.EXACT_MATCH,
				includeDeclaration ? FieldPattern.SearchFor.ALL_OCCURRENCES : FieldPattern.SearchFor.REFERENCES);

		return searchForMatches(workspace, projectName, pattern, includeDeclaration);
	}

	/**
	 * Search for all matches using the search engine.
	 */
	private List<Location> searchForMatches(WorkspaceModel workspace, String projectName,
			SearchPattern pattern, boolean includeDeclaration) {
		JavaIndex index = workspace.getIndexCache().getIndex();
		if (index == null) {
			LOG.warn("Index not available");
			return Collections.emptyList();
		}

		List<Location> locations = new ArrayList<>();
		SearchEngine searchEngine = new SearchEngine();

		searchEngine.search(pattern, index,
			(Path file) -> {
				// FileReader callback - read file content
				try {
					return Files.readString(file);
				} catch (IOException e) {
					LOG.error("Cannot read file: {}", file, e);
					return null;
				}
			},
			(SearchMatch match) -> {
				// SearchRequestor callback - convert match to Location
				// Include declarations only if requested
				if (match.getKind().name().contains("DECLARATION")) {
					if (includeDeclaration) {
						try {
							Location location = searchMatchToLocation(match);
							locations.add(location);
						} catch (Exception e) {
							LOG.error("Error converting search match to location", e);
						}
					}
				} else if (match.getKind().name().contains("REFERENCE")) {
					try {
						Location location = searchMatchToLocation(match);
						locations.add(location);
					} catch (Exception e) {
						LOG.error("Error converting search match to location", e);
					}
				}
			}
		);

		return locations;
	}

	/**
	 * Find local variable or parameter references in the current AST.
	 */
	private List<Location> findLocalReferences(CompilationUnit cu, IVariableBinding varBinding,
			String filePath, String content, boolean includeDeclaration) {
		List<Location> locations = new ArrayList<>();

		// Use a visitor to find all SimpleName nodes that reference this variable
		cu.accept(new shaded.org.eclipse.jdt.core.dom.ASTVisitor() {
			@Override
			public boolean visit(SimpleName node) {
				IBinding binding = node.resolveBinding();
				if (binding != null && binding.isEqualTo(varBinding)) {
					// Check if this is a declaration or reference
					boolean isDeclaration = node.getParent() instanceof VariableDeclaration &&
						((VariableDeclaration) node.getParent()).getName() == node;

					if (isDeclaration && !includeDeclaration) {
						return true; // Skip declarations if not requested
					}

					int offset = node.getStartPosition();
					int length = node.getLength();

					Position start = offsetToPosition(content, offset);
					Position end = offsetToPosition(content, offset + length);

					String uri = filePathToUri(filePath);
					Location location = new Location(uri, new Range(start, end));
					locations.add(location);
				}
				return true;
			}
		});

		return locations;
	}
}
