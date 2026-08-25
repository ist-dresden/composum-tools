package com.composum.sling.browser;

import com.composum.sling.tools.Plugin;
import com.composum.sling.tools.template.TemplateContext.Values;
import org.jetbrains.annotations.NotNull;

import java.util.Collection;

/**
 * A plugin contributing a tab to the browser's resource view (e.g. 'properties', 'json', 'xml').
 */
public interface View extends Plugin, Values.Provider {

    /**
     * @return the client-side stylesheet resource paths this view needs
     */
    @NotNull Collection<String> styles();

    /**
     * @return the client-side script resource paths this view needs
     */
    @NotNull Collection<String> scripts();
}
