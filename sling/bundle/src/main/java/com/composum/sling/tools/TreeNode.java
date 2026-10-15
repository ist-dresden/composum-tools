package com.composum.sling.tools;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.Getter;
import org.apache.commons.lang3.StringUtils;
import org.apache.sling.api.resource.Resource;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;

import static com.composum.sling.tools.Common.AC_POLICY;
import static com.composum.sling.tools.Common.JCR_CONTENT;
import static com.composum.sling.tools.Common.JCR_MIME_TYPE;
import static com.composum.sling.tools.Common.JCR_PRIMARY_TYPE;
import static com.composum.sling.tools.Common.NT_UNSTRUCTURED;

/**
 * A single jsTree-shaped node (lazily including its own children) for a resource-tree JSON
 * endpoint - the shared model behind every plugin's "tree" GET route (see
 * {@code com.composum.sling.browser.Browser}'s and {@code com.composum.sling.changes.Changes}'
 * own 'tree' case for the reference wiring) and the client-side jsTree widgets in
 * {@code browser/script.js} ('BrowserTree') and {@code sling/tools/script.js} ('TreePicker').
 */
@Getter
@JsonInclude(JsonInclude.Include.NON_NULL)
@JsonIgnoreProperties(ignoreUnknown = true)
public class TreeNode {

    /** The loading state of a tree node. */
    @Getter
    @JsonInclude(JsonInclude.Include.NON_NULL)
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class State {
        /** if {@code true} the children of the node are included */
        protected boolean loaded;

        /**
         * Creates a state.
         *
         * @param loaded if {@code true} the children of the node are included
         */
        public State(boolean loaded) {
            this.loaded = loaded;
        }
    }

    /** the node id (the resource path) */
    protected String id;
    /** the resource name */
    protected String name;
    /** the resource path */
    protected String path;

    /** the node text (the resource name) */
    protected String text;
    /** the type key used to select the node's icon and style */
    protected String type;
    /** the Sling resource type of the resource */
    protected String resourceType;

    /** the child nodes; {@code null} if not loaded or without children */
    protected Collection<TreeNode> children;

    /** the loading state of the node */
    protected State state;

    /**
     * Creates a node for a resource.
     *
     * @param manager  the manager providing the access rules
     * @param resource the resource to represent
     * @param state    the node state; the children are included if it is {@code null} or loaded
     */
    public TreeNode(@NotNull final Manager manager, @NotNull final Resource resource,
                    @Nullable final State state) {
        path = resource.getPath();
        name = resource.getName();
        if (StringUtils.isBlank(name) && "/".equals(path)) {
            name = "jcr:root";
        }
        id = path;
        text = name;
        resourceType = StringUtils.defaultIfBlank(resource.getResourceType(), null);
        type = getTypeKey(resource);
        if (state == null || state.loaded) {
            children = getChildren(manager, resource);
        }
        this.state = state;
    }

    /**
     * Creates a node for a path without a resource.
     *
     * @param path the path of the node
     */
    public TreeNode(@NotNull final String path) {
        this.path = path;
        name = StringUtils.substringAfterLast(StringUtils.defaultIfBlank(path, ""), "/");
    }

    /** the names of the children always placed first */
    public static final List<String> FIRST_CHILDREN = List.of(JCR_CONTENT, AC_POLICY);

    /** the comparator ordering the children of sortable nodes */
    protected static final ChildComparator CHILD_COMPARATOR = new ChildComparator();

    /** Orders child names with the {@link #FIRST_CHILDREN} first. */
    protected static class ChildComparator implements Comparator<String> {

        /** Default constructor. */
        protected ChildComparator() {
        }

        @Override
        public int compare(String name1, String name2) {
            return key(name1).compareTo(key(name2));
        }

        /**
         * Builds the sort key of a child name.
         *
         * @param name the child name
         * @return the sort key
         */
        protected String key(String name) {
            final int position = FIRST_CHILDREN.indexOf(name);
            return (position < 0 ? FIRST_CHILDREN.size() : position) + "::" + name;
        }
    }

    /**
     * Builds the child nodes of a resource.
     *
     * @param manager  the manager providing the access rules
     * @param resource the resource
     * @return the allowed children; {@code null} if the resource has no children
     */
    protected Collection<TreeNode> getChildren(@NotNull final Manager manager, @NotNull final Resource resource) {
        if (resource.hasChildren()) {
            final String primaryType = resource.getValueMap().get(JCR_PRIMARY_TYPE, NT_UNSTRUCTURED);
            final boolean sortable = manager.isSortableType(primaryType);
            final Map<String, TreeNode> children = sortable ? new TreeMap<>(CHILD_COMPARATOR) : new LinkedHashMap<>();
            if (!sortable) {
                for (String name : FIRST_CHILDREN) {
                    Optional.ofNullable(resource.getChild(name))
                            .filter(manager::isAllowedResource)
                            .ifPresent(child -> {
                                children.put(child.getName(), new TreeNode(manager, child, new State(false)));
                            });
                }
            }
            for (Resource child : resource.getChildren()) {
                if (manager.isAllowedResource(child)) {
                    if (sortable || !FIRST_CHILDREN.contains(child.getName())) {
                        children.put(child.getName(), new TreeNode(manager, child, new State(false)));
                    }
                }
            }
            return children.values();
        }
        return null;
    }

