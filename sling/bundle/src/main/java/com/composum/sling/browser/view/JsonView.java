package com.composum.sling.browser.view;

import com.composum.sling.browser.Browser;
import com.composum.sling.browser.View;
import com.composum.sling.tools.Common;
import com.composum.sling.tools.Manager;
import com.composum.sling.tools.Result;
import com.composum.sling.tools.template.Template;
import com.composum.sling.tools.template.TemplateContext;
import com.composum.sling.tools.template.TemplateContext.Values;
import org.apache.commons.lang3.StringUtils;
import org.apache.sling.api.SlingHttpServletRequest;
import org.apache.sling.api.SlingHttpServletResponse;
import org.apache.sling.api.request.RequestPathInfo;
import org.apache.sling.api.resource.Resource;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.osgi.framework.BundleContext;
import org.osgi.service.component.annotations.Activate;
import org.osgi.service.component.annotations.Component;
import org.osgi.service.component.annotations.Deactivate;
import org.osgi.service.component.annotations.Modified;
import org.osgi.service.component.annotations.Reference;
import org.osgi.service.metatype.annotations.AttributeDefinition;
import org.osgi.service.metatype.annotations.Designate;
import org.osgi.service.metatype.annotations.ObjectClassDefinition;

import java.io.InputStream;
import java.io.Reader;
import java.text.SimpleDateFormat;
import java.util.Arrays;
import java.util.Calendar;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;

import static com.composum.sling.tools.Common.HTML_TYPE;
import static com.composum.sling.tools.Common.JCR_CONTENT;
import static com.composum.sling.tools.Common.JCR_MIXIN_TYPES;
import static javax.servlet.http.HttpServletResponse.SC_NOT_FOUND;

@Component(service = {View.class, JsonView.class}, immediate = true)
@Designate(ocd = JsonView.Config.class)
public class JsonView extends AbstractSourceView {

    public static final String KEY = "json";

    @ObjectClassDefinition(name = "Composum Browser Json View")
    public @interface Config {

        @AttributeDefinition()
        String key() default JsonView.KEY;

        @AttributeDefinition()
        String label() default "JSON";

        @AttributeDefinition()
        int rank() default 4000;

        @AttributeDefinition(name = "Max Depth")
        int maxDepth() default 0;

        @AttributeDefinition(name = "Source Mode",
                description = "hides technical noise properties (jcr:uuid, jcr:created, ...); can be " +
                        "switched off per request with the 'raw' request parameter")
        boolean sourceMode() default true;

        @AttributeDefinition(name = "Non Source Properties",
                description = "property name patterns hidden in source mode")
        String[] nonSourceProperties() default {
                "^jcr:(uuid|data)$",
                "^jcr:(baseVersion|predecessors|versionHistory|isCheckedOut)$",
                "^jcr:(created|lastModified).*$",
                "^cq:last(Modified|Replicat).*$"
        };

        @AttributeDefinition(name = "Non Source Mixins",
                description = "mixin type patterns hidden in source mode")
        String[] nonSourceMixins() default {
                "^rep:AccessControllable$"
        };
    }

    @Reference
    protected Browser browser;

    protected Config config;

    @Activate
    @Modified
    protected void activate(final BundleContext bundleContext, final Config config) {
        this.config = config;
        activateSourceMode(bundleContext, config.maxDepth(), config.sourceMode(),
                config.nonSourceProperties(), config.nonSourceMixins());
        browser.views().attach(this);
    }

    @Deactivate
    protected void deactivate() {
        browser.views().detach(this);
    }

    @Override
    public Browser browser() {
        return browser;
    }

    @Override
    public @NotNull String key() {
        return Optional.ofNullable(config).map(Config::key).orElse(KEY);
    }

    @Override
    public @NotNull String label() {
        return Optional.ofNullable(config).map(Config::label).orElse("JSON");
    }

    @Override
    public int rank() {
        return Optional.ofNullable(config).map(Config::rank).orElse(4000);
    }

