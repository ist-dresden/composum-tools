package com.composum.aem.browser;

import com.composum.sling.browser.Actions;
import com.composum.sling.browser.Browser;
import com.composum.sling.browser.DefaultActions;
import com.composum.sling.tools.PlatformConfig;
import com.composum.sling.tools.Result;
import org.apache.sling.api.SlingHttpServletRequest;
import org.apache.sling.api.SlingHttpServletResponse;
import org.apache.sling.api.resource.Resource;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.osgi.service.component.annotations.Component;
import org.osgi.service.component.annotations.Reference;
import org.osgi.service.metatype.annotations.AttributeDefinition;
import org.osgi.service.metatype.annotations.ObjectClassDefinition;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static com.composum.sling.tools.Common.EXT_HTML;
import static com.composum.sling.tools.Common.JCR_PRIMARY_TYPE;

/**
 * AEM-specific extension of {@link DefaultActions}: adds "edit"/"manage"/"activate"/"deactivate"
 * actions for author instances, on top of the generic actions inherited from {@link DefaultActions}.
 */
@Component(service = {Actions.class, AemActions.class}, immediate = true)
public class AemActions extends DefaultActions {

    public AemActions() {
    }

    /**
     * OSGi metatype configuration for this action set's navigation rank.
     */
    @ObjectClassDefinition(name = "Composum Browser AEM Actions")
    public @interface Config {

        /**
         * @return this action set's navigation rank
         */
        @AttributeDefinition()
        int rank() default 5000;
    }

    @Reference
    private PlatformConfig platformConfig;

    /** the browser plugin this action set is registered with */
    @Reference
    protected Browser browser;

    @Override
    protected Browser browser() {
        return browser;
    }

    /**
     * @param resource the resource to start searching from
     * @return the closest {@code cq:Page} ancestor of the given resource (or the resource itself),
     * or {@code null} if none is found
     */
    protected @Nullable Resource containingPage(@Nullable Resource resource) {
        while (resource != null && !"cq:Page".equals(resource.getValueMap().get(JCR_PRIMARY_TYPE, String.class))) {
            resource = resource.getParent();
        }
        return resource;
    }

    @Override
    protected @Nullable String displayUrl(@Nullable final Resource target) {
        String url = super.displayUrl(target);
        return platformConfig.runmodes().contains("author") ? url + "?wcmmode=disabled" : url;
    }

    /**
     * @param target the resource to check
     * @return whether the given resource is eligible for activation/deactivation
     */
    protected boolean isPublishTarget(@Nullable final Resource target) {
        if (target != null) {
            final String path = target.getPath();
            return !path.matches("^/content/dam$") && path.matches("^/(content|conf|etc)/.*");
        }
        return false;
    }

    /**
     * @param target the resource to build an editor link for
     * @return the AEM page editor URL for the resource's containing page, or {@code null} if the
     * resource is not editable
     */
    protected @Nullable String editorUrl(@Nullable Resource target) {
        if (target != null) {
            final String path = target.getPath();
            if (!isPublishTarget(target)) {
                return null;
            } else if (path.matches("^/content/dam(/.*)?")) {
                return null;
            } else if (path.matches("^/content/.*") && (target = containingPage(target)) != null) {
                return targetUrl(target, "/editor.html", EXT_HTML);
            }
        }
        return null;
    }

    /**
     * @param target the resource to build a management link for
     * @return the AEM Assets/Sites management console URL for the resource, or {@code null} if none applies
     */
    protected @Nullable String manageUrl(@Nullable Resource target) {
        if (target != null) {
            final String path = target.getPath();
            if (path.matches("^/content/dam(/.*)?")) {
                return targetUrl(target, "/assets.html");
            } else if (path.matches("^/content/.*") && (target = containingPage(target)) != null) {
                return targetUrl(target, "/sites.html");
            }
        }
        return null;
    }

    @Override
    public @NotNull Map<String, Action> set(@NotNull final SlingHttpServletRequest request) {
        final Resource target = targetResource(request);
        final Map<String, Action> actions = new LinkedHashMap<>(super.set(request));
        if (platformConfig.runmodes().contains("author")) {
            Optional.ofNullable(editorUrl(target)).ifPresent(editorUrl ->
                    actions.put("edit", new ActionImpl("edit", "pencil-square", "Edit", "_blank",
                            "opens the selected resource for editing") {
                        @Override
                        public @NotNull String link() {
                            return editorUrl;
                        }
                    }));
            Optional.ofNullable(manageUrl(target)).ifPresent(manageUrl ->
                    actions.put("manage", new ActionImpl("manage", "diagram-3", "Manage", "_blank",
                            "shows the selected resource in the appropriate management page") {
                        @Override
                        public @NotNull String link() {
                            return manageUrl;
                        }
                    }));
            if (isPublishTarget(target)) {
                actions.put("activate", new ActionImpl("activate", "cloud-arrow-up", "Activate (tree)", null,
                        "triggers a deep tree activation if a page or folder is selected, on a page content or on an asset the selected resource is activated only",
                        true) {

                    @Override
                    public @Nullable String link() {
                        return link(target);
                    }

                    @Override
                    public @NotNull Result<?> process(@NotNull final SlingHttpServletRequest request,
                                                      @NotNull final SlingHttpServletResponse response,
                                                      @NotNull final List<String> selectors) {
                        return redirect(targetUrl(target));
                    }
                });
                actions.put("deactivate", new ActionImpl("deactivate", "cloud-slash", "Deactivate", null,
                        "deactivates the selected resource (implicitly deep)") {

                    @Override
                    public @Nullable String link() {
                        return link(target);
                    }

                    @Override
                    public @NotNull Result<?> process(@NotNull final SlingHttpServletRequest request,
                                                      @NotNull final SlingHttpServletResponse response,
                                                      @NotNull final List<String> selectors) {
                        return redirect(targetUrl(target));
                    }
                });
            }
        }
        return actions;
    }
}
