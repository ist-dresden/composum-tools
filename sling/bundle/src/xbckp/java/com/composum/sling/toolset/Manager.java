package com.composum.sling.tools;

import org.apache.sling.api.SlingHttpServletRequest;
import org.apache.sling.api.resource.Resource;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.List;
import java.util.Optional;
import java.util.Set;

public interface Manager {

    public static final String TOOLSET_RESOURCE_TYPE = "composum/tools";
    public static final String SERVLET_PATH = "/apps/cpm/tools";

    @Nullable Plugin getPlugin(@Nullable String key);

    @NotNull Set<Plugin> getPlugins();

    boolean isAllowedProperty(@NotNull String name, @Nullable Object value);

    boolean isAllowedResource(@NotNull Resource resource);

    @Nullable Resource requestResource(@NotNull SlingHttpServletRequest request);

    @Nullable String renderTemplate(@NotNull final Resource resource);

    void addRunmodeCssClasses(@NotNull Set<String> cssClassSet);

    static @Nullable String consume(@NotNull List<String> sequence) {
        String result = null;
        if (!sequence.isEmpty()) {
            result = sequence.remove(0);
        }
        return result;
    }

    static @NotNull String consume(@NotNull final List<String> sequence,
                                   @NotNull final String defaultValue) {
        return Optional.ofNullable(consume(sequence)).orElse(defaultValue);
    }
}
