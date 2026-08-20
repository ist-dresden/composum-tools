package com.composum.sling.tools.processing;

import com.composum.sling.tools.Manager;
import org.apache.commons.lang3.StringUtils;
import org.apache.sling.api.SlingHttpServletRequest;
import org.apache.sling.api.resource.LoginException;
import org.apache.sling.api.resource.NonExistingResource;
import org.apache.sling.api.resource.PersistenceException;
import org.apache.sling.api.resource.Resource;
import org.apache.sling.api.resource.ResourceResolver;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.servlet.http.HttpServletRequest;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;

public class TemplateResolver implements ResourceResolver {

    private static final Logger LOG = LoggerFactory.getLogger(TemplateResolver.class);

    public static final String TEMPLATE_ROOT = Manager.SERVLET_PATH + "/resources";

    public static @NotNull String embeddedPath(@NotNull final String path) {
        return path.startsWith(TEMPLATE_ROOT + "/")
                ? path.substring(TEMPLATE_ROOT.length())
                : path;
    }

    public static @NotNull String resourcePath(@NotNull final String path) {
        return path.startsWith(TEMPLATE_ROOT + "/") ? path : TEMPLATE_ROOT + path;
    }

    public static @NotNull String resourcePath(@NotNull final GenericResource resource) {
        return resourcePath(resource.getPath());
    }

    public static final ThreadLocal<SlingHttpServletRequest> REQUEST = new ThreadLocal<>();

    protected static <T> @Nullable T delegate(Function<ResourceResolver, T> function) {
        final SlingHttpServletRequest request = REQUEST.get();
        if (request != null) {
            return function.apply(request.getResourceResolver());
        }
        return null;
    }

    protected final Map<String, Resource> resources = new LinkedHashMap<>();

    public @Nullable Resource addResource(@NotNull final GenericResource resource) {
        return resources.put(resource.getPath(), resource);
    }

    protected Resource retrieveResource(@NotNull final String path) {
        for (String p = path; StringUtils.length(p) > 1; p = p.substring(0, Math.max(0, p.lastIndexOf('/')))) {
            final Resource resource = resources.get(p);
            if (resource != null) {
                return path.equals(p) ? resource : resource.getChild(path.substring(p.length() + 1));
            }
        }
        return null;
    }

    @Override
    public @Nullable Resource getResource(@NotNull final String path) {
        Resource resource = retrieveResource(embeddedPath(path));
        if (resource == null) {
            resource = delegate(resolver -> resolver.getResource(path));
        }
        LOG.info("getResource({}): {}", path, resource);
        return resource;
    }

    @Override
    public Resource getResource(@Nullable final Resource base, @NotNull final String path) {
        Resource resource;
        if (base instanceof GenericResource) {
            String p = path;
            if (!p.startsWith("/")) {
                p = base.getPath() + "/" + p;
            }
            p = p.replaceAll("/\\./", "/");
            while (p.matches("^(/[^/]+)+/\\.\\./.*")) {
                p = p.replaceFirst("/[^/]+/\\.\\./", "/");
            }
            resource = getResource(p);
        } else {
            resource = delegate(resolver -> resolver.getResource(path));
        }
        LOG.info("getResource({},{}): {}", base, path, resource);
        return resource;
    }

    @Override
    public @NotNull Resource resolve(@NotNull HttpServletRequest request, @NotNull String absPath) {
        return resolve(absPath);
    }

    @Override
    public @NotNull Resource resolve(@NotNull final String absPath) {
        Resource result = Optional.ofNullable(getResource(absPath))
                .orElse(Optional.ofNullable(getResource(StringUtils.substringBeforeLast(absPath, ".")))
                        .orElse(Optional.ofNullable(delegate(resolver -> resolver.resolve(absPath)))
                                .orElse(new NonExistingResource(this, absPath))));
        LOG.info("resolve({}): {}", absPath, result);
        return result;
    }

    @Override
    public @NotNull Resource resolve(@NotNull HttpServletRequest request) {
        return resolve(request.getPathInfo());
    }

    @Override
    public @NotNull String map(@NotNull final String path) {
        String result = resourcePath(path);
        LOG.info("map({}): {}", path, result);
        return result;
    }

