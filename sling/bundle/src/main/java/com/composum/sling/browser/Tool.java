package com.composum.sling.browser;

import com.composum.sling.tools.Plugin;
import com.composum.sling.tools.template.TemplateContext.Values;
import org.jetbrains.annotations.NotNull;

import java.util.Collection;

public interface Tool extends Plugin, Values.Provider {

    @NotNull String icon();

    @NotNull Collection<String> styles();

    @NotNull Collection<String> scripts();
}