    /**
     * Determines the type key of a resource from its primary type, file MIME type or resource type.
     *
     * @param resource the resource
     * @return the type key
     */
    public static String getTypeKey(Resource resource) {
        String type = getPrimaryTypeKey(resource);
        if ("file".equals(type)) {
            type = getFileTypeKey(resource, "file-");
        } else if ("resource".equals(type)) {
            type = getMimeTypeKey(resource, "resource-");
        } else if (StringUtils.isBlank(type) || "unstructured".equals(type)) {
            type = getResourceTypeKey(resource, "resource-");
        }
        return type;
    }

    /**
     * Determines the type key from the primary type without namespace in lower case.
     *
     * @param resource the resource
     * @return the type key
     */
    public static String getPrimaryTypeKey(Resource resource) {
        String type = resource.getValueMap().get(JCR_PRIMARY_TYPE, NT_UNSTRUCTURED);
        if (StringUtils.isNotBlank(type)) {
            int namespace = type.lastIndexOf(':');
            if (namespace >= 0) {
                type = type.substring(namespace + 1);
            }
            type = type.toLowerCase();
        }
        return type;
    }

    /**
     * Determines the type key from the last segment of the resource type, if it differs from the primary type.
     *
     * @param resource the resource
     * @param prefix   the prefix for the key; can be blank
     * @return the type key; {@code null} if there is no distinct resource type
     */
    public static String getResourceTypeKey(Resource resource, String prefix) {
        String primaryType = resource.getValueMap().get(JCR_PRIMARY_TYPE, NT_UNSTRUCTURED);
        String type = null;
        String resourceType = resource.getResourceType();
        if (StringUtils.isNotBlank(resourceType) && !resourceType.equals(primaryType)) {
            int namespace = resourceType.lastIndexOf(':');
            if (namespace >= 0) {
                resourceType = resourceType.substring(namespace + 1);
            }
            int dot = resourceType.lastIndexOf('.');
            if (dot >= 0) {
                resourceType = resourceType.substring(dot + 1);
            }
            type = resourceType.substring(resourceType.lastIndexOf('/') + 1);
            type = type.toLowerCase();
        }
        if (StringUtils.isNotBlank(type) && StringUtils.isNotBlank(prefix)) {
            type = prefix + type;
        }
        return type;
    }

    /**
     * Determines the type key of a file from the MIME type of its content.
     *
     * @param resource the file resource
     * @param prefix   the prefix for the key; can be blank
     * @return the type key; {@code null} if there is no content with a MIME type
     */
    public static String getFileTypeKey(Resource resource, String prefix) {
        String type = null;
        Resource content = resource.getChild(JCR_CONTENT);
        if (content != null) {
            type = getMimeTypeKey(content, prefix);
        }
        return type;
    }

    /**
     * Determines the type key from the MIME type of a resource.
     *
     * @param resource the resource holding the MIME type property
     * @param prefix   the prefix for the key; can be blank
     * @return the type key; {@code null} if there is no MIME type
     */
    public static String getMimeTypeKey(Resource resource, String prefix) {
        String type = null;
        String mimeType = resource.getValueMap().get(JCR_MIME_TYPE, String.class);
        if (StringUtils.isNotBlank(mimeType)) {
            type = getMimeTypeKey(mimeType);
        }
        if (StringUtils.isNotBlank(type) && StringUtils.isNotBlank(prefix)) {
            type = prefix + type;
        }
        return type;
    }

    /**
     * Determines the type key of a MIME type.
     *
     * @param mimeType the MIME type
     * @return the type key in lower case
     */
    @NotNull
    public static String getMimeTypeKey(String mimeType) {
        String major = mimeType;
        String minor = "";
        int delim = mimeType.indexOf('/');
        if (delim >= 0) {
            major = mimeType.substring(0, delim);
            minor = mimeType.substring(delim + 1);
        }
        String type = major;
        if ("text".equals(major)) {
            type += "-" + minor;
        } else if ("application".equals(major)) {
            type = minor;
        }
        return type.toLowerCase();
    }
}
