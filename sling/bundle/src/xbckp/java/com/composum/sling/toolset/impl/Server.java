package com.composum.sling.tools.impl;

import com.composum.sling.tools.Common;
import com.composum.sling.tools.Manager;
import com.composum.sling.tools.Plugin;
import com.composum.sling.tools.Result;
import com.composum.sling.tools.processing.TemplateRequest;
import com.composum.sling.tools.processing.TemplateResolver;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.commons.io.IOUtils;
import org.apache.commons.lang3.StringUtils;
import org.apache.sling.api.SlingHttpServletRequest;
import org.apache.sling.api.SlingHttpServletResponse;
import org.apache.sling.api.request.RequestPathInfo;
import org.apache.sling.api.resource.Resource;
import org.apache.sling.api.servlets.ServletResolverConstants;
import org.apache.sling.api.servlets.SlingAllMethodsServlet;
import org.apache.sling.engine.SlingRequestProcessor;
import org.apache.sling.settings.SlingSettingsService;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.osgi.framework.BundleContext;
import org.osgi.service.component.annotations.Activate;
import org.osgi.service.component.annotations.Component;
import org.osgi.service.component.annotations.Modified;
import org.osgi.service.component.annotations.Reference;
import org.osgi.service.component.annotations.ReferenceCardinality;
import org.osgi.service.component.annotations.ReferencePolicy;
import org.osgi.service.component.annotations.ReferencePolicyOption;
import org.osgi.service.metatype.annotations.AttributeDefinition;
import org.osgi.service.metatype.annotations.Designate;
import org.osgi.service.metatype.annotations.ObjectClassDefinition;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.servlet.Servlet;
import java.io.IOException;
import java.io.InputStream;
import java.io.Reader;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.regex.Pattern;

import static javax.servlet.http.HttpServletResponse.SC_BAD_REQUEST;

@Component(service = {Servlet.class, Manager.class},
        property = {
                ServletResolverConstants.SLING_SERVLET_PATHS + "=/apps/cpm/tools",
//              ServletResolverConstants.SLING_SERVLET_RESOURCE_TYPES + "=" + DEFAULT_RESOURCE_TYPE,
                ServletResolverConstants.SLING_SERVLET_EXTENSIONS + "=" + "html",
                ServletResolverConstants.SLING_SERVLET_EXTENSIONS + "=" + "json"
        },
        immediate = true
)
@Designate(ocd = Server.Config.class)
public class Server extends SlingAllMethodsServlet implements Manager {

    private static final Logger LOG = LoggerFactory.getLogger(Server.class);

    public static final ObjectMapper MAPPER = new ObjectMapper();

    @ObjectClassDefinition(name = "Composum Tools")
    public @interface Config {

        @AttributeDefinition(name = "Allowed Property Patterns")
        String[] allowedPropertyPatterns() default {
                "^.*$"
        };

        @AttributeDefinition(name = "Disabled Property Patterns")
        String[] disabledPropertyPatterns() default {
                "^rep:.*$",
                "^.*password.*$"
        };

        @AttributeDefinition(name = "Allowed Path Patterns")
        String[] allowedPathPatterns() default {
                "^/$",
                "^/content(/.*)?$",
                "^/conf(/.*)?$",
                "^/var(/.*)?$",
                "^/mnt(/.*)?$"
        };

        @AttributeDefinition(name = "Disabled Path Patterns")
        String[] disabledPathPatterns() default {
                ".*/rep:.*",
                "^(/.*)?/api(/.*)?$"
        };

        @AttributeDefinition(name = "Sortable Types")
        String[] sortableTypes() default {
                "nt:folder", "sling:Folder"
        };

        @AttributeDefinition(name = "CSS Runmodes")
        String[] cssRunmodes();

        @AttributeDefinition(name = "Login URI")
        String loginUri() default "/system/sling/form/login.html";

        @AttributeDefinition(name = "Servlet Extensions")
        String[] sling_servlet_extensions() default {
                "html",
                "json"
        };

        @AttributeDefinition(name = "Servlet Paths")
        String[] sling_servlet_paths() default {
                SERVLET_PATH
        };
    }

    @Reference
    private SlingSettingsService settingsService;

    @Reference(cardinality = ReferenceCardinality.OPTIONAL)
    protected volatile SlingRequestProcessor slingRequestProcessor;

    protected List<Pattern> allowedPropertyPatterns;
    protected List<Pattern> disabledPropertyPatterns;
    protected List<Pattern> allowedPathPatterns;
    protected List<Pattern> disabledPathPatterns;
    protected List<String> sortableTypes;
    protected List<String> cssRunmodes;
    protected String loginUri;

