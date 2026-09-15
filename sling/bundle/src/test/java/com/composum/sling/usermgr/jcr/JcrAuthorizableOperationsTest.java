package com.composum.sling.usermgr.jcr;

import org.junit.jupiter.api.Test;

import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Covers the pure, JCR/Sling-independent logic of {@link JcrAuthorizableOperations} - the
 * wildcard-pattern translation the search bar and the Add-to-Group/Add-Member autocomplete both
 * rely on, and the ACL restriction-value formatting the "Affected Paths" tab/search results use.
 * Everything else in that class needs a real (or mocked) repository and is exercised manually
 * against a running instance instead - see the plan file's smoke-test checklists.
 */
public class JcrAuthorizableOperationsTest {

    @Test
    public void wildcardToSqlLike_plainTextIsWrappedAsContains() {
        // no '*'/'?' at all - implicit "contains" search, this class's original, simpler behavior
        assertEquals("%admin%", JcrAuthorizableOperations.wildcardToSqlLike("admin"));
    }

    @Test
    public void wildcardToSqlLike_translatesWildcards() {
        assertEquals("gf-%-editor", JcrAuthorizableOperations.wildcardToSqlLike("gf-*-editor"));
        assertEquals("test-gf_-admin", JcrAuthorizableOperations.wildcardToSqlLike("test-gf?-admin"));
        assertEquals("%", JcrAuthorizableOperations.wildcardToSqlLike("*"));
    }

    @Test
    public void wildcardToSqlLike_escapesLiteralLikeMetacharacters() {
        // a literal '%'/'_'/'\' in the *input* must not be misread as a LIKE wildcard once
        // translated - each has to come out backslash-escaped; every pattern here already
        // contains an explicit '*' so the implicit "wrap as contains" behavior (see the test
        // above) does not also come into play, keeping this test focused on escaping alone
        assertEquals("\\%wildcard\\%%", JcrAuthorizableOperations.wildcardToSqlLike("%wildcard%*"));
        assertEquals("a\\_b%", JcrAuthorizableOperations.wildcardToSqlLike("a_b*"));
        assertEquals("a\\\\b%", JcrAuthorizableOperations.wildcardToSqlLike("a\\b*"));
    }

    @Test
    public void wildcardToRegex_plainTextMatchesAsContains() {
        final Pattern pattern = JcrAuthorizableOperations.wildcardToRegex("dam");
        assertTrue(pattern.matcher("/content/dam/projects").matches());
        assertFalse(pattern.matcher("/content/other").matches());
    }

    @Test
    public void wildcardToRegex_translatesWildcardsAndAnchorsFully() {
        final Pattern pattern = JcrAuthorizableOperations.wildcardToRegex("/content/*/en");
        assertTrue(pattern.matcher("/content/site/en").matches());
        assertTrue(pattern.matcher("/content/a/b/c/en").matches());
        // '.matches()' requires the *whole* string to match - a partial hit must not pass
        assertFalse(pattern.matcher("/content/site/en/jcr:content").matches());
        assertFalse(pattern.matcher("/content/site/de").matches());
    }

    @Test
    public void wildcardToRegex_singleCharWildcard() {
        final Pattern pattern = JcrAuthorizableOperations.wildcardToRegex("/content/pag?");
        assertTrue(pattern.matcher("/content/page").matches());
        assertFalse(pattern.matcher("/content/pages").matches());
    }

    @Test
    public void wildcardToRegex_escapesRegexMetacharactersInLiteralText() {
        // '.', '(', ')' etc. in the pattern are literal characters here, not regex syntax -
        // without proper escaping this would either fail to match the literal dot or (worse)
        // silently match something it shouldn't (e.g. '.' matching any character)
        final Pattern pattern = JcrAuthorizableOperations.wildcardToRegex("*(test).json");
        assertTrue(pattern.matcher("/apps/(test).json").matches());
        assertFalse(pattern.matcher("/apps/Xtest.json").matches());
    }

    @Test
    public void wildcardToRegex_isCaseInsensitive() {
        final Pattern pattern = JcrAuthorizableOperations.wildcardToRegex("/content/*");
        assertTrue(pattern.matcher("/CONTENT/Site").matches());
    }

    @Test
    public void hasWildcard_detectsEitherWildcardCharacter() {
        assertTrue(JcrAuthorizableOperations.hasWildcard("a*b"));
        assertTrue(JcrAuthorizableOperations.hasWildcard("a?b"));
        assertFalse(JcrAuthorizableOperations.hasWildcard("plain"));
    }

    @Test
    public void formatRestrictionValue_plainScalar() {
        final JcrAuthorizableOperations operations = new JcrAuthorizableOperations();
        assertEquals("/content/*", operations.formatRestrictionValue("/content/*"));
        assertEquals("true", operations.formatRestrictionValue(Boolean.TRUE));
    }

    @Test
    public void formatRestrictionValue_multiValueIsBracketedAndCommaJoined() {
        final JcrAuthorizableOperations operations = new JcrAuthorizableOperations();
        final Object[] values = {"/global/site-templates/", "/settings/wcm/", "/sling/configs/"};
        assertEquals("[/global/site-templates/,/settings/wcm/,/sling/configs/]",
                operations.formatRestrictionValue(values));
    }

    @Test
    public void formatRestrictionValue_emptyArray() {
        final JcrAuthorizableOperations operations = new JcrAuthorizableOperations();
        assertEquals("[]", operations.formatRestrictionValue(new Object[0]));
    }
}
