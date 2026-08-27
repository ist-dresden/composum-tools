package com.composum.sling.tools;

import com.composum.sling.tools.dto.Page;
import com.composum.sling.tools.dto.Widget;
import com.composum.sling.tools.impl.Server;
import org.apache.sling.api.SlingHttpServletRequest;
import org.apache.sling.api.resource.Resource;
import org.apache.sling.xss.XSSAPI;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.jspecify.annotations.NonNull;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Set;

public class TestManager implements Manager {

    protected final ToolsPlugin testPlugin;
    protected final PluginSet<ToolsPlugin> plugins = new PluginSet<>() {
        @Override
        protected boolean isEnabled(@NonNull ToolsPlugin service) {
            return true;
        }
    };

    public TestManager() {
        testPlugin = new TestToolsPlugin(this);
        plugins.attach(testPlugin);
    }

    @Override
    public @NotNull PluginSet<ToolsPlugin> plugins() {
        return plugins;
    }

    @Override
    public @NotNull XSSAPI xssapi() {
        return XSSMOCK;
    }

    @Override
    public @NotNull String serverPath() {
        return "/apps/cpm/test";
    }

    @Override
    public @NotNull List<Page> getToolsPages() {
        final List<Page> pages = new ArrayList<>();
        for (ToolsPlugin plugin : plugins().list()) {
            for (Widget widget : plugin.widgets()) {
                if (widget instanceof Page) {
                    pages.add((Page) widget);
                }
            }
        }
        return pages;
    }

    @Override
    public boolean isAllowedProperty(@NotNull String name) {
        return true;
    }

    @Override
    public boolean isAllowedResource(@NotNull Resource resource) {
        return true;
    }

    @Override
    public boolean isSortableType(@NotNull String type) {
        return false;
    }

    @Override
    public @NotNull String loginUri() {
        return Server.DEFAULT_LOGIN_URI;
    }

    @Override
    public @Nullable Resource requestResource(@NotNull SlingHttpServletRequest request) {
        return null;
    }

    @Override
    public void addRunmodeCssClasses(@NotNull Set<String> cssClassSet) {
    }

    @Override
    public @NotNull Collection<String> systemClientlibs() {
        return List.of();
    }

    public static final XSSAPI XSSMOCK = new XSSAPI() {

        @Override
        public Integer getValidInteger(String integer, int defaultValue) {
            return 0;
        }

        @Override
        public Long getValidLong(String source, long defaultValue) {
            return 0L;
        }

        @Override
        public Double getValidDouble(String source, double defaultValue) {
            return 0.0;
        }

        @Override
        public String getValidDimension(String dimension, String defaultValue) {
            return dimension;
        }

        @Override
        public @NotNull String getValidHref(String url) {
            return url;
        }

        @Override
        public String getValidJSToken(String token, String defaultValue) {
            return token;
        }

        @Override
        public String getValidStyleToken(String token, String defaultValue) {
            return token;
        }

        @Override
        public String getValidCSSColor(String color, String defaultColor) {
            return color;
        }

        @Override
        public String getValidMultiLineComment(String comment, String defaultComment) {
            return comment;
        }

        @Override
        public String getValidJSON(String json, String defaultJson) {
            return json;
        }

        @Override
        public String getValidXML(String xml, String defaultXml) {
            return xml;
        }

        @Override
        public String encodeForHTML(String source) {
            return source;
        }

        @Override
        public String encodeForHTMLAttr(String source) {
            return source;
        }

        @Override
        public String encodeForXML(String source) {
            return source;
        }

        @Override
        public String encodeForXMLAttr(String source) {
            return source;
        }

        @Override
        public String encodeForJSString(String source) {
            return source;
        }

        @Override
        public String encodeForCSSString(String source) {
            return source;
        }

        @Override
        public @NotNull String filterHTML(String source) {
            return source;
        }
    };
}
