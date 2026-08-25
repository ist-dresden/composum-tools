package com.composum.sling.browser;

import com.composum.sling.tools.Plugin;
import com.composum.sling.tools.template.TemplateContext.Values;
import org.jetbrains.annotations.NotNull;

import java.util.Collection;

/**
 * A plugin contributing an entry to the browser's tools navigation (e.g. 'query', 'favorites').
 */
public interface Tool extends Plugin, Values.Provider {

    /**
     * @return the icon shown for this tool in the navigation
     */
    @NotNull String icon();

    /**
     * @return the client-side stylesheet resource paths this tool needs
     */
    @NotNull Collection<String> styles();

    /**
     * @return the client-side script resource paths this tool needs
     */
    @NotNull Collection<String> scripts();
}
