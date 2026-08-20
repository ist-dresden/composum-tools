package com.composum.sling.tools.template;

import org.apache.commons.lang3.StringUtils;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Supplier;

/**
 * The hierarchical set of values used to resolve the '${...}' placeholder expressions of a
 * {@link TemplateReader}. A context consists of a stack of {@link Values} maps and an optional
 * parent context.
 *
 * <h2>Key syntax</h2>
 * A key addresses a value in the nested value maps using '.' and/or '[...]' as segment separators;
 * a '[name]' segment is equivalent to a '.name' segment, e.g. 'pages[home].title' and
 * 'pages.home.title' address the same value (a bracket segment must not contain a '.' itself).
 * Only maps are traversed - list or array elements cannot be addressed by an index.
 *
 * <h2>Value resolution</h2>
 * {@link #getValue(String)} inspects the {@link Values} maps of the local stack from top to
 * bottom; the first map yielding a non-null result determines the value. A key that cannot be
 * resolved locally is delegated to the parent context. A value implementing {@link Supplier} is
 * evaluated transparently at each resolution step and replaced by the supplied object, which
 * enables lazy value computation.
 *
 * <h2>Scoping</h2>
 * Nested scopes can be realized either by creating a child context referring to its parent
 * ({@link #TemplateContext(TemplateContext, Values)}) - as done by the {@link TemplateReader} for
 * the 'each' and 'include.name' item values - or by {@link #push(Values)}ing and {@link #pop()}ping
 * additional value maps on the local stack of a context instance.
 */
public class TemplateContext {

    public static final String KEY_SEPARATOR = ".";

    protected final TemplateContext parent;
    protected final List<Values> values = new ArrayList<>();

    /**
     * creates a root context with the given initial values
     */
    public TemplateContext(@NotNull final Values values) {
        this.parent = null;
        push(values);
    }

    /**
     * creates a child context of the given parent with the given initial local values
     */
    public TemplateContext(@NotNull final TemplateContext parent, @NotNull final Values values) {
        this.parent = parent;
        push(values);
    }

    /**
     * creates a child context of the given parent with an empty local values map
     */
    public TemplateContext(@NotNull final TemplateContext parent) {
        this(parent, new Values());
    }

    /**
     * adds a values map on top of the local stack; its values shadow the values of the maps below
     */
    public void push(@NotNull final Values values) {
        this.values.add(values);
    }

    /**
     * removes and returns the topmost values map of the local stack; 'null' if the stack is empty
     */
    public @Nullable Values pop() {
        return values.isEmpty() ? null : values.remove(values.size() - 1);
    }

    /**
     * resolves the value of the given (hierarchical) key: the local stack is searched from top to
     * bottom first, then the parent context; returns 'null' if the key cannot be resolved
     */
    public @Nullable Object getValue(@NotNull final String key) {
        String[] keys = keys(key);
        Object value = null;
        for (int idx = values.size(); value == null && --idx >= 0; ) {
            Object candidate = values.get(idx);
            int i = 0;
            for (; i < keys.length && candidate instanceof Map; i++) {
                candidate = ((Map<?, ?>) candidate).get(keys[i]);
                while (candidate instanceof Supplier) {
                    candidate = ((Supplier<?>) candidate).get();
                }
            }
            // only accept the candidate if all key segments could be traversed - otherwise
            // an intermediate scalar value must not shadow a match further down the stack or parent
            value = i == keys.length ? candidate : null;
        }
        if (value == null && parent != null) {
            value = parent.getValue(key);
        }
        return value;
    }

