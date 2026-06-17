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
import org.junit.Ignore;
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

    /**
     * This test demonstrates a critical deficiency: we cannot distinguish between
     * two types with the same simple name in different packages.
     *
     * Without binding resolution, "Shape" in CircleImpl could refer to either
     * com.example.Shape or com.other.Shape, but we can't tell which one.
     *
     * Expected behavior (with bindings): Only match com.example.Shape
     * Current behavior (without bindings): Matches both declarations incorrectly
     *
     * TODO: Remove @Ignore once we enable binding resolution in MatchLocator
     * See MatchLocator.java line 42 - currently passes false for resolveBindings
     *
     * UPDATE: Binding resolution now enabled - test should pass
     */
    @Test
    public void testCannotDistinguishTypesWithSameSimpleName() {
        // Set up three files with two different "Shape" types
        Path exampleShape = Paths.get("/test/com/example/Shape.java");
        Path otherShape = Paths.get("/test/com/other/Shape.java");
        Path circleImpl = Paths.get("/test/com/example/CircleImpl.java");

        String exampleShapeSource =
            "package com.example;\n" +
            "public class Shape {\n" +
            "    public void draw() {}\n" +
            "}";

        String otherShapeSource =
            "package com.other;\n" +
            "public class Shape {\n" +
            "    public void render() {}\n" +
            "}";

        // CircleImpl uses com.example.Shape (same package, no import needed)
        String circleImplSource =
            "package com.example;\n" +
            "public class CircleImpl {\n" +
            "    private Shape shape;  // This refers to com.example.Shape\n" +
            "}";

        List<MatchLocator.FileContent> files = List.of(
            new MatchLocator.FileContent(exampleShape, exampleShapeSource),
            new MatchLocator.FileContent(otherShape, otherShapeSource),
            new MatchLocator.FileContent(circleImpl, circleImplSource)
        );

        // Search for all occurrences of "Shape"
        TypePattern pattern = new TypePattern("Shape", null, TypePattern.SearchFor.ALL_OCCURRENCES);
        searchEngine.searchInFiles(files, pattern, collectedMatches::add);

        // We should find 3 matches total:
        // 1. Declaration of com.example.Shape
        // 2. Declaration of com.other.Shape
        // 3. Reference to Shape in CircleImpl
        assertTrue("Should find at least 3 matches", collectedMatches.size() >= 3);

        // Count matches by file
        long exampleShapeMatches = collectedMatches.stream()
            .filter(m -> m.getFile().equals(exampleShape))
            .count();
        long otherShapeMatches = collectedMatches.stream()
            .filter(m -> m.getFile().equals(otherShape))
            .count();
        long circleImplMatches = collectedMatches.stream()
            .filter(m -> m.getFile().equals(circleImpl))
            .count();

        // Both Shape files have declarations
        assertEquals("com.example.Shape should have 1 declaration", 1, exampleShapeMatches);
        assertEquals("com.other.Shape should have 1 declaration", 1, otherShapeMatches);
        assertEquals("CircleImpl should have 1 reference to Shape", 1, circleImplMatches);

        // THE DEFICIENCY: Without binding resolution, we cannot determine that
        // CircleImpl's "Shape" reference specifically refers to com.example.Shape
        // and NOT to com.other.Shape.
        //
        // To fix this, we need to:
        // 1. Enable binding resolution in MatchLocator (line 42: pass true instead of false)
        // 2. Update TypePattern to use ITypeBinding.getQualifiedName() for comparison
        // 3. Support fully-qualified patterns like "com.example.Shape"

        System.out.println("\n=== DEFICIENCY DEMONSTRATION ===");
        System.out.println("Found " + collectedMatches.size() + " matches for 'Shape':");
        for (SearchMatch match : collectedMatches) {
            System.out.println("  - " + match.getFile().getFileName() + " at offset " + match.getOffset());
        }
        System.out.println("\nWITHOUT binding resolution, we cannot determine that CircleImpl's 'Shape'");
        System.out.println("refers specifically to com.example.Shape and NOT com.other.Shape.");
        System.out.println("Both Shape declarations match the simple name 'Shape' equally.");
        System.out.println("================================\n");

        // This assertion will FAIL until we fix the issue:
        // We should be able to search for "com.example.Shape" specifically
        // and NOT match com.other.Shape
        TypePattern qualifiedPattern = new TypePattern("com.example.Shape", null, TypePattern.SearchFor.ALL_OCCURRENCES);
        List<SearchMatch> qualifiedMatches = new ArrayList<>();
        searchEngine.searchInFiles(files, qualifiedPattern, qualifiedMatches::add);

        // With proper FQDN matching, we should only find matches in:
        // - exampleShape (the declaration of com.example.Shape)
        // - circleImpl (the reference to com.example.Shape)
        // We should NOT match otherShape (com.other.Shape)
        long incorrectMatches = qualifiedMatches.stream()
            .filter(m -> m.getFile().equals(otherShape))
            .count();

        // TODO: This assertion currently FAILS because we match simple names only
        // Once we enable binding resolution, this should pass
        assertEquals("Searching for 'com.example.Shape' should NOT match com.other.Shape",
            0, incorrectMatches);
    }
}
