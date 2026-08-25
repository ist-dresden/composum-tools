package com.composum.sling.tools;

import org.jetbrains.annotations.NotNull;

public interface Plugin extends Processor {

    /**
     * The key this plugin is registered under - the request selector process() consumes to route a request to this plugin.
     *
     * @return the plugin's registration key
     */
    @NotNull String key();

    /**
     * The label shown for this plugin in the navigation.
     *
     * @return the plugin's navigation label
     */
    @NotNull String label();

    /**
     * Determines this plugin's position among the navigation entries; higher values sort first.
     *
     * @return the plugin's navigation rank
     */
    int rank();
}
