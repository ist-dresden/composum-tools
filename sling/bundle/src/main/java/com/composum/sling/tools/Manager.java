package com.composum.sling.tools;

import com.composum.sling.tools.dto.Page;
import org.apache.sling.api.SlingHttpServletRequest;
import org.apache.sling.api.resource.Resource;
import org.apache.sling.xss.XSSAPI;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.Set;

public interface Manager {

    public static final String TOOLSET_RESOURCE_TYPE = "composum/tools";
    public static final String DEFAULT_SERVLET_PATH = "/apps/cpm/tools";

    public static final ThreadLocal<SlingHttpServletRequest> CURRENT_REQUEST = new ThreadLocal<>();

    @NotNull PluginSet<ToolsPlugin> plugins();

    @Nullable <T> T getService(Class<T> service);

    @NotNull XSSAPI xssapi();

    @NotNull String serverPath();

    @NotNull Collection<Page> getToolsPages();

    boolean isAllowedProperty(@NotNull String name);

    boolean isAllowedResource(@NotNull Resource resource);

    boolean isSortableType(@NotNull String type);

    @NotNull String loginUri();

    @Nullable Resource requestResource(@NotNull SlingHttpServletRequest request);

    void addRunmodeCssClasses(@NotNull Set<String> cssClassSet);

    @NotNull Collection<String> systemClientlibs();

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
