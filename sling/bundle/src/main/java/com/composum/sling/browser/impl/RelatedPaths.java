package com.composum.sling.browser.impl;

import com.composum.sling.tools.Manager;
import com.composum.sling.tools.MergeMountpointService;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.Getter;
import org.apache.commons.lang3.StringUtils;
import org.apache.sling.api.resource.Resource;
import org.apache.sling.api.resource.ResourceResolver;
import org.apache.sling.api.resource.ValueMap;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static com.composum.sling.tools.Common.JCR_CONTENT;
import static com.composum.sling.tools.Common.SLING_RES_SUPER_TYPE;
import static org.apache.jackrabbit.vault.util.JcrConstants.JCR_PRIMARYTYPE;

public class RelatedPaths {

    @Getter
    @JsonInclude(JsonInclude.Include.NON_NULL)
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class Reference {

        @NotNull
        protected final String path;
        @NotNull
        protected final String label;
        @Nullable
        protected final String icon;
        @Nullable
        protected final String description;

        public Reference(@NotNull final String path, @NotNull final String label, @Nullable final String icon) {
            this(path, label, icon, null);
        }

        public Reference(@NotNull final String path, @NotNull final String label, @Nullable final String icon, @Nullable final String description) {
            this.path = path;
            this.label = label;
            this.icon = icon;
            this.description = description;
        }

        @Override
        public String toString() {
            return "Reference{" + "label='" + label + '\'' +
                    ", path='" + path + '\'' +
                    '}';
        }
    }

    protected final Manager manager;
    protected final ResourceResolver resolver;
    protected final Resource resource;
    protected final String path;

    private transient String resourceType;

    private transient Boolean isDeclaringType;
    private transient List<Reference> supertypeChain;
    private transient List<Reference> resourceTypes;
    private transient List<Reference> relatedPathSet;

    private transient Boolean overlayAvailable;
    private transient Boolean overrideAvailable;
    private transient Map<String, Reference> typeRootLabels;

    private transient MergeMountpointService mergeMountpointService;

    public RelatedPaths(@NotNull final Manager manager, @NotNull final Resource resource) {
        this.manager = manager;
        this.resolver = resource.getResourceResolver();
        this.resource = resource;
        this.path = resource.getPath();
    }

    /**
     * returns 'true' if the current resource has a well known resource type
     */
    public boolean isTyped() {
        return StringUtils.isNotBlank(getResourceType());
    }

    /**
     * @return 'true' if the current resource itself declares a resource type
     */
    public boolean isDeclaringType() {
        if (isDeclaringType == null) {
            isDeclaringType = false;
            String path = this.path;
            if (path.startsWith(getOverrideRoot() + "/")) {
                path = path.substring(getOverrideRoot().length());
            }
            for (String root : getTypeSearchPath(true)) {
                if (path.startsWith(root)) {
                    isDeclaringType = true;
                    break;
                }
            }
        }
        return isDeclaringType;
    }

    /**
     * @return 'true' if the current resource itself 'implements' a resource type
     */
    public boolean isSourcePath() {
        return isDeclaringType() && !isOverlayResource() && !isOverrideResource();
    }

    /**
     * the content resource type (sling:resourceType) declared for the current resource
     */
    public @NotNull String getResourceType() {
        if (resourceType == null) {
            resourceType = "";
            String type = getResourceType(resource);
            if (StringUtils.isNotBlank(type)) {
                // check for a real existing resource type
                if (!Resource.RESOURCE_TYPE_NON_EXISTING.equals(type)
                        && !path.equals(getOverlayRoot() + "/" + type)) {
                    resourceType = type;
                }
            }
        }
        return resourceType;
    }

    public static @Nullable String getResourceType(@NotNull final Resource resource) {
        String result = resource.getResourceType();
        if (StringUtils.isBlank(result) || resource.getValueMap()
                .get(JCR_PRIMARYTYPE, "{no node}").equals(result)) {
            Resource contentResource = resource.getChild(JCR_CONTENT);
            if (contentResource != null) {
                result = contentResource.getResourceType();
                if (StringUtils.isNotBlank(result)) {
                    if (result.equals(contentResource.getValueMap().get(JCR_PRIMARYTYPE, String.class))) {
                        result = null;
                    }
                }
            }
        }
        return result;
    }


