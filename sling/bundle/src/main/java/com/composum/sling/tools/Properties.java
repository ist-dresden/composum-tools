package com.composum.sling.tools;

import org.apache.commons.lang3.StringUtils;
import org.apache.sling.api.resource.Resource;
import org.apache.sling.api.resource.ResourceResolver;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.io.InputStream;
import java.text.SimpleDateFormat;
import java.util.Calendar;

import static com.composum.sling.tools.Common.JCR_CONTENT;

/** Helper for rendering property values as HTML. */
public class Properties {

    /** Default constructor. */
    public Properties() {
    }

    /**
     * Appends an HTML representation of the given property value to the buffer; arrays and iterables are rendered as lists, strings that denote an accessible resource path or resource type are rendered as links.
     *
     * @param manager  the tools manager, used for XSS protection and access checks
     * @param resolver the resolver used to resolve paths found in string values
     * @param resource the resource owning the value, used as target of the binary download link, may be {@code null}
     * @param buffer   the buffer to append the HTML to
     * @param value    the value to render, may be {@code null}
     * @return the type name of the rendered value (with a trailing '[]' for multi values), or an empty string for {@code null}
     */
    public static String toHtml(@NotNull final Manager manager,
                                @NotNull final ResourceResolver resolver, @Nullable final Resource resource,
                                @NotNull final StringBuilder buffer, @Nullable final Object value) {
        String type = "";
        if (value != null) {
            if (value instanceof Object[]) {
                buffer.append("<ul>");
                for (Object val : (Object[]) value) {
                    buffer.append("<li>");
                    type = toHtml(manager, resolver, resource, buffer, val);
                    buffer.append("</li>");
                }
                buffer.append("</ul>");
                type += "[]";
            } else if (value instanceof Iterable) {
                buffer.append("<ul>");
                for (Object val : (Iterable<?>) value) {
                    buffer.append("<li>");
                    type = toHtml(manager, resolver, resource, buffer, val);
                    buffer.append("</li>");
                }
                buffer.append("</ul>");
                type += "[]";
            } else if (value instanceof Calendar) {
                buffer.append(new SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS Z").format(((Calendar) value).getTime()));
                type = "Date";
            } else if (value instanceof InputStream) {
                if (resource != null) {
                    buffer.append("<a class=\"binary\" data-target=\"_blank\" data-href=\"")
                            .append(StringUtils.substringBeforeLast(resource.getPath(), "/" + JCR_CONTENT))
                            .append("\">download...</a>");
                } else {
                    buffer.append("...");
                }
                type = "Binary";
            } else if (value instanceof String) {
                final String string = (String) value;
                Resource target = resolvePath(manager, resolver, string);
                if (target == null) {
                    target = resolveType(manager, resolver, string);
                }
                if (target != null) {
                    buffer.append("<a class=\"path\" href=\"#\" data-path=\"").append(target.getPath()).append("\">")
                            .append(manager.xssapi().encodeForHTML(string)).append("</a>");
                } else {
                    buffer.append(manager.xssapi().encodeForHTML(string));
                }
                type = "String";
            } else {
                buffer.append(manager.xssapi().encodeForHTML(value.toString()));
                type = value.getClass().getSimpleName();
            }
        }
        return type;
    }

    /**
     * Resolves a resource type (relative to the search path) to an accessible resource.
     *
     * @param manager  the tools manager, used for access checks
     * @param resolver the resolver used to look up the resource
     * @param type     the resource type, may be {@code null}
     * @return the resource found for the type, or {@code null} if the value is not a resource type or no accessible resource exists
     */
    protected static @Nullable Resource resolveType(@NotNull final Manager manager,
                                                    @NotNull final ResourceResolver resolver,
                                                    @Nullable final String type) {
        if (StringUtils.isNotBlank(type) && StringUtils.countMatches(type, "/") > 1) {
            Resource resource;
            for (String root : resolver.getSearchPath()) {
                resource = resolvePath(manager, resolver, root + type);
                if (resource != null) {
                    return resource;
                }
            }
        }
        return null;
    }

    /**
     * Resolves an absolute path to an accessible resource.
     *
     * @param manager  the tools manager, used for access checks
     * @param resolver the resolver used to look up the resource
     * @param path     the path, may be {@code null}
     * @return the resource found at the path, or {@code null} if the value is not an absolute path or no accessible resource exists
     */
    protected static @Nullable Resource resolvePath(@NotNull final Manager manager,
                                                    @NotNull final ResourceResolver resolver,
                                                    @Nullable final String path) {
        if (StringUtils.isNotBlank(path) && path.startsWith("/")) {
            final Resource resource = resolver.getResource(path);
            if (resource != null && manager.isAllowedResource(resource)) {
                return resource;
            }
        }
        return null;
    }
}
