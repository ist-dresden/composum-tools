package com.composum.aem.browser;

import com.composum.sling.browser.Actions;
import com.composum.sling.browser.Browser;
import com.composum.sling.browser.DefaultActions;
import com.composum.sling.tools.Common;
import com.composum.sling.tools.PlatformConfig;
import com.composum.sling.tools.Result;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.Getter;
import org.apache.sling.api.SlingHttpServletRequest;
import org.apache.sling.api.SlingHttpServletResponse;
import org.apache.sling.api.resource.Resource;
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

import javax.jcr.Session;
import javax.servlet.http.HttpServletResponse;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Pattern;

import static com.composum.sling.tools.Common.EXT_HTML;
import static com.composum.sling.tools.Common.JCR_CONTENT;
import static com.composum.sling.tools.Common.JCR_PRIMARY_TYPE;
import static com.composum.sling.tools.Common.NT_FOLDER;
import static com.composum.sling.tools.Common.ORDERED_FOLDER;
import static com.composum.sling.tools.Common.SLING_FOLDER;

/**
 * AEM-specific extension of {@link DefaultActions}: adds "edit"/"manage"/"activate"/"deactivate"
 * actions for author instances, on top of the generic actions inherited from {@link DefaultActions}.
 * Registered with a higher default {@link Config#rank() rank} than {@link DefaultActions} so that
 * {@code Browser} prefers this implementation whenever both are present.
 */
@Component(service = {Actions.class, AemActions.class}, immediate = true)
@Designate(ocd = AemActions.Config.class)
public class AemActions extends DefaultActions {

    /** this action set's default selection rank */
    public static final int RANK = 5000;

    /**
     * Default constructor.
     */
    public AemActions() {
    }

    /**
     * OSGi metatype configuration for this action set's selection rank.
     */
    @ObjectClassDefinition(name = "Composum Browser AEM Actions")
    public @interface Config {

        /**
         * This action set's selection rank.
         *
         * @return this action set's selection rank
         */
        @AttributeDefinition()
        int rank() default AemActions.RANK;

        /**
         * The path patterns to skip during a deep tree activation.
         *
         * @return path patterns to skip (with their whole subtree) during a deep tree activation
         */
        @AttributeDefinition(name = "Deep Activation Exclude Patterns")
        String[] deepActivationExcludePatterns() default {};
    }

    /** child resources whose name matches this are skipped during a deep tree activation - JCR system/ACL nodes are not independently replicable resources, and are handled implicitly by their parent's replication */
    public static final Pattern JCR_SYSTEM_NODE = Pattern.compile("^(jcr|rep):.*$");

    /** resource types treated as a folder/page for the purpose of a deep tree activation and manage action */
    public static final List<String> FOLDER_TYPES = List.of(
            NT_FOLDER, SLING_FOLDER, ORDERED_FOLDER, "cq:Page"
    );

    /** the configured deep-activation exclude patterns (see {@link Config#deepActivationExcludePatterns()}) */
    protected List<Pattern> deepActivationExcludePatterns;

    @Reference
    private PlatformConfig platformConfig;

    /** the browser plugin this action set is registered with */
    @Reference
    protected Browser browser;

    @Override
    protected Browser browser() {
        return browser;
    }