    /**
     * Remove any search path / /mnt/overlay from given path to a resource type = "normalize" path to resource type.
     */
    @Nullable
    protected String getResourceType(@Nullable String resourceType) {
        if (StringUtils.isNotBlank(resourceType)) {
            if (resourceType.startsWith(getOverrideRoot())) {
                resourceType = resourceType.substring(getOverrideRoot().length());
            }
            for (String root : getTypeSearchPath(true)) {
                if (resourceType.startsWith(root)) {
                    resourceType = resourceType.substring(root.length());
                    break;
                }
            }
        }
        return resourceType;
    }

    /**
     * Returns the resource for a resourceType (the "highest" in the search path), or the resource if the path is absolute.
     */
    @Nullable
    protected Resource getTypeResource(@Nullable final String resourceType, boolean includeOverlay) {
        if (StringUtils.isNotBlank(resourceType)) {
            if (!resourceType.startsWith("/")) {
                for (String root : getTypeSearchPath(includeOverlay)) {
                    Resource typeResource = resolver.getResource(root + resourceType);
                    if (typeResource != null) {
                        return typeResource;
                    }
                }
            }
            return resolver.getResource(resourceType);
        }
        return null;
    }

    @NotNull
    protected List<String> getTypeSearchPath(boolean includeOverlay) {
        List<String> typeSearchPath = new ArrayList<>(Arrays.asList(resolver.getSearchPath()));
        if (includeOverlay) {
            typeSearchPath.add(0, getOverlayRoot() + "/");
        }
        return typeSearchPath;
    }

    /**
     * The chain of resource super types. This is also included for content resources since this is used quite often in AEM.
     *
     * @see "https://experienceleague.adobe.com/docs/experience-manager-65/developing/introduction/the-basics.html?lang=en#sling-request-processing"
     */
    @NotNull
    public List<Reference> getSupertypeChain() {
        if (supertypeChain == null) {
            supertypeChain = new ArrayList<>();
            Resource typeResource = resource;
            if (isDeclaringType()) { // start from "highest" resource wrt. search path
                typeResource = getTypeResource(getResourceType(path), false);
            }
            while (typeResource != null) {
                ValueMap values = typeResource.getValueMap();
                typeResource = getTypeResource(values.get(SLING_RES_SUPER_TYPE, ""), false);
                if (typeResource != null) {
                    supertypeChain.add(new Reference(typeResource.getPath(), typeResource.getPath(), ""));
                }
            }
        }
        return supertypeChain;
    }

    /**
     * Paths for the locations relevant to the resource typein search paths, /mnt/override / /mnt/overlay, mapped to the label information.
     */
    @NotNull
    protected List<Reference> getResourceTypeSet() {
        if (resourceTypes == null) {
            resourceTypes = new ArrayList<>();
            String resourceType = getResourceType(isDeclaringType() ? path : getResourceType());
            if (StringUtils.isNotBlank(resourceType)) {
                Map<String, Reference> labels = getTypeRootLabels();
                if (isOverrideAvailable()) {
                    Reference label = labels.get(getOverrideRoot() + "/");
                    String path = getOverridePath();
                    resourceTypes.add(new Reference(path, label.getLabel(), "",
                            label.getDescription() + "\n" + path));
                }
                if (isOverlayAvailable()) {
                    Reference label = labels.get(getOverlayRoot() + "/");
                    String path = getOverlayPath();
                    if (StringUtils.isNotBlank(path)) {
                        resourceTypes.add(new Reference(path, label.getLabel(), "",
                                label.getDescription() + "\n" + path));
                    }
                }
                String basePath = getBasePath();
                for (String root : getTypeSearchPath(false)) {
                    String resourceTypePath = root + resourceType;
                    Resource type = resolver.getResource(resourceTypePath);
                    Reference label = labels.get(root); // XXX
                    resourceTypes.add(new Reference(resourceTypePath, label.getLabel(), "",
                            label.getDescription() + "\n" + resourceTypePath)); /*, type != null ?
                            (basePath != null && resolver.getResource(basePath) != null ? "is-overlay" : null)
                            : "overlay-option")); TODO check this */
                }
            }
        }
        return resourceTypes;
    }

