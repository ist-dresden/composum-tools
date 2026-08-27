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
import java.util.function.Supplier;

import static com.composum.sling.tools.Common.EXT_HTML;
import static com.composum.sling.tools.Common.HTTP_LOCATION;
import static javax.servlet.http.HttpServletResponse.SC_MOVED_TEMPORARILY;
import static javax.servlet.http.HttpServletResponse.SC_NOT_FOUND;

@Component(service = {Actions.class, DefaultActions.class}, immediate = true)
@Designate(ocd = DefaultActions.Config.class)
public class DefaultActions implements Actions {

    public static final int RANK = 2000;

    @ObjectClassDefinition(name = "Composum Browser Sling Actions")
    public @interface Config {

        /**
         * @return this action set's selection rank
         */
        @AttributeDefinition()
        int rank() default DefaultActions.RANK;
    }

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

        public ActionImpl(@NotNull final String key, @Nullable final String icon, @NotNull final String label) {
            this(key, icon, label, null);
        }

        public ActionImpl(@NotNull final String key, @Nullable final String icon, @NotNull final String label,
                          @Nullable final String target) {
            this(key, icon, label, target, null);
        }

        public ActionImpl(@NotNull final String key, @Nullable final String icon, @NotNull final String label,
                          @Nullable final String target, @Nullable final String description) {
            this(key, icon, label, target, description, false);
        }

        public ActionImpl(@NotNull final String key, @Nullable final String icon, @NotNull final String label,
                          @Nullable final String target, @Nullable final String description, boolean newGroup) {
            this.key = key;
            this.icon = icon;
            this.label = label;
            this.description = description;
            this.target = target;
            this.newGroup = newGroup;
        }

        public @Nullable String link(@Nullable final Resource target) {
            return browser().manager().serverPath() + ".browser.action." + key + ".html"
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

    @Reference
    protected Browser browser;

    protected Browser browser() {
        return browser;
    }

    protected @Nullable Resource targetResource(@Nullable SlingHttpServletRequest request) {
        return request != null ? browser().manager().requestResource(request) : null;
    }

    protected @Nullable String targetUrl(@Nullable final Resource target) {
        return targetUrl(target, null);
    }

    protected @Nullable String targetUrl(@Nullable final Resource target, @Nullable final String baseUri) {
        return targetUrl(target, baseUri, null);
    }

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

    protected @Nullable String displayUrl(@Nullable final Resource target) {
        return targetUrl(target, null, EXT_HTML);
    }

    protected @NotNull Result<?> redirect(@Nullable final String redirectUrl) {
        return StringUtils.isNotBlank(redirectUrl) ? new Result<>(SC_MOVED_TEMPORARILY, Map.of(
                HTTP_LOCATION, redirectUrl
        ), null) : new Result<>(SC_NOT_FOUND);
    }

    @Override
    public @NotNull Map<String, Action> set(@NotNull final SlingHttpServletRequest request) {
        Map<String, Action> actions = new LinkedHashMap<>();
        actions.put("view", new ActionImpl("view", "display", "View", "_blank",
                "open the selected resource in a fresh tab") {
            @Override
            public @Nullable String link() {
                return displayUrl(targetResource(request));
            }
        });
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