    @Override
    public @Nullable String map(@NotNull final HttpServletRequest request, @NotNull final String resourcePath) {
        return map(resourcePath);
    }

    @Override
    public @NotNull String[] getSearchPath() {
        List<String> searchPath = new ArrayList<>();
        searchPath.add(TEMPLATE_ROOT);
        searchPath.addAll(Arrays.asList(Optional.ofNullable(delegate(ResourceResolver::getSearchPath))
                .orElse(new String[0])));
        LOG.info("getSearchPath(): {}", searchPath);
        return searchPath.toArray(new String[0]);
    }

    @Override
    public @NotNull Iterator<Resource> listChildren(@NotNull final Resource parent) {
        return parent instanceof GenericResource ? parent.listChildren()
                : Optional.ofNullable(delegate(resolver -> resolver.listChildren(parent)))
                .orElse(Collections.emptyIterator());
    }

    @Override
    public @Nullable Resource getParent(@NotNull final Resource child) {
        return child instanceof GenericResource ? child.getParent()
                : delegate(resolver -> resolver.getParent(child));
    }

    @Override
    public @NotNull Iterable<Resource> getChildren(@NotNull final Resource parent) {
        return parent instanceof GenericResource ? parent.getChildren()
                : Optional.ofNullable(delegate(resolver -> resolver.getChildren(parent)))
                .orElse(Collections.emptyList());
    }

    @Override
    public @NotNull Iterator<Resource> findResources(@NotNull final String query, final String language) {
        return Optional.ofNullable(delegate(resolver -> resolver.findResources(query, language)))
                .orElse(Collections.emptyIterator());
    }

    @Override
    public @NotNull Iterator<Map<String, Object>> queryResources(@NotNull final String query, final String language) {
        return Optional.ofNullable(delegate(resolver -> resolver.queryResources(query, language)))
                .orElse(Collections.emptyIterator());
    }

    @Override
    public boolean hasChildren(@NotNull final Resource resource) {
        return resource instanceof GenericResource ? resource.hasChildren()
                : Boolean.TRUE.equals(delegate(resolver -> resolver.hasChildren(resource)));
    }

    @Override
    public @NotNull ResourceResolver clone(Map<String, Object> authenticationInfo) throws LoginException {
        throw new LoginException("unsupported");
    }

    @Override
    public boolean isLive() {
        return false;
    }

    @Override
    public void close() {
    }

    @Override
    public @Nullable String getUserID() {
        return null;
    }

    @Override
    public @NotNull Iterator<String> getAttributeNames() {
        return Collections.emptyIterator();
    }

    @Override
    public @Nullable Object getAttribute(@NotNull final String name) {
        return delegate(resolver -> resolver.getAttribute(name));
    }

    @Override
    public void delete(@NotNull Resource resource) throws PersistenceException {
        throw new PersistenceException("unsupported");
    }

    @Override
    public @NotNull Resource create(@NotNull Resource parent, @NotNull String name, Map<String, Object> properties)
            throws PersistenceException {
        throw new PersistenceException("unsupported");
    }

    @Override
    public void revert() {
    }

    @Override
    public void commit() {
    }

    @Override
    public boolean hasChanges() {
        return false;
    }

    @Override
    public @Nullable String getParentResourceType(Resource resource) {
        return Optional.ofNullable(resource.getParent()).map(Resource::getResourceType).orElse(null);
    }

    @Override
    public @Nullable String getParentResourceType(String resourceType) {
        return null;
    }

    @Override
    public boolean isResourceType(Resource resource, String resourceType) {
        return resource != null && resourceType != null &&
                (resourceType.equals(resource.getResourceType()) ||
                        resourceType.equals(resource.getResourceSuperType()));
    }

    @Override
    public void refresh() {
    }

    @Override
    public Resource copy(String srcAbsPath, String destAbsPath) throws PersistenceException {
        throw new PersistenceException("unsupported");
    }

    @Override
    public Resource move(String srcAbsPath, String destAbsPath) throws PersistenceException {
        throw new PersistenceException("unsupported");
    }

    @Override
    public @Nullable <AdapterType> AdapterType adaptTo(@NotNull final Class<AdapterType> type) {
        return delegate(resolver -> resolver.adaptTo(type));
    }
}
