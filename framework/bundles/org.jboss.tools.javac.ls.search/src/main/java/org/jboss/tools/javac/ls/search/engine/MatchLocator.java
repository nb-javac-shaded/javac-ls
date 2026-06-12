package org.jboss.tools.javac.ls.search.engine;

import java.nio.file.Path;
import java.util.List;

import org.jboss.tools.javac.ls.parser.bindings.JavacDOMParser;
import org.jboss.tools.javac.ls.search.match.MatchingNodeSet;
import org.jboss.tools.javac.ls.search.match.SearchMatch;
import org.jboss.tools.javac.ls.search.match.SearchRequestor;
import org.jboss.tools.javac.ls.search.pattern.SearchPattern;
import org.jboss.tools.javac.ls.search.visitor.DOMSearchVisitor;

import shaded.org.eclipse.jdt.core.dom.AST;
import shaded.org.eclipse.jdt.core.dom.ASTNode;
import shaded.org.eclipse.jdt.core.dom.CompilationUnit;

/**
 * Locates matches in source files by parsing them and applying search patterns.
 *
 * This class is stateless and can be reused for multiple search operations.
 * It parses Java source files, traverses their AST with a DOMSearchVisitor,
 * and reports matches to a SearchRequestor.
 */
public class MatchLocator {

    private final JavacDOMParser parser;

    public MatchLocator() {
        this.parser = new JavacDOMParser();
    }

    /**
     * Locates matches in a single file.
     *
     * @param file the file to search
     * @param source the source code content
     * @param pattern the search pattern to match
     * @param requestor the callback to receive matches
     */
    public void locateMatches(Path file, String source, SearchPattern pattern, SearchRequestor requestor) {
        // Parse the source file to AST
        CompilationUnit cu = parser.parse(source, file.toString(), null, AST.JLS21, null, false);
        if (cu == null) {
            return;
        }

        // Visit the AST and collect matching nodes
        MatchingNodeSet matchingNodes = new MatchingNodeSet();
        DOMSearchVisitor visitor = new DOMSearchVisitor(pattern, matchingNodes);
        cu.accept(visitor);

        // Convert matching nodes to SearchMatch objects and report them
        if (matchingNodes.hasMatches()) {
            reportMatches(file, cu, matchingNodes, requestor);
        }
    }

    /**
     * Locates matches in multiple files.
     *
     * @param files the files to search (path and source pairs)
     * @param pattern the search pattern to match
     * @param requestor the callback to receive matches
     */
    public void locateMatches(List<FileContent> files, SearchPattern pattern, SearchRequestor requestor) {
        for (FileContent fileContent : files) {
            locateMatches(fileContent.getFile(), fileContent.getSource(), pattern, requestor);
        }
    }

    /**
     * Converts matching AST nodes to SearchMatch objects and reports them.
     */
    private void reportMatches(Path file, CompilationUnit cu, MatchingNodeSet matchingNodes, SearchRequestor requestor) {
        for (ASTNode node : matchingNodes.getMatchingNodes()) {
            SearchMatch.MatchKind kind = matchingNodes.getMatchKind(node);
            if (kind == null) {
                continue;
            }

            // Get node position in source
            int startPosition = node.getStartPosition();
            int length = node.getLength();

            if (startPosition < 0 || length < 0) {
                continue;
            }

            // Extract element name from the node
            String elementName = extractElementName(node);

            // Create and report the match
            SearchMatch match = new SearchMatch(file, startPosition, length, kind, elementName);
            requestor.acceptMatch(match);
        }
    }

    /**
     * Extracts a human-readable element name from an AST node.
     */
    private String extractElementName(ASTNode node) {
        String nodeString = node.toString();
        // Limit name length for readability
        if (nodeString.length() > 50) {
            nodeString = nodeString.substring(0, 47) + "...";
        }
        return nodeString;
    }

    /**
     * Container for file path and source content.
     */
    public static class FileContent {
        private final Path file;
        private final String source;

        public FileContent(Path file, String source) {
            this.file = file;
            this.source = source;
        }

        public Path getFile() {
            return file;
        }

        public String getSource() {
            return source;
        }
    }
}
