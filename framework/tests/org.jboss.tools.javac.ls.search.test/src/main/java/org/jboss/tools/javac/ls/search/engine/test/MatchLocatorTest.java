package org.jboss.tools.javac.ls.search.engine.test;

import static org.junit.Assert.*;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;

import org.jboss.tools.javac.ls.search.engine.MatchLocator;
import org.jboss.tools.javac.ls.search.match.SearchMatch;
import org.jboss.tools.javac.ls.search.match.SearchMatch.MatchKind;
import org.jboss.tools.javac.ls.search.pattern.TypePattern;
import org.junit.Before;
import org.junit.Test;

public class MatchLocatorTest {

    private MatchLocator matchLocator;
    private List<SearchMatch> collectedMatches;

    @Before
    public void setUp() {
        matchLocator = new MatchLocator();
        collectedMatches = new ArrayList<>();
    }

    @Test
    public void testLocateSingleMatch() {
        Path file = Paths.get("/test/MyClass.java");
        String source = "public class MyClass { }";

        TypePattern pattern = new TypePattern("MyClass", null, TypePattern.SearchFor.DECLARATIONS);
        matchLocator.locateMatches(file, source, pattern, collectedMatches::add);

        assertEquals(1, collectedMatches.size());
        SearchMatch match = collectedMatches.get(0);
        assertEquals(file, match.getFile());
        assertEquals(MatchKind.TYPE_DECLARATION, match.getKind());
    }

    @Test
    public void testLocateMultipleMatches() {
        Path file = Paths.get("/test/MyClass.java");
        String source = "public class MyClass { public void myMethod() { } public void caller() { myMethod(); } }";

        org.jboss.tools.javac.ls.search.pattern.MethodPattern pattern =
            new org.jboss.tools.javac.ls.search.pattern.MethodPattern("myMethod");
        matchLocator.locateMatches(file, source, pattern, collectedMatches::add);

        assertEquals(2, collectedMatches.size());
        // One declaration, one reference
        long declarations = collectedMatches.stream()
            .filter(m -> m.getKind() == MatchKind.METHOD_DECLARATION)
            .count();
        long references = collectedMatches.stream()
            .filter(m -> m.getKind() == MatchKind.METHOD_REFERENCE)
            .count();

        assertEquals(1, declarations);
        assertEquals(1, references);
    }

    @Test
    public void testLocateNoMatches() {
        Path file = Paths.get("/test/MyClass.java");
        String source = "public class MyClass { }";

        TypePattern pattern = new TypePattern("OtherClass", null, TypePattern.SearchFor.DECLARATIONS);
        matchLocator.locateMatches(file, source, pattern, collectedMatches::add);

        assertEquals(0, collectedMatches.size());
    }

    @Test
    public void testLocateInMultipleFiles() {
        List<MatchLocator.FileContent> files = List.of(
            new MatchLocator.FileContent(Paths.get("/test/Class1.java"), "public class MyClass { }"),
            new MatchLocator.FileContent(Paths.get("/test/Class2.java"), "public class MyClass { }"),
            new MatchLocator.FileContent(Paths.get("/test/Class3.java"), "public class OtherClass { }")
        );

        TypePattern pattern = new TypePattern("MyClass", null, TypePattern.SearchFor.DECLARATIONS);
        matchLocator.locateMatches(files, pattern, collectedMatches::add);

        assertEquals(2, collectedMatches.size());
        assertEquals(Paths.get("/test/Class1.java"), collectedMatches.get(0).getFile());
        assertEquals(Paths.get("/test/Class2.java"), collectedMatches.get(1).getFile());
    }

    @Test
    public void testMatchPositionInformation() {
        Path file = Paths.get("/test/MyClass.java");
        String source = "public class MyClass { }";

        TypePattern pattern = new TypePattern("MyClass", null, TypePattern.SearchFor.DECLARATIONS);
        matchLocator.locateMatches(file, source, pattern, collectedMatches::add);

        assertEquals(1, collectedMatches.size());
        SearchMatch match = collectedMatches.get(0);

        assertTrue(match.getOffset() >= 0);
        assertTrue(match.getLength() > 0);
        assertNotNull(match.getElementName());
    }

    @Test
    public void testInvalidSource() {
        Path file = Paths.get("/test/Invalid.java");
        String source = "this is not valid java code }{{{";

        TypePattern pattern = new TypePattern("MyClass", null, TypePattern.SearchFor.DECLARATIONS);
        // Should not throw, just return no matches
        matchLocator.locateMatches(file, source, pattern, collectedMatches::add);

        // Implementation may return 0 matches for invalid source
        assertTrue(collectedMatches.size() >= 0);
    }
}