    /** the bundle context this action set was activated with */
    protected BundleContext bundleContext;
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
        this.deepActivationExcludePatterns = Common.patternList(config.deepActivationExcludePatterns());
    }

    /**
     * The closest {@code cq:Page} ancestor of the given resource.
     *
     * @param resource the resource to start searching from
     * @return the closest {@code cq:Page} ancestor of the given resource (or the resource itself),
     * or {@code null} if none is found
     */
    protected @Nullable Resource containingParent(@Nullable Resource resource, List<String> types) {
        while (resource != null && !types.contains(resource.getValueMap().get(JCR_PRIMARY_TYPE, String.class))) {
            resource = resource.getParent();
        }
        return resource;
    }

    @Override
    protected @Nullable String displayUrl(@Nullable final Resource target) {
        String url = super.displayUrl(target);
        return url != null ? (platformConfig.runmodes().contains("author") ? url + "?wcmmode=disabled" : url) : null;
    }

    /**
     * Whether the given resource is eligible for activation/deactivation.
     *
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
     * The AEM page editor URL for the resource's containing page.
     *
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
            } else if (path.matches("^/content/.*")
                    && (target = containingParent(target, Collections.singletonList("cq:Page"))) != null) {
                return targetUrl(target, "/editor.html", EXT_HTML);
            }
        }
        return null;
    }

    /**
     * The AEM Assets/Sites management console URL for the resource.
     *
     * @param target the resource to build a management link for
     * @return the AEM Assets/Sites management console URL for the resource, or {@code null} if none applies
     */
    protected @Nullable String manageUrl(@Nullable Resource target) {
        if (target != null) {
            final String path = target.getPath();
            if (path.matches("^/content/dam(/.*)?")) {
                return targetUrl(target, "/assets.html");
            } else if (path.matches("^/content(/.*)?")
                    && (target = containingParent(target, FOLDER_TYPES)) != null) {
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
                    public @NotNull String method() {
                        return "GET";
                    }

                    @Override
                    public @NotNull Result<?> process(@NotNull final SlingHttpServletRequest request,
                                                      @NotNull final SlingHttpServletResponse response,
                                                      @NotNull final List<String> selectors) {
                        Result<ActionResult> result = new Result<>(HttpServletResponse.SC_NOT_FOUND);
                        final Resource target = browser().manager.requestResource(request);
                        if (target != null) {
                            result = activate(target, isReplicationFolder(target));
                        }
                        return result;
                    }
                });
                actions.put("deactivate", new ActionImpl("deactivate", "cloud-slash", "Deactivate", null,
                        "deactivates the selected resource (implicitly deep)") {

                    @Override
                    public @Nullable String link() {
                        return link(target);
                    }

                    @Override
                    public @NotNull String method() {
                        return "GET";
                    }

                    @Override
                    public @NotNull Result<?> process(@NotNull final SlingHttpServletRequest request,
                                                      @NotNull final SlingHttpServletResponse response,
                                                      @NotNull final List<String> selectors) {
                        Result<ActionResult> result = new Result<>(HttpServletResponse.SC_NOT_FOUND);
                        final Resource target = browser().manager.requestResource(request);
                        if (target != null) {
                            result = deactivate(target);
                        }
                        return result;
                    }
                });
            }
        }
        return actions;
    }

    /**
     * Whether the given resource is a folder or page, and thus eligible for a deep tree activation.
     *
     * @param target the resource to check
     * @return whether the given resource is a folder or page
     */
    protected boolean isReplicationFolder(@NotNull final Resource target) {
        final String primaryType = target.getValueMap().get(JCR_PRIMARY_TYPE, "");
        return FOLDER_TYPES.contains(primaryType);
    }

    /**
     * The JSON result of an activate/deactivate action: the paths that were actually replicated,
     * and an error message if the action failed.
     */
    @Getter
    @JsonInclude(JsonInclude.Include.NON_NULL)
    @JsonIgnoreProperties(ignoreUnknown = true)
    protected static class ActionResult {

        private final List<String> targets;
        private final String error;

        /**
         * @param targets the paths that were actually replicated
         * @param error   an error message, or {@code null} if the action succeeded
         */
        public ActionResult(@Nullable final List<String> targets, @Nullable final String error) {
            this.targets = targets;
            this.error = error;
        }
    }

    /**
     * Activates the given resource, either as a single replication or, if {@code deep}, as a
     * recursive tree activation (see {@link #replicateDeep}).
     *
     * @param target the resource to activate
     * @param deep   whether to recursively activate the resource's subtree
     * @return the JSON result of the activation
     */
    protected Result<ActionResult> activate(@NotNull final Resource target, final boolean deep) {
        Result<ActionResult> result = new Result<>(HttpServletResponse.SC_INTERNAL_SERVER_ERROR);
        result.setContentType(Common.JSON_TYPE);
        final AemReplicator replicator = replicator();
        if (replicator != null) {
            final List<String> targets = new ArrayList<>();
            try {
                if (deep) {
                    replicateDeep(target, replicator, targets);
                } else {
                    final Resource replicationTarget = replicationTarget(target);
                    replicator.replicate(replicationTarget, true);
                    targets.add(replicationTarget.getPath());
                }
                result = new Result<>(new ActionResult(targets, null), Common.JSON_TYPE);
            } catch (Exception ex) {
                result.setData(new ActionResult(targets, ex.getMessage()));
            }
        }
        return result;
    }

    /**
     * AEM's {@code Replicator} expects the actual content-bearing resource (e.g. a {@code cq:Page}
     * or {@code dam:Asset}) as replication target, never a {@code jcr:content} node directly - the
     * registered {@code ContentBuilder} resolves and bundles the {@code jcr:content} subtree itself
     * once it is given the containing resource's path. Since the browser lets you select any
     * resource, including one arbitrarily deep inside a {@code jcr:content} subtree (e.g. a
     * component, or - with a nested {@code jcr:content} of its own - an asset rendition), this walks
     * all the way up to the repository root and returns the parent of the *outermost*
     * {@code jcr:content} ancestor found, not just the nearest one: for
     * {@code .../asset.jpg/jcr:content/renditions/original/jcr:content}, the nearest {@code
     * jcr:content} belongs to the rendition file ("original") and would yield the wrong target -
     * only the outermost one, belonging to the asset itself, yields the correct replication root.
     *
     * @param resource the selected resource
     * @return the given resource, or the closest actual content-bearing ancestor if {@code resource}
     * is, or is nested inside, a {@code jcr:content} subtree
     */
    protected @NotNull Resource replicationTarget(@NotNull final Resource resource) {
        Resource target = resource;
        for (Resource current = resource; current != null; current = current.getParent()) {
            if (JCR_CONTENT.equals(current.getName())) {
                final Resource parent = current.getParent();
                if (parent != null) {
                    target = parent;
                }
            }
        }
        return target;
    }

    /**
     * Recursively activates a resource and its subtree, adapted from a Groovy console script used
     * for the same purpose: {@code jcr:}/{@code rep:} system nodes are skipped (with their whole
     * subtree) since they are not independently replicable resources and are handled implicitly by
     * their parent's replication; resources matching {@link #deepActivationExcludePatterns} are
     * skipped likewise. A failure on one resource aborts the walk; the paths already collected in
     * {@code targets} up to that point are still reported back by the calling {@link #activate}.
     *
     * @param resource   the resource to activate, together with its subtree
     * @param replicator the replicator to use
     * @param targets    collects the paths that were actually (successfully) activated
     * @throws Exception if the reflective replication call fails
     */
    protected void replicateDeep(@NotNull final Resource resource, @NotNull final AemReplicator replicator,
                                 @NotNull final List<String> targets) throws Exception {
        if (!JCR_SYSTEM_NODE.matcher(resource.getName()).matches()) {
            final String path = resource.getPath();
            for (final Pattern pattern : deepActivationExcludePatterns) {
                if (pattern.matcher(path).matches()) {
                    return;
                }
            }
            replicator.replicate(resource, true);
            targets.add(path);
            for (final Resource child : resource.getChildren()) {
                replicateDeep(child, replicator, targets);
            }
        }
    }

    /**
     * Deactivates the given resource (never recursive, unlike {@link #activate}).
     *
     * @param target the resource to deactivate
     * @return the JSON result of the deactivation
     */
    protected Result<ActionResult> deactivate(@NotNull final Resource target) {
        Result<ActionResult> result = new Result<>(HttpServletResponse.SC_INTERNAL_SERVER_ERROR);
        result.setContentType(Common.JSON_TYPE);
        final AemReplicator replicator = replicator();
        if (replicator != null) {
            final Resource replicationTarget = replicationTarget(target);
            try {
                replicator.replicate(replicationTarget, false);
                result = new Result<>(new ActionResult(List.of(replicationTarget.getPath()), null), Common.JSON_TYPE);
            } catch (Exception ex) {
                result.setData(new ActionResult(Collections.emptyList(), ex.getMessage()));
            }
        }
        return result;
    }

    /**
     * A minimal reflective facade for the AEM {@code com.day.cq.replication.Replicator} service, used
     * to trigger activation/deactivation without a compile-time (or even an OSGi {@code Import-Package})
     * dependency on the AEM replication API: the {@code ReplicationActionType} class is loaded via the
     * service object's own classloader (the AEM core bundle, which of course exports it) rather than
     * this bundle's, so this bundle never needs to import {@code com.day.cq.replication} itself - it
     * stays fully optional and plain-Sling-compatible.
     */
    protected static class AemReplicator {

        /** the fully qualified class name of the AEM {@code Replicator} service */
        public static final String SERVICE_CLASS = "com.day.cq.replication.Replicator";
        /** the fully qualified class name of AEM's replication action type */
        public static final String TYPE_CLASS = "com.day.cq.replication.ReplicationActionType";

        private final Object service;
        private final Method replicateMethod;
        private final Object activateType;
        private final Object deactivateType;

        /**
         * Reflectively resolves the {@code replicate} method and the activate/deactivate type
         * constants against the given service instance's own classloader.
         *
         * @param service the AEM {@code Replicator} service instance
         * @throws ReflectiveOperationException if the AEM replication API does not have the
         *                                      expected shape
         */
        public AemReplicator(@NotNull final Object service) throws ReflectiveOperationException {
            this.service = service;
            final Class<?> serviceClass = service.getClass();
            final Class<?> typeClass = serviceClass.getClassLoader().loadClass(TYPE_CLASS);
            this.activateType = typeClass.getField("ACTIVATE").get(null);
            this.deactivateType = typeClass.getField("DEACTIVATE").get(null);
            this.replicateMethod = serviceClass.getMethod("replicate", Session.class, typeClass, String.class);
        }

        /**
         * Replicates (activates or deactivates) the given resource.
         *
         * @param target   the resource to replicate
         * @param activate {@code true} to activate, {@code false} to deactivate
         * @throws Exception if the reflective {@code replicate} call fails
         */
        public void replicate(@NotNull final Resource target, final boolean activate) throws Exception {
            replicateMethod.invoke(service, target.getResourceResolver().adaptTo(Session.class),
                    activate ? activateType : deactivateType, target.getPath());
        }
    }

    protected @Nullable AemReplicator replicator() {
        final ServiceReference<?> reference = bundleContext.getServiceReference(AemReplicator.SERVICE_CLASS);
        if (reference != null) {
            final Object service = bundleContext.getService(reference);
            if (service != null) {
                try {
                    return new AemReplicator(service);
                } catch (ReflectiveOperationException ignored) {
                }
            }
        }
        return null;
    }
}
