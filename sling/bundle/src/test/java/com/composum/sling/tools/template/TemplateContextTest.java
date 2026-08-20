package com.composum.sling.tools.template;

import com.composum.sling.tools.template.TemplateContext.Values;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

public class TemplateContextTest {

    @Test
    public void simpleValueAccess() {
        TemplateContext context = new TemplateContext(new Values()
                .with("title", "Hello")
                .with("count", 5)
                .with("flag", Boolean.TRUE));
        assertEquals("Hello", context.getValue("title"));
        assertEquals(5, context.getValue("count"));
        assertEquals(Boolean.TRUE, context.getValue("flag"));
        assertNull(context.getValue("missing"));
        assertEquals("default", context.getValue("missing", "default"));
        assertEquals("Hello", context.getValue("title", "default"));
    }

    @Test
    public void hierarchicalKeys() {
        TemplateContext context = new TemplateContext(new Values()
                .with("page.meta.title", "T")
                .with("pages", Map.of("home", Map.of("title", "Home"))));
        assertEquals("T", context.getValue("page.meta.title"));
        assertInstanceOf(Map.class, context.getValue("page.meta"));
        // '[name]' segments are equivalent to '.name' segments, any Map is traversed
        assertEquals("Home", context.getValue("pages[home].title"));
        assertEquals("Home", context.getValue("pages.home.title"));
        assertNull(context.getValue("pages.home.missing"));
        assertNull(context.getValue("pages.missing.title"));
    }

    @Test
    public void bracketKeysOnWrite() {
        TemplateContext context = new TemplateContext(new Values()
                .with("pages[home].title", "Home"));
        assertEquals("Home", context.getValue("pages.home.title"));
        assertEquals("Home", context.getValue("pages[home][title]"));
    }

    @Test
    public void supplierValues() {
        TemplateContext context = new TemplateContext(new Values()
                .with("lazy", (Supplier<?>) () -> "computed")
                .with("chain", (Supplier<?>) () -> (Supplier<?>) () -> "chained")
                .with("tree", (Supplier<?>) () -> Map.of("leaf", (Supplier<?>) () -> "deep")));
        assertEquals("computed", context.getValue("lazy"));
        assertEquals("chained", context.getValue("chain"));
        // suppliers are also evaluated at intermediate steps of a key path
        assertEquals("deep", context.getValue("tree.leaf"));
    }

    @Test
    public void valueStack() {
        TemplateContext context = new TemplateContext(new Values()
                .with("key", "base")
                .with("other", "o"));
        context.push(new Values().with("key", "override"));
        assertEquals("override", context.getValue("key"));
        assertEquals("o", context.getValue("other")); // falls through to the lower stack entry
        Values popped = context.pop();
        assertNotNull(popped);
        assertEquals("override", popped.get("key"));
        assertEquals("base", context.getValue("key"));
        assertNotNull(context.pop());
        assertNull(context.pop()); // the stack is empty now
        assertNull(context.getValue("key"));
    }

    @Test
    public void parentChain() {
        TemplateContext root = new TemplateContext(new Values()
                .with("inherited", "root")
                .with("shadowed", "root"));
        TemplateContext child = new TemplateContext(root, new Values()
                .with("shadowed", "child")
                .with("local", "child"));
        assertEquals("root", child.getValue("inherited"));
        assertEquals("child", child.getValue("shadowed"));
        assertEquals("child", child.getValue("local"));
        assertNull(root.getValue("local")); // the parent is not affected by the child
        TemplateContext grandchild = new TemplateContext(child);
        assertEquals("root", grandchild.getValue("inherited"));
        assertEquals("child", grandchild.getValue("shadowed"));
    }

    @Test
    public void deepMerge() {
        TemplateContext context = new TemplateContext(new Values()
                .with("cfg", new Values().with("a", "1").with("deep.x", "X"))
                .with("cfg", new Values().with("a", "overridden").with("b", "2").with("deep.y", "Y")));
        assertEquals("overridden", context.getValue("cfg.a"));
        assertEquals("2", context.getValue("cfg.b"));
        assertEquals("X", context.getValue("cfg.deep.x"));
        assertEquals("Y", context.getValue("cfg.deep.y"));
    }

    @Test
    public void bulkWithAndNullValues() {
        Map<String, Object> bulk = new HashMap<>();
        bulk.put("a", "A");
        bulk.put("b.c", "BC");
        Values values = new Values()
                .with(bulk)
                .with("ignored", null);
        TemplateContext context = new TemplateContext(values);
        assertEquals("A", context.getValue("a"));
        assertEquals("BC", context.getValue("b.c"));
        assertFalse(values.containsKey("ignored")); // 'null' values are not stored
    }