    protected final Map<String, Plugin> pluginMap = new TreeMap<>();
    protected final Set<Plugin> pluginSet = new TreeSet<>(Comparator.comparingInt(Plugin::rank));

    @Reference(
            service = Plugin.class,
            policy = ReferencePolicy.DYNAMIC,
            cardinality = ReferenceCardinality.MULTIPLE,
            policyOption = ReferencePolicyOption.GREEDY
    )
    protected void bindPlugin(@NotNull final Plugin service) {
        synchronized (pluginMap) {
            final Plugin replaced = pluginMap.put(service.key(), service);
            if (replaced != null) {
                pluginSet.remove(replaced);
            }
            pluginSet.add(service);
        }
    }

    @SuppressWarnings("unused")
    protected void unbindPlugin(@NotNull final Plugin service) {
        synchronized (pluginMap) {
            final Plugin removed = pluginMap.remove(service.key());
            if (removed != null) {
                pluginSet.remove(removed);
            }
        }
    }

    @Override
    public @Nullable Plugin getPlugin(@Nullable final String key) {
        return Optional.ofNullable(key).map(pluginMap::get).orElse(null);
    }

    @Override
    public @NotNull Set<Plugin> getPlugins() {
        return pluginSet;
    }

    @Activate
    @Modified
    protected void activate(final BundleContext bundleContext, final Config config) {
        allowedPropertyPatterns = Common.patternList(config.allowedPropertyPatterns());
        disabledPropertyPatterns = Common.patternList(config.disabledPropertyPatterns());
        allowedPathPatterns = Common.patternList(config.allowedPathPatterns());
        disabledPathPatterns = Common.patternList(config.disabledPathPatterns());
        sortableTypes = Common.listOf(config.sortableTypes());
        cssRunmodes = Common.listOf(config.cssRunmodes());
        loginUri = config.loginUri();
    }

    @Override
    public boolean isAllowedProperty(@NotNull final String name, @Nullable final Object value) {
        if (value != null) {
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
    public @Nullable Resource requestResource(@NotNull final SlingHttpServletRequest request) {
        Resource resource = Optional.ofNullable(request.getRequestPathInfo().getSuffixResource())
                .orElse(request.getResourceResolver().getResource("/"));
        return resource != null && isAllowedResource(resource) ? resource : null;
    }

    @Override
    protected void doGet(@NotNull final SlingHttpServletRequest request,
                         @NotNull final SlingHttpServletResponse response)
            throws IOException {
        TemplateResolver.REQUEST.set(request);
        try {
            final RequestPathInfo pathInfo = request.getRequestPathInfo();
            final List<String> selectors = Common.listOf(pathInfo.getSelectors());
            final Plugin plugin = getPlugin(Manager.consume(selectors, "tiles"));
            if (plugin != null) {
                Result<?> result = plugin.process(request, response, selectors);
                final String contentType = result.getContentType();
                final Integer contentLength = result.getContentLength();
                final Object data = result.getData();
                if (StringUtils.isNotBlank(contentType)) {
                    response.setContentType(contentType);
                }
                if (contentLength != null) {
                    response.setContentLength(contentLength);
                }
                response.setStatus(result.getStatusCode());
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
                } else if (data != null) {
                    if (StringUtils.isBlank(contentType)) {
                        response.setContentType(Common.JSON_TYPE);
                    }
                    MAPPER.writeValue(response.getWriter(), data);
                    return;
                } else {
                    response.setContentLength(0);
                }
            }
            response.sendError(SC_BAD_REQUEST);
        } finally {
            TemplateResolver.REQUEST.remove();
        }
    }

    @Override
    public @Nullable String renderTemplate(@NotNull final Resource resource) {
        String result = null;
        try {
            result = new TemplateRequest(slingRequestProcessor, resource)
                    .withRequestMethod("GET")
                    .withExtension("html")
                    .execute()
                    .checkStatus(200)
                    .getResponseAsString();
        } catch (Exception ex) {
            LOG.info("error on render template", ex);
        }
        return result;
    }

    @Override
    public void addRunmodeCssClasses(@NotNull Set<String> cssClassSet) {
        final Set<String> slingRunmodes = settingsService.getRunModes();
        for (final String runmode : cssRunmodes) {
            if (slingRunmodes.contains(runmode)) {
                cssClassSet.add("runmode-" + runmode);
            }
        }
    }
}
