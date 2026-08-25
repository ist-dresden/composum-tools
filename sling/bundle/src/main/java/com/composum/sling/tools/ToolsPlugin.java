package com.composum.sling.tools;

import com.composum.sling.tools.dto.Widget;
import org.apache.sling.api.SlingHttpServletRequest;
import org.apache.sling.api.SlingHttpServletResponse;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.List;

/**
 * A {@link Plugin} contributing one or more dashboard widgets.
 */
public interface ToolsPlugin extends Plugin {

    /**
     * @return the widgets contributed by this plugin
     */
    @NotNull List<Widget> widgets();

    /**
     * @param request   the current request
     * @param response  the current response
     * @param widgetKey the key of the widget to build a link for
     * @return the link to the given widget, or 'null' if the plugin does not provide one
     */
    @Nullable String widgetLink(@NotNull SlingHttpServletRequest request,
                                @NotNull SlingHttpServletResponse response,
                                @NotNull String widgetKey);
}
