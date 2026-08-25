package com.composum.aem.browser.view;

import com.composum.sling.browser.Browser;
import com.composum.sling.browser.View;
import com.composum.sling.browser.view.DisplayView;
import com.composum.sling.tools.PlatformConfig;
import com.composum.sling.tools.Result;
import com.composum.sling.tools.template.Template;
import com.composum.sling.tools.template.TemplateContext;
import com.composum.sling.tools.template.TemplateContext.Values;
import org.apache.sling.api.SlingHttpServletRequest;
import org.apache.sling.api.SlingHttpServletResponse;
import org.jetbrains.annotations.NotNull;
import org.osgi.framework.BundleContext;
import org.osgi.service.component.annotations.Activate;
import org.osgi.service.component.annotations.Component;
import org.osgi.service.component.annotations.Deactivate;
import org.osgi.service.component.annotations.Modified;
import org.osgi.service.component.annotations.Reference;
import org.osgi.service.metatype.annotations.AttributeDefinition;
import org.osgi.service.metatype.annotations.Designate;
import org.osgi.service.metatype.annotations.ObjectClassDefinition;

import java.io.Reader;
import java.util.Optional;

import static com.composum.sling.tools.Common.HTML_TYPE;

/**
 * AEM-specific extension of {@link DisplayView}: on author instances, replaces the generic
 * "Preview" tab's content with an AEM page-editor-aware variant (see {@link #params}).
 */
@Component(service = {View.class, AemDisplayView.class}, immediate = true)
@Designate(ocd = AemDisplayView.Config.class)
public class AemDisplayView extends DisplayView {

    public AemDisplayView() {
    }

    /**
     * OSGi metatype configuration for this view's key/label/rank.
     */
    @ObjectClassDefinition(name = "Composum Browser AEM Display View")
    public @interface Config {

        /**
         * @return this view's selector key
         */
        @AttributeDefinition()
        String key() default DisplayView.KEY;

        /**
         * @return this view's tab label
         */
        @AttributeDefinition()
        String label() default "Preview";

        /**
         * @return this view's navigation rank
         */
        @AttributeDefinition()
        int rank() default 6500;
    }

    @Reference
    private PlatformConfig platformConfig;

    /** the browser plugin this view is registered with */
    @Reference
    protected Browser browser;

    /** the current OSGi configuration */
    protected Config config;

    /**
     * @param bundleContext the bundle context of this component
     * @param config        the current OSGi configuration
     */
    @Activate
    @Modified
    protected void activate(final BundleContext bundleContext, final Config config) {
        this.bundleContext = bundleContext;
        this.config = config;
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
    public PlatformConfig platformConfig() {
        return platformConfig;
    }

    @Override
    public @NotNull String key() {
        return Optional.ofNullable(config).map(Config::key).orElse(KEY);
    }

    @Override
    public @NotNull String label() {
        return Optional.ofNullable(config).map(Config::label).orElse("Preview");
    }

    @Override
    public int rank() {
        return Optional.ofNullable(config).map(Config::rank).orElse(6500);
    }

    @Override
    protected Result<?> params(@NotNull final SlingHttpServletRequest request,
                               @NotNull final SlingHttpServletResponse response) {
        if (platformConfig().runmodes().contains("author")) {
            final Template template = getTemplate(new TemplateContext(new Values()),
                    "/aem/browser/view/display/params.html");
            final Reader content = browser().templateReader(template);
            if (content != null) {
                return new Result<>(content, HTML_TYPE);
            }
        }
        return super.params(request, response);
    }
}