    /**
     * resolves the value of the given key like {@link #getValue(String)}, falling back to the
     * given default value if the key cannot be resolved or if the resolved value's runtime type
     * is not assignable to the default value's runtime type (this avoids a blind unchecked cast
     * and the resulting {@link ClassCastException} at the call site). Caution: the type check
     * matches against the default value's concrete runtime class, not against the (erased) type
     * parameter 'T' - a default of a specific implementation class (e.g. an {@code ArrayList})
     * will not accept a resolved value of a sibling implementation (e.g. a {@code LinkedList})
     * even though both satisfy the same interface; pick a default whose class is a genuine common
     * supertype of every value you expect to accept.
     */
    @SuppressWarnings("unchecked")
    public <T> T getValue(@NotNull final String key, @NotNull final T defaultValue) {
        Class<T> valueType = (Class<T>) defaultValue.getClass();
        return Optional.ofNullable(getValue(key))
                .map(v -> valueType.isAssignableFrom(v.getClass()) ? valueType.cast(v) : null)
                .orElse(defaultValue);
    }

    /**
     * splits a hierarchical key into its segments; '[name]' segments are normalized to '.name'
     */
    protected static String[] keys(@NotNull String key) {
        key = key.replaceAll("\\[([^]]*)]", ".$1");
        return StringUtils.split(key, KEY_SEPARATOR);
    }

    /**
     * The map (tree) implementation of one set of context values with builder style helpers:
     * {@link #with(String, Object)} accepts hierarchical keys and creates the intermediate maps as
     * needed; if the current and the new value of a key are both maps, they are merged deeply
     * (scalar values of the new map override existing ones); 'null' values are ignored - a value
     * cannot be removed using 'with'. A foreign map found as an intermediate step or as a merge
     * partner (e.g. an immutable {@code Map.of(...)}, or any {@link Map} not created by 'with'
     * itself) is copied into a new {@link Values} instance rather than mutated or cast, so 'with'
     * never modifies a map instance supplied by the caller. See also {@link Provider} for how a
     * domain object can expose itself as a 'Values' map.
     */
    public static class Values extends HashMap<String, Object> {

        /**
         * Lets a domain object control how it is presented to a template, as an alternative to a
         * generic reflection/JSON-based fallback a {@link TemplateBuilder#valuesOf(Object)}
         * implementation might use for otherwise unsupported value types: if a value implements
         * 'Provider', its {@link #values(Values)} method is called - typically with a fresh, empty
         * {@link Values} instance - instead of that generic fallback.
         */
        public static interface Provider {

            /**
             * populates and returns the given values map with this object's template-navigable
             * properties, e.g. via repeated {@link Values#with(String, Object)} calls
             *
             * @param values the (typically empty) values map to populate
             * @return the given values map, populated with this object's properties
             */
            @NotNull Values values(@NotNull Values values);
        }

        public Values with(Map<String, Object> values) {
            for (Entry<String, Object> entry : values.entrySet()) {
                with(entry.getKey(), entry.getValue());
            }
            return this;
        }

        @SuppressWarnings("unchecked")
        public Values with(String key, Object value) {
            if (value != null) {
                String[] keys = keys(key);
                Values set = this;
                for (int idx = 0; idx < keys.length - 1; idx++) {
                    final String segment = keys[idx];
                    Object existing = set.get(segment);
                    Values nested = existing instanceof Values ? (Values) existing
                            : existing instanceof Map ? new Values().with((Map<String, Object>) existing)
                            : new Values();
                    if (existing != nested) {
                        set.put(segment, nested);
                    }
                    set = nested;
                }
                final String leaf = keys[keys.length - 1];
                Object current = set.get(leaf);
                if (current instanceof Map && value instanceof Map) {
                    set.put(leaf, merge((Map<?, ?>) current, (Map<?, ?>) value));
                } else {
                    set.put(leaf, value);
                }
            }
            return this;
        }

        @SuppressWarnings("unchecked")
        protected Values merge(Map<?, ?> target, Map<?, ?> source) {
            Values result = target instanceof Values ? (Values) target
                    : new Values().with((Map<String, Object>) target);
            for (Entry<?, ?> s : source.entrySet()) {
                String key = String.valueOf(s.getKey());
                Object sv = s.getValue();
                result.compute(key, (k, tv) -> tv instanceof Map && sv instanceof Map
                        ? merge((Map<?, ?>) tv, (Map<?, ?>) sv) : sv);
            }
            return result;
        }
    }
}
