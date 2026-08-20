package com.composum.sling.browser.impl;

import com.composum.sling.browser.View;
import com.composum.sling.tools.AbstractPlugin;
import com.composum.sling.tools.Manager;
import com.composum.sling.tools.Plugin;
import com.composum.sling.tools.Result;
import com.composum.sling.tools.dto.Page;
import com.composum.sling.tools.dto.Widget;
import com.composum.sling.tools.template.Template;
import com.composum.sling.tools.template.TemplateBuilder;
import com.composum.sling.tools.template.TemplateContext;
import com.composum.sling.tools.template.TemplateReader;
import org.apache.sling.api.SlingHttpServletRequest;
import org.apache.sling.api.SlingHttpServletResponse;
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

import javax.servlet.http.HttpServletResponse;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.function.Supplier;

import static com.composum.sling.tools.Common.HTML_TYPE;

@Component(service = Plugin.class,
        /*configurationPolicy = ConfigurationPolicy.REQUIRE,*/ immediate = true)
@Designate(ocd = Browser.Config.class)
public class Browser extends AbstractPlugin {

    public static final String KEY = "browser";

    protected final List<Widget> WIDGETS = List.of(
            new Page()
    );

    @ObjectClassDefinition(name = "Composum Browser")
    public @interface Config {

        @AttributeDefinition()
        String key() default "browser";

        @AttributeDefinition()
        String label() default "Browser";

        @AttributeDefinition()
        int rank() default 1000;
    }

    /*
    protected static final TemplateResolver RESOLVER = new TemplateResolver();

    protected static final TemplateResource BROWSER_PAGE =
            new TemplateResource(RESOLVER, "com/composum/sling/browser/page");

    static {
        RESOLVER.addResource(BROWSER_PAGE);
    }
    */

    protected final Map<String, View> viewMap = new TreeMap<>();
    protected final Set<View> viewSet = new TreeSet<>(Comparator.comparingInt(View::rank));

    @Reference(
            service = View.class,
            policy = ReferencePolicy.DYNAMIC,
            cardinality = ReferenceCardinality.MULTIPLE,
            policyOption = ReferencePolicyOption.GREEDY
    )
    protected void bindView(@NotNull final View service) {
        synchronized (viewMap) {
            final View replaced = viewMap.put(service.key(), service);
            if (replaced != null) {
                viewSet.remove(replaced);
            }
            viewSet.add(service);
        }
    }

    @SuppressWarnings("unused")
    protected void unbindView(@NotNull final View service) {
        synchronized (viewMap) {
            final View removed = viewMap.remove(service.key());
            if (removed != null) {
                viewSet.remove(removed);
            }
        }
    }

    public @Nullable View getView(@Nullable final String key) {
        return Optional.ofNullable(key).map(viewMap::get).orElse(null);
    }

    public @NotNull Set<View> getViews() {
        return viewSet;
    }

    @Reference(cardinality = ReferenceCardinality.OPTIONAL)
    protected volatile Manager manager;

    protected BundleContext bundleContext;
    protected Config config;

    protected @NotNull Manager manager() {
        return manager;
    }

    @Activate
    @Modified
    protected void activate(final BundleContext bundleContext, final Config config) {
        this.bundleContext = bundleContext;
        this.config = config;
    }

    @Override
    public @NotNull String key() {
        return Optional.ofNullable(config).map(Config::key).orElse(KEY);
    }

    @Override
    public @NotNull String label() {
        return Optional.ofNullable(config).map(Config::label).orElse("Browser");
    }

    @Override
    public int rank() {
        return Optional.ofNullable(config).map(Config::rank).orElse(1000);
    }

    @Override
    public @NotNull List<Widget> widgets() {
        return WIDGETS;
    }

    @Override
    public @NotNull Result<?> process(@NotNull final SlingHttpServletRequest request,
                                      @NotNull final SlingHttpServletResponse response,
                                      @NotNull List<String> selectors) {
        Result<?> result = new Result<>(HttpServletResponse.SC_BAD_REQUEST);
        switch (request.getMethod()) {
            case "GET":
                result = processGet(request, response, selectors);
                break;
            default:
                break;
        }
        return result;
    }

    public @NotNull Result<?> processGet(@NotNull final SlingHttpServletRequest request,
                                         @NotNull final SlingHttpServletResponse response,
                                         @NotNull List<String> selectors) {
        Result<?> result = new Result<>(HttpServletResponse.SC_BAD_REQUEST);
        switch (Manager.consume(selectors, "page")) {
            case "resource":
                result = resource(request);
                break;
            case "tree":
                // tree node data
                break;
            case "view": {
                // content of the current browser view
                final View view = getView(Manager.consume(selectors));
                if (view != null) {
                    result = view.process(request, response, selectors);
                }
            }
            break;
            case "page": {
                // the content of the browser page (tree + view set)
                //final String content = manager.renderTemplate(BROWSER_PAGE);
                final Reader content = openTemplate(getTemplate(new TemplateContext(request, Map.of(
                )), "page"));
                if (content != null) {
                    result = new Result<>(content, HTML_TYPE);
                }
            }
            break;
            default:
                // the browser page descriptor (page data)
                break;
        }
        return result;
    }

    public final Map<String, TemplateBuilder.Factory> templates = Map.of(
            "page", current -> new Template("/com/composum/sling/browser/page.html",
                    new TemplateContext(current, Map.of(
                            "html", Map.of(
                                    "cssClasses", (Supplier<String>) () -> {
                                        return getHtmlCssClasses("browser-page");
                                    }
                            )
                    )), this)
    );

    @Override
    public @Nullable Template getTemplate(@NotNull TemplateContext current, @NotNull String key) {
        return Optional.ofNullable(templates.get(key))
                .map(factory -> factory.create(current))
                .orElse(null);
    }

    @Override
    public @Nullable Reader openTemplate(@Nullable Template template) {
        return Optional.ofNullable(template)
                .map(tmpl -> openTemplate(tmpl.getPath()))
                .map(stream -> new TemplateReader(template,
                        new InputStreamReader(stream, StandardCharsets.UTF_8)))
                .orElse(null);
    }
}
