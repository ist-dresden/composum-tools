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
     * The icon shown for this tool in the navigation.
     *
     * @return the icon shown for this tool in the navigation
     */
    @NotNull String icon();

    /**
     * The client-side stylesheet resource paths this tool needs.
     *
     * @return the client-side stylesheet resource paths this tool needs
     */
    @NotNull Collection<String> styles();

    /**
     * The client-side script resource paths this tool needs.
     *
     * @return the client-side script resource paths this tool needs
     */
    @NotNull Collection<String> scripts();
}
