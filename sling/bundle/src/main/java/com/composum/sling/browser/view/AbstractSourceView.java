package com.composum.sling.browser.view;

import com.composum.sling.browser.AbstractView;
import com.composum.sling.tools.Common;
import com.composum.sling.tools.Result;
import com.composum.sling.tools.template.Template;
import com.composum.sling.tools.template.TemplateContext;
import org.apache.commons.lang3.StringUtils;
import org.apache.sling.api.SlingHttpServletRequest;
import org.apache.sling.api.SlingHttpServletResponse;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.osgi.framework.BundleContext;

import java.io.Reader;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.regex.Pattern;

import static com.composum.sling.tools.Common.HTML_TYPE;
import static javax.servlet.http.HttpServletResponse.SC_OK;

/**
 * Shared source-mode filtering and request-parameter parsing for resource-dump views ({@link JsonView},
 * {@link XmlView}): hides technical noise properties/mixins unless disabled per request via the 'raw'
 * parameter, and caps the requested dump depth at the configured maximum.
 */
public abstract class AbstractSourceView extends AbstractView {

    public static final Comparator<String> PROPERTY_NAME_COMPARATOR =
            Comparator.comparing(name -> (name.contains(":") ? name : "zzz:" + name));

    protected BundleContext bundleContext;

    protected int maxDepth;
    protected boolean sourceModeSupport;
    protected List<Pattern> nonSourceProperties;
    protected List<Pattern> nonSourceMixins;

    protected Result<?> params(@NotNull final SlingHttpServletRequest request,
                               @NotNull final SlingHttpServletResponse response) {
        final Template template = getTemplate(new TemplateContext(new TemplateContext.Values()),
                "/sling/browser/view/source/params.html");
        final Reader content = browser().templateReader(template);
        if (content != null) {
            return new Result<>(content, HTML_TYPE);
        }
        return new Result<>(SC_OK);
    }

    protected void activateSourceMode(@NotNull final BundleContext bundleContext,
                                      final int maxDepth, final boolean sourceMode,
                                      @NotNull final String[] nonSourceProperties,
                                      @NotNull final String[] nonSourceMixins) {
        this.bundleContext = bundleContext;
        this.maxDepth = maxDepth;
        this.sourceModeSupport = sourceMode;
        this.nonSourceProperties = Common.patternList(nonSourceProperties);
        this.nonSourceMixins = Common.patternList(nonSourceMixins);
    }

    protected boolean isAllowedProperty(@NotNull final String name, final boolean sourceMode) {
        if (!browser().manager().isAllowedProperty(name)) {
            return false;
        }
        if (sourceMode) {
            for (final Pattern excluded : nonSourceProperties) {
                if (excluded.matcher(name).matches()) {
                    return false;
                }
            }
        }
        return true;
    }

    protected @Nullable String[] filterMixins(@Nullable final String[] values) {
        if (values == null) {
            return null;
        }
        final List<String> allowed = new ArrayList<>();
        for (final String value : values) {
            boolean excluded = false;
            for (final Pattern pattern : nonSourceMixins) {
                if (value != null && pattern.matcher(value).matches()) {
                    excluded = true;
                    break;
                }
            }
            if (!excluded) {
                allowed.add(value);
            }
        }
        return allowed.toArray(new String[0]);
    }

    protected Integer getIntParameter(@NotNull final SlingHttpServletRequest request,
                                      @NotNull final String name, final Integer defaultValue) {
        final String value = request.getParameter(name);
        if (StringUtils.isNotBlank(value)) {
            try {
                return Integer.parseInt(value.trim());
            } catch (final NumberFormatException ignore) {
            }
        }
        return defaultValue;
    }

    protected boolean getBooleanParameter(@NotNull final SlingHttpServletRequest request,
                                          @NotNull final String name, final boolean defaultValue) {
        final String value = request.getParameter(name);
        return value != null ? List.of("true", "on", "").contains(value.toLowerCase()) : defaultValue;
    }
}
