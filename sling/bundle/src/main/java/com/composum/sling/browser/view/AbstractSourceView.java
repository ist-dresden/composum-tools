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

    /** sorts namespaced property names (e.g. 'jcr:...') before plain ones, alphabetically within each group */
    public static final Comparator<String> PROPERTY_NAME_COMPARATOR =
            Comparator.comparing(name -> (name.contains(":") ? name : "zzz:" + name));

    /** the bundle context this view was activated with */
    protected BundleContext bundleContext;

    /** the configured default recursion depth for the resource dump */
    protected int maxDepth;
    /** whether source mode (hiding technical noise properties/mixins) is enabled by default */
    protected boolean sourceModeSupport;
    /** property name patterns hidden while in source mode */
    protected List<Pattern> nonSourceProperties;
    /** mixin type patterns hidden while in source mode */
    protected List<Pattern> nonSourceMixins;

    /**
     * Default constructor.
     */
    protected AbstractSourceView() {
    }

    /**
     * Renders the (currently empty) parameter form for this view.
     *
     * @param request  the current request
     * @param response the response to write to
     * @return the rendered form, or an empty 'OK' result if no form template is present
     */
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

    /**
     * Initializes the source-mode filtering fields from the subclass's own OSGi configuration.
     *
     * @param bundleContext       the bundle context of the activating component
     * @param maxDepth            the default recursion depth for the resource dump
     * @param sourceMode          whether source mode is enabled by default
     * @param nonSourceProperties property name patterns hidden while in source mode
     * @param nonSourceMixins     mixin type patterns hidden while in source mode
     */
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

    /**
     * Whether the given property may be shown.
     *
     * @param name       the property name to check
     * @param sourceMode whether source mode is currently active for the request
     * @return whether the given property may be shown
     */
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

    /**
     * Removes any mixin type name matching a {@link #nonSourceMixins} pattern.
     *
     * @param values the mixin type names to filter, or 'null'
     * @return the given values with any name matching a {@link #nonSourceMixins} pattern removed,
     * or 'null' if 'values' was 'null'
     */
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

    /**
     * The parsed integer value of a request parameter.
     *
     * @param request      the current request
     * @param name         the request parameter name
     * @param defaultValue the value to return if the parameter is absent or not a valid integer
     * @return the parsed request parameter value, or 'defaultValue'
     */
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

    /**
     * The parsed boolean value of a request parameter.
     *
     * @param request      the current request
     * @param name         the request parameter name
     * @param defaultValue the value to return if the parameter is absent
     * @return 'true' if the request parameter is present and equal to 'true', 'on' or the empty string
     */
    protected boolean getBooleanParameter(@NotNull final SlingHttpServletRequest request,
                                          @NotNull final String name, final boolean defaultValue) {
        final String value = request.getParameter(name);
        return value != null ? List.of("true", "on", "").contains(value.toLowerCase()) : defaultValue;
    }
}
