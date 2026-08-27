package com.composum.sling.tools;

import org.apache.sling.api.SlingHttpServletRequest;
import org.apache.sling.api.resource.Resource;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.Collection;
import java.util.Set;

public interface PlatformConfig {

    boolean toolsAllowed(@NotNull SlingHttpServletRequest request);

    @NotNull Collection<String> fileTypes();

    @Nullable String nameOf(@Nullable Resource resource);

    @Nullable Resource contentOf(@Nullable Resource resource);

    @Nullable Resource originalOf(@Nullable Resource resource);

    @NotNull Set<String> runmodes();
}
