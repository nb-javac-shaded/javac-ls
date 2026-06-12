package org.jboss.tools.javac.ls.search.engine.test;

import static org.junit.Assert.*;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.jboss.tools.javac.ls.index.model.Location;
import org.jboss.tools.javac.ls.index.model.ReferenceEntry;
import org.jboss.tools.javac.ls.index.model.ReferenceEntry.ReferenceKind;
import org.jboss.tools.javac.ls.index.store.JavaIndex;
import org.jboss.tools.javac.ls.search.engine.MatchLocator;
import org.jboss.tools.javac.ls.search.engine.SearchEngine;
import org.jboss.tools.javac.ls.search.match.SearchMatch;
import org.jboss.tools.javac.ls.search.pattern.TypePattern;
import org.junit.Before;
import org.junit.Test;

public class SearchEngineTest {

    private SearchEngine searchEngine;
    private JavaIndex index;
    private Map<Path, String> fileContents;
    private List<SearchMatch> collectedMatches;

    @Before
    public void setUp() {
        searchEngine = new SearchEngine();
        index = new JavaIndex();
        fileContents = new HashMap<>();
        collectedMatches = new ArrayList<>();

        // Set up test files
        Path file1 = Paths.get("/test/File1.java");
        Path file2 = Paths.get("/test/File2.java");
        Path file3 = Paths.get("/test/File3.java");

        fileContents.put(file1, "public class MyClass { }");
        fileContents.put(file2, "public class Other { private MyClass field; }");
        fileContents.put(file3, "public class Unrelated { }");

        // Index type references
        index.addTypeReference("MyClass", new ReferenceEntry(
            new Location(file2, 30, 37, 1, 30),
            ReferenceKind.TYPE_REFERENCE
        ));
    }

    @Test
    public void testSearchWithIndex() {
        TypePattern pattern = new TypePattern("MyClass", null, TypePattern.SearchFor.ALL_OCCURRENCES);

        searchEngine.search(pattern, index, fileContents::get, collectedMatches::add);

        // Should find MyClass in File2 (indexed reference)
        assertTrue(collectedMatches.size() > 0);
        assertTrue(collectedMatches.stream()
            .anyMatch(m -> m.getFile().equals(Paths.get("/test/File2.java"))));
    }

    @Test
    public void testSearchInSpecificFiles() {
        List<MatchLocator.FileContent> files = List.of(
            new MatchLocator.FileContent(Paths.get("/test/File1.java"), "public class MyClass { }"),
            new MatchLocator.FileContent(Paths.get("/test/File3.java"), "public class Unrelated { }")
        );

        TypePattern pattern = new TypePattern("MyClass", null, TypePattern.SearchFor.DECLARATIONS);
        searchEngine.searchInFiles(files, pattern, collectedMatches::add);

        assertEquals(1, collectedMatches.size());
        assertEquals(Paths.get("/test/File1.java"), collectedMatches.get(0).getFile());
    }

    @Test
    public void testSearchWithEmptyIndex() {
        JavaIndex emptyIndex = new JavaIndex();
        TypePattern pattern = new TypePattern("MyClass", null, TypePattern.SearchFor.ALL_OCCURRENCES);

        searchEngine.search(pattern, emptyIndex, fileContents::get, collectedMatches::add);

        // No index entries means no candidate files, so no matches
        assertEquals(0, collectedMatches.size());
    }

    @Test
    public void testFileReaderReturnsNull() {
        TypePattern pattern = new TypePattern("MyClass", null, TypePattern.SearchFor.ALL_OCCURRENCES);

        // File reader that always returns null
        searchEngine.search(pattern, index, (path) -> null, collectedMatches::add);

        // Should handle null gracefully and find no matches
        assertEquals(0, collectedMatches.size());
    }

    @Test
    public void testSearchWithNullPattern() {
        try {
            searchEngine.search(null, index, fileContents::get, collectedMatches::add);
            // Implementation may allow null or throw - either is acceptable
        } catch (NullPointerException e) {
            // Acceptable
        }
    }

    @Test
    public void testSearchWithEmptySearchString() {
        TypePattern pattern = new TypePattern("", null, TypePattern.SearchFor.ALL_OCCURRENCES);

        searchEngine.search(pattern, index, fileContents::get, collectedMatches::add);

        // Empty search string should return no results
        assertEquals(0, collectedMatches.size());
    }
}
