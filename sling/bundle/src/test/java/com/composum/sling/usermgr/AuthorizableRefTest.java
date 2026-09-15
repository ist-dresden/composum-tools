package com.composum.sling.usermgr;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Covers {@link AuthorizableRef#iconOf}, the tree/table icon mapping ('user'/'system-user'/
 * 'group'/'folder' -> Bootstrap Icons name) shared by the tree, the Groups/Members lists, the
 * search results and the "Affected Paths" table's own leading type column (via
 * {@code AffectedPathEntry#principalIcon}) - the one small piece of {@link AuthorizableRef} that
 * is pure logic, independent of any JCR node.
 */
public class AuthorizableRefTest {

    @Test
    public void iconOf_knownTypes() {
        assertEquals("person", AuthorizableRef.iconOf("user"));
        assertEquals("gear", AuthorizableRef.iconOf("system-user"));
        assertEquals("people", AuthorizableRef.iconOf("group"));
        assertEquals("folder", AuthorizableRef.iconOf("folder"));
    }

    @Test
    public void iconOf_unknownTypeFallsBackToFolder() {
        // a defensive default, not expected to be hit in practice - #typeOf never produces
        // anything but the four known types
        assertEquals("folder", AuthorizableRef.iconOf("something-else"));
    }
}