    @Override
    public @NotNull Result<?> process(@NotNull final SlingHttpServletRequest request,
                                      @NotNull final SlingHttpServletResponse response,
                                      @NotNull final List<String> selectors) {
        Result<?> result = new Result<>(SC_NOT_FOUND);
        switch (Manager.consume(selectors, "")) {
            case "resource":
                result = browser.resource(request);
                break;
            case "form":
                result = params(request, response);
                break;
            case "load": {
                final Resource resource = browser.manager().requestResource(request);
                if (resource != null) {
                    final Integer depth = getIntParameter(request, "depth", maxDepth);
                    final boolean raw = getBooleanParameter(request, "raw", false);
                    final boolean source = !raw && sourceModeSupport;
                    final Map<String, Object> tree = dumpResource(resource, 0, depth, source);
                    result = new Result<>(tree);
                    result.setPrettyPrint(true);
                }
            }
            break;
            default: {
                final RequestPathInfo pathInfo = request.getRequestPathInfo();
                final String path = Optional.ofNullable(pathInfo.getSuffix()).filter(StringUtils::isNotBlank).orElse("/");
                final Reader content = browser.templateReader(getTemplate(new TemplateContext(
                        new Values()
                                .with("content.url", browser.manager().serverPath() + ".browser.view.json.load.json"
                                        + path
                                        + Optional.ofNullable(request.getQueryString()).map(q -> "?" + q).orElse(""))
                ), "view"));
                if (content != null) {
                    result = new Result<>(content, HTML_TYPE);
                }
            }
            break;
        }
        return result;
    }

    public final Map<String, Factory> templates = Map.of(
            "view", current ->
                    new Template("/sling/browser/view/json/json.html",
                            new TemplateContext(current, new Values()), this)
    );

    @Override
    public @Nullable Template getTemplate(@NotNull TemplateContext context, @NotNull String key) {
        return Optional.ofNullable(templates.get(key))
                .map(factory -> factory.create(context))
                .orElse(key.startsWith("/") ? new Template(key, context, this) : null);
    }

    protected @NotNull Map<String, Object> dumpResource(@NotNull final Resource resource, final int depth,
                                                        @Nullable final Integer maxDepth, final boolean sourceMode) {
        final Map<String, Object> result = new LinkedHashMap<>();
        dumpProperties(result, resource, sourceMode);
        final String name = resource.getName();
        Integer childMaxDepth = maxDepth;
        if (sourceMode && (JCR_CONTENT.equals(name) || resource.getPath().contains("/" + JCR_CONTENT + "/"))) {
            childMaxDepth = null;
        }
        final Resource content = resource.getChild(JCR_CONTENT);
        if (content != null && browser.manager().isAllowedResource(content)) {
            if (sourceMode || childMaxDepth == null || depth < childMaxDepth) {
                result.put(content.getName(), dumpResource(content, depth + 1, childMaxDepth, sourceMode));
            }
        }
        if (childMaxDepth == null || depth < childMaxDepth) {
            for (final Resource child : resource.getChildren()) {
                if (browser.manager().isAllowedResource(child)) {
                    final String childName = child.getName();
                    if (!JCR_CONTENT.equals(childName)) {
                        result.put(childName, dumpResource(child, depth + 1, childMaxDepth, sourceMode));
                    }
                }
            }
        }
        return result;
    }

    protected void dumpProperties(@NotNull final Map<String, Object> target, @NotNull final Resource resource,
                                  final boolean sourceMode) {
        final Map<String, Object> sorted = new TreeMap<>(PROPERTY_NAME_COMPARATOR);
        for (final Map.Entry<String, Object> property : resource.getValueMap().entrySet()) {
            final String name = property.getKey();
            final Object value = property.getValue();
            if (!isAllowedProperty(name, sourceMode)) {
                continue;
            }
            if (sourceMode && JCR_MIXIN_TYPES.equals(name)) {
                final String[] mixins = filterMixins(value instanceof String[] ? (String[]) value : null);
                if (mixins != null && mixins.length > 0) {
                    Arrays.sort(mixins);
                    sorted.put(name, mixins);
                }
            } else if (value instanceof InputStream) {
                sorted.put(name, "<binary>");
            } else if (value != null) {
                sorted.put(name, value);
            }
        }
        for (final Map.Entry<String, Object> property : sorted.entrySet()) {
            target.put(property.getKey(), jsonValue(property.getValue()));
        }
    }

    protected @Nullable Object jsonValue(@Nullable final Object value) {
        if (value instanceof Calendar) {
            return new SimpleDateFormat(Common.JSON_DATE_FORMAT).format(((Calendar) value).getTime());
        } else if (value instanceof Date) {
            return new SimpleDateFormat(Common.JSON_DATE_FORMAT).format((Date) value);
        } else if (value instanceof Calendar[]) {
            final Calendar[] values = (Calendar[]) value;
            final String[] formatted = new String[values.length];
            final SimpleDateFormat format = new SimpleDateFormat(Common.JSON_DATE_FORMAT);
            for (int i = 0; i < values.length; i++) {
                formatted[i] = format.format(values[i].getTime());
            }
            return formatted;
        }
        return value;
    }
}
