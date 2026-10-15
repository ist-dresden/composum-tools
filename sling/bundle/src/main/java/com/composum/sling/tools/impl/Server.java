package com.composum.sling.tools.impl;

import com.composum.sling.browser.Browser;
import com.composum.sling.tools.Common;
import com.composum.sling.tools.Manager;
import com.composum.sling.tools.PlatformConfig;
import com.composum.sling.tools.PluginSet;
import com.composum.sling.tools.Result;
import com.composum.sling.tools.ToolsPlugin;
import com.composum.sling.tools.dto.Page;
import com.composum.sling.tools.dto.Widget;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.commons.io.IOUtils;
import org.apache.commons.lang3.StringUtils;
import org.apache.sling.api.SlingHttpServletRequest;
import org.apache.sling.api.SlingHttpServletResponse;
import org.apache.sling.api.request.RequestPathInfo;
import org.apache.sling.api.resource.Resource;
import org.apache.sling.api.servlets.SlingAllMethodsServlet;
import org.apache.sling.xss.XSSAPI;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.osgi.framework.BundleContext;
import org.osgi.framework.ServiceReference;
import org.osgi.service.component.annotations.Activate;
import org.osgi.service.component.annotations.Component;
import org.osgi.service.component.annotations.Modified;
import org.osgi.service.component.annotations.Reference;
import org.osgi.service.metatype.annotations.AttributeDefinition;
import org.osgi.service.metatype.annotations.Designate;
import org.osgi.service.metatype.annotations.ObjectClassDefinition;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.servlet.Servlet;
import java.io.IOException;
import java.io.InputStream;
import java.io.Reader;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;

import static com.composum.sling.tools.Common.EXT_HTML;
import static com.composum.sling.tools.Common.EXT_JSON;
import static com.composum.sling.tools.Common.HTTP_CONTENT_TYPE;
import static com.composum.sling.tools.Common.JSON_TYPE;
import static com.composum.sling.tools.Common.NT_FOLDER;
import static com.composum.sling.tools.Common.SLING_FOLDER;
import static javax.servlet.http.HttpServletResponse.SC_BAD_REQUEST;
import static javax.servlet.http.HttpServletResponse.SC_NOT_FOUND;

/**
 * The tools servlet; it delegates the requests to the tools plugins and provides the {@link Manager} service.
 *
 * <p>This is the single point for the configuration of the tools.</p>
 */
@Component(service = {Servlet.class, Manager.class}, immediate = true)
@Designate(ocd = Server.Config.class)
public class Server extends SlingAllMethodsServlet implements Manager {

    private static final Logger LOG = LoggerFactory.getLogger(Server.class);

    /** the default URI of the login page */
    public static final String DEFAULT_LOGIN_URI = "/system/sling/form/login.html";

    /** the mapper used to write JSON results */
    public static final ObjectMapper MAPPER = new ObjectMapper();

    /** Default constructor. */
    public Server() {
    }

    /** The configuration of the tools servlet. */
    @ObjectClassDefinition(name = "Composum Tools")
    public @interface Config {

        /**
         * Provides the patterns of the property names accessible by the tools.
         *
         * @return the regular expressions for allowed property names
         */
        @AttributeDefinition(name = "Allowed Property Patterns")
        String[] allowedPropertyPatterns() default {
                "^.*$"
        };

        /**
         * Provides the patterns of the property names hidden from the tools.
         *
         * @return the regular expressions for disabled property names
         */
        @AttributeDefinition(name = "Disabled Property Patterns")
        String[] disabledPropertyPatterns() default {
                "^.*password.*$"
        };

        /**
         * Provides the patterns of the paths accessible by the tools.
         *
         * @return the regular expressions for allowed paths
         */
        @AttributeDefinition(name = "Allowed Path Patterns")
        String[] allowedPathPatterns() default {
                "^/$",
                "^/content(/.*)?$",
                "^/(conf|etc)(/.*)?$",
                "^/(var|tmp)(/.*)?$",
                "^/oak:index(/.*)?$",
                "^/(apps|libs|mnt)(/.*)?$"
        };

        /**
         * Provides the patterns of the paths hidden from the tools.
         *
         * @return the regular expressions for disabled paths
         */
        @AttributeDefinition(name = "Disabled Path Patterns")
        String[] disabledPathPatterns() default {
                ".*/rep:[^/]+/.*",
                "^(/.*)?/api(/.*)?$"
        };