    @Test
    public void overspecifiedPathYieldsNull() {
        // a key path that runs into a scalar before it is fully consumed must not resolve
        // to that intermediate value
        TemplateContext context = new TemplateContext(new Values().with("a.b", "scalar"));
        assertNull(context.getValue("a.b.c"));
    }

    @Test
    public void overspecifiedPathFallsThroughToParent() {
        // a partially resolvable key must fall through to the parent context instead of being
        // shadowed by an unrelated scalar value of the same first segment in the child
        TemplateContext root = new TemplateContext(new Values().with("a.b", "fromParent"));
        TemplateContext child = new TemplateContext(root, new Values().with("a", "scalar"));
        assertEquals("fromParent", child.getValue("a.b"));
    }

    @Test
    public void withWrapsForeignIntermediateMap() {
        // an intermediate step that is a plain (non-Values) Map must be copied into a Values
        // instance rather than throwing a ClassCastException
        Values values = new Values()
                .with("cfg", Map.of("a", "1"))
                .with("cfg.b", "2");
        TemplateContext context = new TemplateContext(values);
        assertEquals("1", context.getValue("cfg.a"));
        assertEquals("2", context.getValue("cfg.b"));
    }

    @Test
    public void withReplacesScalarIntermediateStep() {
        // an intermediate step that is a scalar (not a map at all) is replaced by a fresh Values
        // map rather than throwing a ClassCastException; the scalar itself is lost
        Values values = new Values()
                .with("a", "scalar")
                .with("a.b", "nested");
        TemplateContext context = new TemplateContext(values);
        assertEquals("nested", context.getValue("a.b"));
    }

    @Test
    public void mergeWithImmutableMap() {
        // merging into or with an immutable Map (e.g. Map.of(...)) must not attempt to mutate it
        Values values = new Values()
                .with("cfg", Map.of("a", "1", "b", "2"))
                .with("cfg", Map.of("b", "overridden", "c", "3"));
        TemplateContext context = new TemplateContext(values);
        assertEquals("1", context.getValue("cfg.a"));
        assertEquals("overridden", context.getValue("cfg.b"));
        assertEquals("3", context.getValue("cfg.c"));
    }

    @Test
    public void mergeDeepIntoImmutableNestedMap() {
        // the deep-merge recursion must also cope with immutable maps found at nested levels
        Values values = new Values()
                .with("cfg", Map.of("nested", Map.of("x", "1")))
                .with("cfg", Map.of("nested", Map.of("y", "2")));
        TemplateContext context = new TemplateContext(values);
        assertEquals("1", context.getValue("cfg.nested.x"));
        assertEquals("2", context.getValue("cfg.nested.y"));
    }

    @Test
    public void defaultValueTypeCheckAcceptsMatchingRuntimeType() {
        TemplateContext context = new TemplateContext(new Values().with("count", 5));
        assertEquals(5, context.getValue("count", 0));
    }

    @Test
    public void defaultValueTypeCheckFallsBackOnTypeMismatch() {
        // the context holds a String for 'count', but an Integer default is requested - the
        // wrongly typed value must not be returned (and must not throw a ClassCastException)
        TemplateContext context = new TemplateContext(new Values().with("count", "not-a-number"));
        assertEquals(42, context.getValue("count", 42));
    }

    @Test
    public void defaultValueTypeCheckAcceptsResolvedSubtypeOfDefaultClass() {
        // the check is a genuine 'isAssignableFrom' test: a resolved value whose class is a
        // subclass of the default's class is accepted
        class Animal {
        }
        class Dog extends Animal {
        }
        Dog dog = new Dog();
        TemplateContext context = new TemplateContext(new Values().with("pet", dog));
        assertEquals(dog, context.getValue("pet", new Animal()));
    }

    @Test
    public void defaultValueTypeCheckRejectsSiblingImplementationOfSameInterface() {
        // caution: the match is against the default's concrete runtime class, not against a
        // common interface - a LinkedList value is not accepted for an ArrayList default even
        // though both implement List
        TemplateContext context = new TemplateContext(new Values()
                .with("list", new LinkedList<>(List.of("a", "b"))));
        List<String> fallback = new ArrayList<>(List.of("fallback"));
        assertEquals(fallback, context.getValue("list", fallback));
    }
}
