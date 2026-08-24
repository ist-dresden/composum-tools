package com.composum.sling.tools;

import org.apache.sling.api.SlingHttpServletRequest;
import org.apache.sling.api.SlingHttpServletResponse;
import org.jetbrains.annotations.NotNull;

import java.util.List;

public interface Processor {

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