        /**
         * Provides the node types with sortable children.
         *
         * @return the sortable node types
         */
        @AttributeDefinition(name = "Sortable Types")
        String[] sortableTypes() default {
                NT_FOLDER, SLING_FOLDER
        };

        /**
         * Provides the run modes to add as CSS classes to the tools pages.
         *
         * @return the run modes
         */
        @AttributeDefinition(name = "CSS Runmodes")
        String[] cssRunmodes();

        /**
         * Provides the URI of the login page.
         *
         * @return the login URI
         */
        @AttributeDefinition(name = "Login URI")
        String loginUri() default Server.DEFAULT_LOGIN_URI;

        /**
         * Provides the categories of the system client libraries.
         *
         * @return the client library categories
         */
        @AttributeDefinition(name = "System Clientlibs")
        String[] systemClientlibs() default {};

        /**
         * Provides the extensions the servlet is registered for.
         *
         * @return the servlet extensions
         */
        @AttributeDefinition(name = "Servlet Extensions")
        String[] sling_servlet_extensions() default {
                "html",
                "json"
        };

        /**
         * Provides the paths the servlet is registered for; the first one is the server path.
         *
         * @return the servlet paths
         */
        @AttributeDefinition(name = "Servlet Paths")
        String[] sling_servlet_paths() default {
                DEFAULT_SERVLET_PATH
        };
    }

    /** the XSS protection API */
    @Reference
    private XSSAPI xssapi;

    /** the configuration of the platform the tools are running on */
    @Reference
    private PlatformConfig platformConfig;

    /** the context of this bundle */
    protected BundleContext bundleContext;
    /** the active configuration */
    protected Config config;

    /** the patterns of the allowed property names */
    protected List<Pattern> allowedPropertyPatterns;
    /** the patterns of the disabled property names */
    protected List<Pattern> disabledPropertyPatterns;
    /** the patterns of the allowed paths */
    protected List<Pattern> allowedPathPatterns;
    /** the patterns of the disabled paths */
    protected List<Pattern> disabledPathPatterns;
    /** the node types with sortable children */
    protected List<String> sortableTypes;
    /** the run modes to add as CSS classes */
    protected List<String> cssRunmodes;
    /** the categories of the system client libraries */
    protected List<String> systemClientlibs;
    /** the URI of the login page */
    protected String loginUri;
    /** the path of the servlet */
    protected String serverPath;

    /** the registered tools plugins */
    protected final PluginSet<ToolsPlugin> plugins = new PluginSet<>() {
        @Override
        protected boolean isEnabled(@NotNull final ToolsPlugin service) {
            return service.isEnabled();
        }
    };

    @Override
    public @NotNull PluginSet<ToolsPlugin> plugins() {
        return plugins;
    }

    /**
     * Applies the configuration.
     *
     * @param bundleContext the context of this bundle
     * @param config        the configuration
     */
    @Activate
    @Modified
    protected void activate(final BundleContext bundleContext, final Config config) {
        this.bundleContext = bundleContext;
        this.config = config;
        allowedPropertyPatterns = Common.patternList(config.allowedPropertyPatterns());
        disabledPropertyPatterns = Common.patternList(config.disabledPropertyPatterns());
        allowedPathPatterns = Common.patternList(config.allowedPathPatterns());
        disabledPathPatterns = Common.patternList(config.disabledPathPatterns());
        sortableTypes = Common.listOf(config.sortableTypes());
        cssRunmodes = Common.listOf(config.cssRunmodes());
        systemClientlibs = Common.listOf(config.systemClientlibs());
        loginUri = Optional.ofNullable(config.loginUri()).orElse(DEFAULT_LOGIN_URI);
        serverPath = Common.listOf(config.sling_servlet_paths()).get(0);
    }

    @Override
    public @Nullable <T> T getService(Class<T> serviceType) {
        final ServiceReference<T> reference = bundleContext.getServiceReference(serviceType);
        if (reference != null) {
            return bundleContext.getService(reference);
        }
        return null;
    }

    @Override
    public @NotNull XSSAPI xssapi() {
        return xssapi;
    }

