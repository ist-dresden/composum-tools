package com.composum.sling.tools;

import org.apache.sling.api.SlingHttpServletRequest;
import org.apache.sling.api.SlingHttpServletResponse;
import org.jetbrains.annotations.NotNull;

import java.util.List;

public interface Plugin {

    /**
     * The key this plugin is registered under - the request selector process() consumes to route a request to this plugin.
     */
    @NotNull String key();

    /**
     * The label shown for this plugin in the navigation.
     */
    @NotNull String label();

    /**
     * Determines this plugin's position among the navigation entries; higher values sort first.
     */
    int rank();

    boolean isEnabled();

    /**
     * Handles one request that the server routed to this plugin.
     *
     * @param selectors the request selectors remaining after server or any plugin in the chain consumed
     *                  the leading ones that identified this plugin by its {@link #key()}
     */
    @NotNull Result<?> process(@NotNull SlingHttpServletRequest request,
                               @NotNull SlingHttpServletResponse response,
                               @NotNull List<String> selectors);
}
