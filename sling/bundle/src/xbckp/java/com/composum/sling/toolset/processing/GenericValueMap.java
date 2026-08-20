package com.composum.sling.tools.processing;

import org.apache.sling.api.resource.ValueMap;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Supplier;

public abstract class GenericValueMap implements ValueMap {

    protected final String path;

    public GenericValueMap(@NotNull final String path) {
        this.path = path;
    }

    protected abstract @NotNull Set<String> keys();

    protected abstract @Nullable <T> Supplier<T> supplier(@NotNull String name, @NotNull Class<T> type);

    @Override
    public @Nullable <T> T get(@NotNull String name, @NotNull Class<T> type) {
        return Optional.ofNullable(supplier(name, type)).map(Supplier::get).orElse(null);
    }

    @Override
    @SuppressWarnings("unchecked")
    public @NotNull <T> T get(@NotNull String name, @NotNull T defaultValue) {
        return Optional.ofNullable((T) get(name, defaultValue.getClass())).orElse(defaultValue);
    }

    @Override
    public int size() {
        return keys().size();
    }

    @Override
    public boolean isEmpty() {
        return keys().isEmpty();
    }

    @Override
    public boolean containsKey(Object key) {
        return key instanceof String && keys().contains(key);
    }

    @Override
    public boolean containsValue(Object value) {
        return false;
    }

    @Override
    public Object get(Object key) {
        return key instanceof String ? get((String) key, Object.class) : null;
    }

    @Override
    public @Nullable Object put(String key, Object value) {
        throw new UnsupportedOperationException("unmodifiable");
    }

    @Override
    public Object remove(Object key) {
        throw new UnsupportedOperationException("unmodifiable");
    }

    @Override
    public void putAll(@NotNull Map<? extends String, ?> m) {
        throw new UnsupportedOperationException("unmodifiable");
    }

    @Override
    public void clear() {
        throw new UnsupportedOperationException("unmodifiable");
    }

    @Override
    public @NotNull Set<String> keySet() {
        return keys();
    }

    @Override
    public @NotNull Collection<Object> values() {
        return List.of();
    }

    protected class GenericEntry implements Entry<String, Object> {

        protected final String key;

        public GenericEntry(@NotNull final String key) {
            this.key = key;
        }

        @Override
        public String getKey() {
            return key;
        }

        @Override
        public Object getValue() {
            return GenericValueMap.this.get(key);
        }

        @Override
        public Object setValue(Object value) {
            throw new UnsupportedOperationException("unmodifiable");
        }
    }

    @Override
    public @NotNull Set<Entry<String, Object>> entrySet() {
        final Set<Entry<String, Object>> result = new LinkedHashSet<>();
        for (String key : keys()) {
            result.add(new GenericEntry(key));
        }
        return result;
    }
}