    @Override
    public @NotNull String serverPath() {
        return serverPath;
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
    public boolean isAllowedProperty(@NotNull final String name) {
        for (Pattern allowed : allowedPropertyPatterns) {
            if (allowed.matcher(name).matches()) {
                for (Pattern disabled : disabledPropertyPatterns) {
                    if (disabled.matcher(name).matches()) {
                        return false;
                    }
                }
                return true;
            }
        }
        return false;
    }

    @Override
    public boolean isAllowedResource(@NotNull final Resource resource) {
        final String path = resource.getPath();
        for (Pattern allowed : allowedPathPatterns) {
            if (allowed.matcher(path).matches()) {
                for (Pattern disabled : disabledPathPatterns) {
                    if (disabled.matcher(path).matches()) {
                        return false;
                    }
                }
                return true;
            }
        }
        return false;
    }

    @Override
    public boolean isSortableType(@NotNull String type) {
        return sortableTypes.contains(type);
    }

    @Override
    public @NotNull String loginUri() {
        return loginUri;
    }

    @Override
    public @Nullable Resource requestResource(@NotNull final SlingHttpServletRequest request) {
        Resource resource = request.getRequestPathInfo().getSuffixResource();
        return resource != null && isAllowedResource(resource) ? resource : null;
    }

    @Override
    protected void doGet(@NotNull final SlingHttpServletRequest request,
                         @NotNull final SlingHttpServletResponse response)
            throws IOException {
        doIt(request, response);
    }

    @Override
    protected void doPost(@NotNull final SlingHttpServletRequest request,
                          @NotNull final SlingHttpServletResponse response)
            throws IOException {
        doIt(request, response);
    }

    /**
     * Handles a request by delegating it to the plugin selected by the first selector.
     *
     * @param request  the request
     * @param response the response
     * @throws IOException if writing the response fails
     */
    protected void doIt(@NotNull final SlingHttpServletRequest request,
                        @NotNull final SlingHttpServletResponse response)
            throws IOException {
        CURRENT_REQUEST.set(request);
        try {
            if (!platformConfig.toolsAllowed(request)) {
                response.sendError(SC_NOT_FOUND);
                return;
            }
            final RequestPathInfo pathInfo = request.getRequestPathInfo();
            final List<String> selectors = Common.listOf(pathInfo.getSelectors());
            final ToolsPlugin plugin = plugins().get(Manager.consume(selectors, Browser.KEY));
            if (plugin != null) {
                Result<?> result = plugin.process(request, response, selectors);
                final Object data = result.getData();
                response.setStatus(result.getStatusCode());
                result.addHeaders(response);
                if (data instanceof InputStream) {
                    final InputStream stream = (InputStream) data;
                    IOUtils.copy(stream, response.getOutputStream());
                    try {
                        stream.close();
                    } catch (IOException ignore) {
                    }
                    return;
                } else if (data instanceof Reader) {
                    final Reader stream = (Reader) data;
                    IOUtils.copy(stream, response.getWriter());
                    try {
                        stream.close();
                    } catch (IOException ignore) {
                    }
                    return;
                } else if (data != null && (EXT_JSON.equals(pathInfo.getExtension())
                        || JSON_TYPE.startsWith(response.getContentType()))) {
                    if (StringUtils.isBlank(response.getHeader(HTTP_CONTENT_TYPE))) {
                        response.setContentType(Common.JSON_TYPE);
                    }
                    if (result.isPrettyPrint()) {
                        MAPPER.writerWithDefaultPrettyPrinter().writeValue(response.getWriter(), data);
                    } else {
                        MAPPER.writeValue(response.getWriter(), data);
                    }
                    return;
                } else {
                    if (StringUtils.isBlank(response.getHeader(HTTP_CONTENT_TYPE))
                            && EXT_HTML.equals(pathInfo.getExtension())) {
                        response.setContentType(Common.HTML_TYPE);
                    }
                    response.setContentLength(0);
                    return;
                }
            }
            response.sendError(SC_BAD_REQUEST);
        } finally {
            CURRENT_REQUEST.remove();
        }
    }

    @Override
    public void addRunmodeCssClasses(@NotNull Set<String> cssClassSet) {
        final Set<String> runmodes = platformConfig.runmodes();
        for (final String runmode : cssRunmodes) {
            if (runmodes.contains(runmode)) {
                cssClassSet.add("runmode-" + runmode);
            }
        }
    }

    @Override
    public @NotNull Collection<String> systemClientlibs() {
        return systemClientlibs;
    }
}
