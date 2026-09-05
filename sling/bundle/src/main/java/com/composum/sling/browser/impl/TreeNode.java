package com.composum.sling.browser.impl;

import com.composum.sling.tools.Manager;
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
import static com.composum.sling.tools.Common.JCR_PRIMARY_TYPE;
import static com.composum.sling.tools.Common.NT_UNSTRUCTURED;

@Getter
@JsonInclude(JsonInclude.Include.NON_NULL)
@JsonIgnoreProperties(ignoreUnknown = true)
public class TreeNode {

    @Getter
    @JsonInclude(JsonInclude.Include.NON_NULL)
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class State {
        protected boolean loaded;

        public State(boolean loaded) {
            this.loaded = loaded;
        }
    }

    protected String id;
    protected String name;
    protected String path;

    protected String text;
    protected String type;
    protected String resourceType;

    protected Collection<TreeNode> children;

    protected State state;

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

    public TreeNode(@NotNull final String path) {
        this.path = path;
        name = StringUtils.substringAfterLast(StringUtils.defaultIfBlank(path, ""), "/");
    }

    public static final List<String> FIRST_CHILDREN = List.of(JCR_CONTENT, AC_POLICY);

    protected static final ChildComparator CHILD_COMPARATOR = new ChildComparator();

    protected static class ChildComparator implements Comparator<String> {

        @Override
        public int compare(String name1, String name2) {
            return key(name1).compareTo(key(name2));
        }

        protected String key(String name) {
            final int position = FIRST_CHILDREN.indexOf(name);
            return (position < 0 ? FIRST_CHILDREN.size() : position) + "::" + name;
        }
    }

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

    public static String getFileTypeKey(Resource resource, String prefix) {
        String type = null;
        Resource content = resource.getChild(JCR_CONTENT);
        if (content != null) {
            type = getMimeTypeKey(content, prefix);
        }
        return type;
    }

    public static String getMimeTypeKey(Resource resource, String prefix) {
        String type = null;
        String mimeType = resource.getValueMap().get("jcr:mimeType", String.class);
        if (StringUtils.isNotBlank(mimeType)) {
            type = getMimeTypeKey(mimeType);
        }
        if (StringUtils.isNotBlank(type) && StringUtils.isNotBlank(prefix)) {
            type = prefix + type;
        }
        return type;
    }

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
