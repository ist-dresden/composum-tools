package com.composum.sling.tools.processing;

import com.composum.sling.tools.Common;
import org.apache.commons.lang3.StringUtils;
import org.apache.sling.api.resource.Resource;
import org.apache.sling.api.resource.ResourceMetadata;
import org.apache.sling.api.resource.ResourceResolver;
import org.apache.sling.api.resource.ValueMap;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Iterator;
import java.util.List;

public class GenericResource implements Resource {

    protected final ResourceResolver resolver;
    protected final Resource parent;
    protected final String path;
    protected final ValueMap properties;
    protected final List<Resource> children;

    protected final ResourceMetadata metadata = new ResourceMetadata();

    public GenericResource(@NotNull final ResourceResolver resolver,
                           @NotNull final String path,
                           @NotNull final ValueMap properties) {
        this(resolver, null, path, properties, new ArrayList<>());
    }

    public GenericResource(@NotNull final Resource parent,
                           @NotNull final String name,
                           @NotNull final ValueMap properties) {
        this(parent, name, properties, new ArrayList<>());
    }

    public GenericResource(@NotNull final Resource parent,
                           @NotNull final String name,
                           @NotNull final ValueMap properties,
                           @NotNull final List<Resource> children) {
        this(parent.getResourceResolver(), parent, parent.getPath() + "/" + name, properties, children);
    }

    public GenericResource(@NotNull final ResourceResolver resolver,
                           @Nullable final Resource parent,
                           @NotNull final String path,
                           @NotNull final ValueMap properties,
                           @NotNull final List<Resource> children) {
        this.resolver = resolver;
        this.parent = parent;
        this.path = path;
        this.properties = properties;
        this.children = children;
    }

    @Override
    public @NotNull String getPath() {
        return path;
    }

    @Override
    public @NotNull String getName() {
        return StringUtils.substringAfterLast(getPath(), "/");
    }

    @Override
    public @Nullable Resource getParent() {
        return parent;
    }

    public void addChildren(Resource... resources) {
        children.addAll(Arrays.asList(resources));
    }

    @Override
    public @NotNull Iterator<Resource> listChildren() {
        return children.iterator();
    }

    @Override
    public @NotNull Iterable<Resource> getChildren() {
        return children;
    }

    @Override
    public @Nullable Resource getChild(@NotNull String relPath) {
        final String root = getPath();
        while (relPath.startsWith("/")) {
            relPath = relPath.substring(1);
        }
        for (String p = relPath; StringUtils.isNotEmpty(p); p = p.substring(0, Math.max(0, p.lastIndexOf('/')))) {
            final String full = root + "/" + p;
            for (final Resource child : getChildren()) {
                if (full.equals(child.getPath())) {
                    return relPath.equals(p) ? child : child.getChild(relPath.substring(p.length() + 1));
                }
            }
        }
        return null;
    }

    @Override
    public @NotNull String getResourceType() {
        return properties.get(Common.SLING_RESOURCE_TYPE, "");
    }

    @Override
    public @Nullable String getResourceSuperType() {
        return getResourceResolver().getParentResourceType(getResourceType());
    }

    @Override
    public boolean hasChildren() {
        return !children.isEmpty();
    }

    @Override
    public boolean isResourceType(String resourceType) {
        return getResourceResolver().isResourceType(this, resourceType);
    }

    @Override
    public @NotNull ResourceMetadata getResourceMetadata() {
        return metadata;
    }

    @Override
    public @NotNull ResourceResolver getResourceResolver() {
        return resolver;
    }

    @Override
    public @NotNull ValueMap getValueMap() {
        return properties;
    }

    @Override
    public @Nullable <AdapterType> AdapterType adaptTo(@NotNull Class<AdapterType> type) {
        return null;
    }
}
