package org.jboss.tools.javac.ls.search.engine;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import org.jboss.tools.javac.ls.index.model.FieldDeclarationEntry;
import org.jboss.tools.javac.ls.index.model.MethodDeclarationEntry;
import org.jboss.tools.javac.ls.index.model.ReferenceEntry;
import org.jboss.tools.javac.ls.index.store.JavaIndex;
import org.jboss.tools.javac.ls.search.match.SearchRequestor;
import org.jboss.tools.javac.ls.search.pattern.ConstructorPattern;
import org.jboss.tools.javac.ls.search.pattern.FieldPattern;
import org.jboss.tools.javac.ls.search.pattern.MethodPattern;
import org.jboss.tools.javac.ls.search.pattern.SearchPattern;
import org.jboss.tools.javac.ls.search.pattern.TypePattern;

/**
 * Main entry point for search operations.
 *
 * This class orchestrates the search process:
 * 1. Query the index to get candidate files (fast, imprecise)
 * 2. Parse candidate files and match against patterns (slow, precise)
 *
 * The engine is stateless and requires the JavaIndex to be passed in for each search.
 */
public class SearchEngine {

    private final MatchLocator matchLocator;

    public SearchEngine() {
        this.matchLocator = new MatchLocator();
    }

    /**
     * Searches for matches in the workspace.
     *
     * @param pattern the search pattern
     * @param index the Java index to query for candidate files
     * @param fileReader callback to read file contents
     * @param requestor callback to receive matches
     */
    public void search(SearchPattern pattern, JavaIndex index, FileReader fileReader, SearchRequestor requestor) {
        // Phase 1: Query index for candidate files
        Collection<Path> candidateFiles = queryIndex(pattern, index);

        if (candidateFiles.isEmpty()) {
            return;
        }

        // Phase 2: Parse candidate files and find precise matches
        List<MatchLocator.FileContent> fileContents = new ArrayList<>();
        for (Path file : candidateFiles) {
            String source = fileReader.readFile(file);
            if (source != null) {
                fileContents.add(new MatchLocator.FileContent(file, source));
            }
        }

        matchLocator.locateMatches(fileContents, pattern, requestor);
    }

    /**
     * Searches in a specific set of files (bypasses index).
     *
     * @param files the files to search
     * @param pattern the search pattern
     * @param requestor callback to receive matches
     */
    public void searchInFiles(List<MatchLocator.FileContent> files, SearchPattern pattern, SearchRequestor requestor) {
        matchLocator.locateMatches(files, pattern, requestor);
    }

    /**
     * Queries the index for candidate files that might contain matches.
     *
     * This is a fast, imprecise operation that narrows down the files to search.
     */
    private Collection<Path> queryIndex(SearchPattern pattern, JavaIndex index) {
        String searchString = pattern.getSearchString();
        if (searchString == null || searchString.isEmpty()) {
            return List.of();
        }

        Set<Path> candidates = new HashSet<>();

        if (pattern instanceof TypePattern) {
            // Find files that reference or use this type
            addFilesFromReferences(index.findTypeUsages(searchString), candidates);
            addFilesFromReferences(index.findNameUsages(searchString), candidates);
        } else if (pattern instanceof MethodPattern) {
            // Find files that reference or declare methods with this name
            addFilesFromReferences(index.findNameUsages(searchString), candidates);
        } else if (pattern instanceof FieldPattern) {
            // Find files that reference or declare fields with this name
            addFilesFromReferences(index.findNameUsages(searchString), candidates);
        } else if (pattern instanceof ConstructorPattern) {
            // Find files that reference this constructor (by type name)
            addFilesFromReferences(index.findTypeUsages(searchString), candidates);
            addFilesFromReferences(index.findNameUsages(searchString), candidates);
        }

        return candidates;
    }

    /**
     * Extracts file paths from reference entries and adds them to the candidate set.
     */
    private void addFilesFromReferences(Collection<ReferenceEntry> references, Set<Path> candidates) {
        for (ReferenceEntry ref : references) {
            if (ref.getLocation() != null && ref.getLocation().getFile() != null) {
                candidates.add(ref.getLocation().getFile());
            }
        }
    }

    /**
     * Callback interface for reading file contents.
     */
    @FunctionalInterface
    public interface FileReader {
        /**
         * Reads the content of a file.
         *
         * @param file the file to read
         * @return the file content, or null if the file cannot be read
         */
        String readFile(Path file);
    }
}
