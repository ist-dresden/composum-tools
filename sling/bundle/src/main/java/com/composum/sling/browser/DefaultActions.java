package com.composum.sling.browser;

import com.composum.sling.tools.Result;
import com.composum.sling.tools.template.TemplateContext;
import org.apache.commons.lang3.StringUtils;
import org.apache.sling.api.SlingHttpServletRequest;
import org.apache.sling.api.resource.Resource;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.osgi.service.component.annotations.Component;
import org.osgi.service.component.annotations.Reference;
import org.osgi.service.metatype.annotations.AttributeDefinition;
import org.osgi.service.metatype.annotations.Designate;
import org.osgi.service.metatype.annotations.ObjectClassDefinition;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Supplier;

import static com.composum.sling.tools.Common.EXT_HTML;
import static com.composum.sling.tools.Common.HTTP_LOCATION;
import static com.composum.sling.tools.Common.JCR_CONTENT;
import static com.composum.sling.tools.Common.SLING_RESOURCE_TYPE;
import static javax.servlet.http.HttpServletResponse.SC_MOVED_TEMPORARILY;
import static javax.servlet.http.HttpServletResponse.SC_NOT_FOUND;

/**
 * The default set of browser actions (currently the 'view' action opening the selected resource in a fresh tab).
 */
@Component(service = {Actions.class, DefaultActions.class}, immediate = true)
@Designate(ocd = DefaultActions.Config.class)
public class DefaultActions implements Actions {

    /** The default selection rank of this action set. */
    public static final int RANK = 2000;

    /**
     * Default constructor.
     */
    public DefaultActions() {
    }

    /**
     * The OSGi configuration of the default actions.
     */
    @ObjectClassDefinition(name = "Composum Browser Sling Actions")
    public @interface Config {

        /**
         * The selection rank of this action set.
         *
         * @return this action set's selection rank
         */
        @AttributeDefinition()
        int rank() default DefaultActions.RANK;
    }

    /**
     * The base implementation of an action rendered as a link or button.
     */
    protected abstract class ActionImpl implements Action {

        @NotNull
        final String key;

        @Nullable
        final String icon;

        @NotNull
        final String label;

        @Nullable
        final String description;

        @Nullable
        final String target;

        final boolean newGroup;

        /**
         * Creates an action without target and description.
         *
         * @param key   the key of the action
         * @param icon  the icon of the action
         * @param label the label of the action
         */
        public ActionImpl(@NotNull final String key, @Nullable final String icon, @NotNull final String label) {
            this(key, icon, label, null);
        }

        /**
         * Creates an action without description.
         *
         * @param key    the key of the action
         * @param icon   the icon of the action
         * @param label  the label of the action
         * @param target the link target of the action
         */
        public ActionImpl(@NotNull final String key, @Nullable final String icon, @NotNull final String label,
                          @Nullable final String target) {
            this(key, icon, label, target, null);
        }

        /**
         * Creates an action which does not start a new group.
         *
         * @param key         the key of the action
         * @param icon        the icon of the action
         * @param label       the label of the action
         * @param target      the link target of the action
         * @param description the description (title) of the action
         */
        public ActionImpl(@NotNull final String key, @Nullable final String icon, @NotNull final String label,
                          @Nullable final String target, @Nullable final String description) {
            this(key, icon, label, target, description, false);
        }

        /**
         * Creates an action.
         *
         * @param key         the key of the action
         * @param icon        the icon of the action
         * @param label       the label of the action
         * @param target      the link target of the action
         * @param description the description (title) of the action
         * @param newGroup    whether the action starts a new group of actions
         */
        public ActionImpl(@NotNull final String key, @Nullable final String icon, @NotNull final String label,
                          @Nullable final String target, @Nullable final String description, boolean newGroup) {
            this.key = key;
            this.icon = icon;
            this.label = label;
            this.description = description;
            this.target = target;
            this.newGroup = newGroup;
        }

        /**
         * Builds the link to this action for the given target resource.
         *
         * @param target the resource the action is applied to
         * @return the action link
         */
        public @Nullable String link(@Nullable final Resource target) {
            return browser().manager.serverPath() + ".browser.action." + key + ".html"
                    + (target != null ? target.getPath() : "");
        }

