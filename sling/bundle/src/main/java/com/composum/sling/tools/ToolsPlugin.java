package com.composum.sling.tools;

import com.composum.sling.tools.dto.Widget;
import org.apache.sling.api.SlingHttpServletRequest;
import org.apache.sling.api.SlingHttpServletResponse;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.List;

public interface ToolsPlugin extends Plugin {

    @NotNull List<Widget> widgets();

    @Nullable String widgetLink(@NotNull SlingHttpServletRequest request,
                                @NotNull SlingHttpServletResponse response,
                                @NotNull String widgetKey);
}