    /**
     * Set of related paths: for resource types the resource type found in the search path and /mnt/(override|overlay), base paths, resource types.
     */
    @NotNull
    public List<Reference> getRelatedPathSet() {
        if (relatedPathSet == null) {
            if (isDeclaringType()) {
                relatedPathSet = getResourceTypeSet();
            } else {
                relatedPathSet = new ArrayList<>();
                Map<String, Reference> labels = getTypeRootLabels();
                String overrideRoot = getOverrideRoot() + "/";
                if (isOverrideAvailable()) {
                    Reference label = labels.get(overrideRoot);
                    String overridePath = getOverridePath();
                    String basePath = getBasePath();
                    relatedPathSet.add(new Reference(overridePath, label.getLabel(), "",
                            label.getDescription() + "\n" + overridePath));
                    relatedPathSet.add(new Reference(basePath, "Base Resource", "", basePath));
                }
                String resourceType = getResourceType();
                if (StringUtils.isNotBlank(resourceType)) {
                    Resource type = getTypeResource(resourceType, false);
                    if (type != null) {
                        String typePath = type.getPath();
                        relatedPathSet.add(new Reference(typePath, "Resource Type", "", typePath));
                    }
                }
            }
        }
        return relatedPathSet;
    }

    //
    // resource merger overlay / override
    //

    public String getBasePath() {
        if (isOverrideResource()) {
            return path.substring(getOverrideRoot().length());
        } else if (isOverlayResource()) { // use "highest" found entry according to search path
            Resource type = getTypeResource(getResourceType(isDeclaringType() ? path : getResourceType()), false);
            return type != null ? type.getPath() : path;
        }
        return path;
    }

    public String getOverlayRoot() {
        return getMergeMountpointService().overlayMergeMountPoint(resolver);
    }

    private MergeMountpointService getMergeMountpointService() {
        if (mergeMountpointService == null) {
            mergeMountpointService = manager.getService(MergeMountpointService.class);
        }
        return mergeMountpointService;
    }

    public boolean isOverlayResource() {
        return path.startsWith(getOverlayRoot() + "/");
    }

    public boolean isOverlayAvailable() {
        if (overlayAvailable == null) {
            String overlayPath = getOverlayPath();
            overlayAvailable = overlayPath != null && resolver.getResource(overlayPath) != null;
        }
        return overlayAvailable;
    }

    /**
     * Path of resource type within /mnt/overlay . If not a declaring resource, this doesn't make sense -> null.
     */
    @Nullable
    public String getOverlayPath() {
        return isOverlayResource() ? path :
                isDeclaringType() ? getOverlayRoot() + "/" + getResourceType(path)
                        : null;
    }

    public String getOverrideRoot() {
        return getMergeMountpointService().overrideMergeMountPoint(resolver);
    }

    public boolean isOverrideResource() {
        return path.startsWith(getOverrideRoot() + "/");
    }

    public boolean isOverrideAvailable() {
        if (overrideAvailable == null) {
            overrideAvailable = resolver.getResource(getOverridePath()) != null;
        }
        return overrideAvailable;
    }

    /**
     * Path within /mnt/override.
     */
    @NotNull
    public String getOverridePath() {
        return isOverrideResource() ? path : getOverrideRoot() + getBasePath();
    }

    protected Map<String, Reference> getTypeRootLabels() {
        if (typeRootLabels == null) {
            typeRootLabels = new HashMap<>();
            typeRootLabels.put(getOverrideRoot() + "/",
                    new Reference("o/r", "Resource Merger - Override", getOverrideRoot()));
            typeRootLabels.put(getOverlayRoot() + "/",
                    new Reference("o/l", "Resource Merger - Overlay", getOverlayRoot()));
            for (String root : resolver.getSearchPath()) {
                String label = ("" + root.charAt(1)).toUpperCase();
                String path = StringUtils.removeEnd(root, "/");
                typeRootLabels.put(root, new Reference(label, "Resource Resolver - " + path, path));
            }
        }
        return typeRootLabels;
    }
}
