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

/** The central access point of the tools servlet for the tools plugins. */
public interface Manager {

    /** the resource type of the tools */
    public static final String TOOLSET_RESOURCE_TYPE = "composum/tools";
    /** the default path of the tools servlet */
    public static final String DEFAULT_SERVLET_PATH = "/apps/cpm/tools";

    /** the request currently processed by the tools servlet in the current thread */
    public static final ThreadLocal<SlingHttpServletRequest> CURRENT_REQUEST = new ThreadLocal<>();

    /**
     * Provides the registered tools plugins.
     *
     * @return the set of the enabled plugins
     */
    @NotNull PluginSet<ToolsPlugin> plugins();

    /**
     * Retrieves an OSGi service.
     *
     * @param service the type of the requested service
     * @param <T>     the service type
     * @return the service; {@code null} if no such service is available
     */
    @Nullable <T> T getService(Class<T> service);

    /**
     * Provides the XSS protection API.
     *
     * @return the XSS API
     */
    @NotNull XSSAPI xssapi();

    /**
     * Provides the path of the tools servlet.
     *
     * @return the servlet path
     */
    @NotNull String serverPath();

    /**
     * Collects the pages provided by the tools plugins.
     *
     * @return the tools pages
     */
    @NotNull Collection<Page> getToolsPages();

    /**
     * Checks if a property is accessible by the tools.
     *
     * @param name the property name
     * @return {@code true} if the property is allowed
     */
    boolean isAllowedProperty(@NotNull String name);

    /**
     * Checks if a resource is accessible by the tools.
     *
     * @param resource the resource to check
     * @return {@code true} if the resource is allowed
     */
    boolean isAllowedResource(@NotNull Resource resource);

    /**
     * Checks if the children of nodes of a type can be sorted.
     *
     * @param type the node type
     * @return {@code true} if the type is sortable
     */
    boolean isSortableType(@NotNull String type);

    /**
     * Provides the URI of the login page.
     *
     * @return the login URI
     */
    @NotNull String loginUri();

    /**
     * Determines the resource addressed by a request.
     *
     * @param request the request
     * @return the suffix resource of the request; {@code null} if it is missing or not allowed
     */
    @Nullable Resource requestResource(@NotNull SlingHttpServletRequest request);

    /**
     * Adds a CSS class for each active run mode that is configured for it.
     *
     * @param cssClassSet the set to add the CSS classes to
     */
    void addRunmodeCssClasses(@NotNull Set<String> cssClassSet);

    /**
     * Provides the categories of the client libraries of the system.
     *
     * @return the client library categories
     */
    @NotNull Collection<String> systemClientlibs();

    /**
     * Removes and returns the first element of a sequence.
     *
     * @param sequence the sequence to take the first element from
     * @return the removed first element; {@code null} if the sequence is empty
     */
    static @Nullable String consume(@NotNull List<String> sequence) {
        String result = null;
        if (!sequence.isEmpty()) {
            result = sequence.remove(0);
        }
        return result;
    }

    /**
     * Removes and returns the first element of a sequence or a default value.
     *
     * @param sequence     the sequence to take the first element from
     * @param defaultValue the value to return if the sequence is empty
     * @return the removed first element or the default value
     */
    static @NotNull String consume(@NotNull final List<String> sequence,
                                   @NotNull final String defaultValue) {
        return Optional.ofNullable(consume(sequence)).orElse(defaultValue);
    }
}
