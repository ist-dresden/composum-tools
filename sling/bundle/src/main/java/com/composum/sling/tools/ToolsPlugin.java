package com.composum.sling.tools;

import com.composum.sling.tools.dto.Widget;
import org.jetbrains.annotations.NotNull;

import java.util.List;

public interface ToolsPlugin extends Plugin {

    @NotNull List<Widget> widgets();
}