        @Override
        public @NotNull TemplateContext.Values values(@NotNull TemplateContext.Values values) {
            return values
                    .with("key", key)
                    .with("icon", icon)
                    .with("label", label)
                    .with("title", description)
                    .with("target", target)
                    .with("link", (Supplier<?>) this::link)
                    .with("method", (Supplier<?>) this::method)
                    .with("newGroup", newGroup);
        }
    }

    /** The browser service. */
    @Reference
    protected Browser browser;

    /**
     * Returns the browser service.
     *
     * @return the browser service
     */
    protected Browser browser() {
        return browser;
    }

    /**
     * Determines the resource the actions are applied to.
     *
     * @param request the current request
     * @return the requested resource, or {@code null} if there is no request
     */
    protected @Nullable Resource targetResource(@Nullable SlingHttpServletRequest request) {
        return request != null ? browser().manager.requestResource(request) : null;
    }

    /**
     * Builds the URL of the given target resource.
     *
     * @param target the target resource
     * @return the URL of the target
     */
    protected @Nullable String targetUrl(@Nullable final Resource target) {
        return targetUrl(target, null);
    }

    /**
     * Builds the URL of the given target resource.
     *
     * @param target  the target resource
     * @param baseUri the base URI to prepend
     * @return the URL of the target
     */
    protected @Nullable String targetUrl(@Nullable final Resource target, @Nullable final String baseUri) {
        return targetUrl(target, baseUri, null);
    }

    /**
     * Builds the URL of the given target resource.
     *
     * @param target    the target resource
     * @param baseUri   the base URI to prepend
     * @param extension the extension to append if the path of the target has no extension yet
     * @return the URL of the target
     */
    protected @Nullable String targetUrl(@Nullable final Resource target, @Nullable final String baseUri, @Nullable final String extension) {
        final StringBuilder uri = new StringBuilder();
        if (StringUtils.isNotBlank(baseUri)) {
            uri.append(baseUri);
        }
        if (target != null) {
            final String path = target.getPath();
            uri.append(StringUtils.isBlank(extension) || path.matches(".*\\.[^.]+$") ? path : path + "." + extension);
        }
        return uri.toString();
    }

    /**
     * Builds the URL to display the given resource as HTML.
     *
     * @param target the target resource
     * @return the display URL, or {@code null} if the resource (or its content child) has no resource type
     */
    protected @Nullable String displayUrl(@Nullable final Resource target) {
        final String resourceType = Optional.ofNullable(target)
                .map(r -> r.getValueMap().get(SLING_RESOURCE_TYPE, String.class))
                .orElse(Optional.ofNullable(target)
                        .map(t -> t.getChild(JCR_CONTENT))
                        .map(r -> r.getValueMap().get(SLING_RESOURCE_TYPE, String.class))
                        .orElse(null));
        return StringUtils.isNotBlank(resourceType) ? targetUrl(target, null, EXT_HTML) : null;
    }

    /**
     * Builds a redirect result.
     *
     * @param redirectUrl the URL to redirect to
     * @return a redirect to the URL, or a 'not found' result if the URL is blank
     */
    protected @NotNull Result<?> redirect(@Nullable final String redirectUrl) {
        return StringUtils.isNotBlank(redirectUrl) ? new Result<>(SC_MOVED_TEMPORARILY, Map.of(
                HTTP_LOCATION, redirectUrl
        ), null) : new Result<>(SC_NOT_FOUND);
    }

    @Override
    public @NotNull Map<String, Action> set(@NotNull final SlingHttpServletRequest request) {
        final Resource target = targetResource(request);
        Map<String, Action> actions = new LinkedHashMap<>();
        Optional.ofNullable(displayUrl(target)).ifPresent(displayUrl ->
                actions.put("view", new ActionImpl("view", "display", "View", "_blank",
                        "open the selected resource in a fresh tab") {
                    @Override
                    public @Nullable String link() {
                        return displayUrl;
                    }
                }));
        return actions;
    }

    @Override
    public @NotNull Collection<String> styles() {
        return List.of();
    }

    @Override
    public @NotNull Collection<String> scripts() {
        return List.of();
    }
}
