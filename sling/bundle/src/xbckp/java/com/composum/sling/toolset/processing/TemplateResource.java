package com.composum.sling.tools.processing;

import org.apache.commons.lang3.StringUtils;
import org.apache.sling.api.resource.ResourceResolver;
import org.apache.sling.api.resource.ValueMap;
import org.apache.sling.api.wrappers.ValueMapDecorator;
import org.jetbrains.annotations.NotNull;

import java.util.Map;

import static com.composum.sling.tools.Common.JCR_PRIMARY_TYPE;
import static com.composum.sling.tools.Common.NT_UNSTRUCTURED;
import static com.composum.sling.tools.Common.SLING_RESOURCE_TYPE;

public class TemplateResource extends GenericResource {

    public TemplateResource(@NotNull ResourceResolver resolver, @NotNull String resourceType) {
        this(resolver, "/" + resourceType, resourceType);
    }

    public TemplateResource(@NotNull ResourceResolver resolver,
                            @NotNull String path, @NotNull String resourceType) {
        super(resolver, path, componentProperties(resourceType));
        final String name = StringUtils.substringAfterLast(path, "/");
        addChildren(new FileResource(this, name + ".html"));
    }

    protected static ValueMap componentProperties(String resourceType) {
        return new ValueMapDecorator(Map.of(
                JCR_PRIMARY_TYPE, NT_UNSTRUCTURED,
                SLING_RESOURCE_TYPE, resourceType
        ));
    }
}
