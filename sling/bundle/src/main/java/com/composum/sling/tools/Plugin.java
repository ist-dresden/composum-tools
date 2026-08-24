package com.composum.sling.tools;

import org.jetbrains.annotations.NotNull;

public interface Plugin extends Processor {

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
}
