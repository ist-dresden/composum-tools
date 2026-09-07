package com.composum.sling.changes;

import com.composum.sling.tools.AbstractToolsPlugin;
import com.composum.sling.tools.Manager;
import com.composum.sling.tools.PlatformConfig;
import com.composum.sling.tools.ReferencesService;
import com.composum.sling.tools.Result;
import com.composum.sling.tools.ToolsPlugin;
import com.composum.sling.tools.dto.Widget;
import com.composum.sling.tools.template.Template;
import com.composum.sling.tools.template.TemplateContext;
import com.composum.sling.tools.template.TemplateContext.Values;
import org.apache.commons.lang3.StringUtils;
import org.apache.sling.api.SlingHttpServletRequest;
import org.apache.sling.api.SlingHttpServletResponse;
import org.apache.sling.api.resource.PersistenceException;
import org.apache.sling.api.resource.Resource;
import org.apache.sling.api.resource.ResourceResolver;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.osgi.framework.BundleContext;
import org.osgi.service.component.annotations.Activate;
import org.osgi.service.component.annotations.Component;
import org.osgi.service.component.annotations.ConfigurationPolicy;
import org.osgi.service.component.annotations.Deactivate;
import org.osgi.service.component.annotations.Modified;
import org.osgi.service.component.annotations.Reference;
import org.osgi.service.component.annotations.ReferenceCardinality;
import org.osgi.service.component.annotations.ReferencePolicy;
import org.osgi.service.metatype.annotations.AttributeDefinition;
import org.osgi.service.metatype.annotations.Designate;
import org.osgi.service.metatype.annotations.ObjectClassDefinition;

import javax.servlet.http.HttpServletResponse;
import java.io.Reader;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static com.composum.sling.tools.Common.HTML_TYPE;
import static javax.servlet.http.HttpServletResponse.SC_BAD_REQUEST;
import static javax.servlet.http.HttpServletResponse.SC_FORBIDDEN;
import static javax.servlet.http.HttpServletResponse.SC_NOT_ACCEPTABLE;
import static javax.servlet.http.HttpServletResponse.SC_NOT_FOUND;

/**
 * Staged, CRXDE-like node-modification actions (Create/Delete/Move/Copy-Paste Node, Change
 * Property) as a standalone capability: a {@link ToolsPlugin} with no page and no dashboard tile
 * of its own (see {@link #widgets()}) that exists purely to be optionally consumed by other
 * plugins through the {@link ChangesService} interface it implements. Every mutation is
 * resolver-API only (never {@code javax.jcr.*} - see {@link ChangeOperations}) and staged in a
 * per-HTTP-session {@link ChangeSession} until an explicit commit ("Commit All") or discard
 * ("Revert All") - there is deliberately no immediate-commit mode, unlike every other mutating
 * plugin in this project.
 * <p>
 * {@code ConfigurationPolicy.REQUIRE}: like Package Manager/User Manager, this component simply
 * does not activate without an explicit OSGi configuration (an empty one is enough) - here that
 * single fact <em>is</em> the feature's on/off switch for every consumer, since a consumer binds
 * {@link ChangesService} as an {@code OPTIONAL}/{@code DYNAMIC} reference and falls back to
 * read-only behaviour whenever it is unbound. {@link Config#writeEnabled()} is a second, runtime
 * pause independent of that (mirrors the same two-layer pattern Package/User Manager use).
 */
@Component(service = {ToolsPlugin.class, ChangesService.class, Changes.class},
        configurationPolicy = ConfigurationPolicy.REQUIRE, immediate = true)
@Designate(ocd = Changes.Config.class)
public class Changes extends AbstractToolsPlugin implements ChangesService {

    public static final String KEY = "changes";
    public static final String LABEL = "Changes";
    public static final int RANK = 0;

    private static final String DIALOGS_ROOT = "/sling/changes/dialogs/";

    @ObjectClassDefinition(name = "Composum Changes")
    public @interface Config {

        @AttributeDefinition()
        String key() default Changes.KEY;

        @AttributeDefinition()
        String label() default Changes.LABEL;

        @AttributeDefinition()
        int rank() default Changes.RANK;

        @AttributeDefinition()
        boolean enabled() default true;

        @AttributeDefinition(name = "Write Enabled",
                description = "whether node-modification actions (create/delete/move/copy, property changes) are allowed")
        boolean writeEnabled() default true;
    }

    /** the manager this plugin is registered with */
    @Reference
    private void bindManager(Manager service) {
        manager = service;
    }

    /** the jcr:primaryType/jcr:mixinTypes autocomplete's candidate lists (see {@link #primaryTypeSuggest}/{@link #mixinTypeSuggest}) */
    @Reference
    protected PlatformConfig platformConfig;

    /**
     * The optionally bound reference-search capability backing Move's "adjust references" option
     * (see {@link #moveNode}) - the Move dialog only offers that option while this is bound.
     */
    @Reference(cardinality = ReferenceCardinality.OPTIONAL, policy = ReferencePolicy.DYNAMIC)
    protected volatile @Nullable ReferencesService referencesService;

    protected Config config;

    @Activate
    @Modified
    protected void activate(final BundleContext bundleContext, final Config config) {
        this.config = config;
        manager.plugins().attach(this);
    }

    @Deactivate
    protected void deactivate() {
        manager.plugins().detach(this);
    }

    @Override
    public @NotNull String key() {
        return Optional.ofNullable(config).map(Config::key).orElse(KEY);
    }

    @Override
    public @NotNull String label() {
        return Optional.ofNullable(config).map(Config::label).orElse(LABEL);
    }

    @Override
    public int rank() {
        return Optional.ofNullable(config).map(Config::rank).orElse(RANK);
    }

    @Override
    public boolean isEnabled() {
        return config.enabled();
    }

    /** no page, no dashboard tile - see the class Javadoc */
    @Override
    public @NotNull List<Widget> widgets() {
        return List.of();
    }

    // ChangesService

    @Override
    public @NotNull ResourceResolver resolver(@NotNull final SlingHttpServletRequest request) {
        final ChangeSession session = ChangeSession.get(request, false);
        return session != null ? session.resolver() : request.getResourceResolver();
    }

    @Override
    public boolean writeEnabled() {
        return config.writeEnabled();
    }

    @Override
    public @NotNull String dialogUri() {
        return manager.serverPath() + "." + key() + ".dialog.";
    }

    @Override
    public @NotNull String pendingUri() {
        return manager.serverPath() + "." + key() + ".pending.html";
    }

    @Override
    public int pendingCount(@NotNull final SlingHttpServletRequest request) {
        final ChangeSession session = ChangeSession.get(request, false);
        return session != null ? session.pendingChanges().size() : 0;
    }

    @Override
    public @NotNull String styleResource() {
        return "/sling/changes/style.css";
    }

    @Override
    public @NotNull String scriptResource() {
        return "/sling/changes/script.js";
    }

    @Override
    public @NotNull String actionLink(@NotNull final String action) {
        return manager.serverPath() + "." + key() + "." + action + ".json";
    }

    @Override
    public boolean isProtectedProperty(@NotNull final String name) {
        return ChangeOperations.PROTECTED_PROPERTIES.contains(name);
    }

    // Request dispatch

    @Override
    public @NotNull Result<?> process(@NotNull final SlingHttpServletRequest request,
                                      @NotNull final SlingHttpServletResponse response,
                                      @NotNull List<String> selectors) {
        switch (request.getMethod()) {
            case "GET":
                return processGet(request, response, selectors);
            case "POST":
                return processPost(request, response, selectors);
            default:
                return new Result<>(HttpServletResponse.SC_BAD_REQUEST);
        }
    }

    /** the parent path of 'path' - '/' for a root-level path, matching {@link #targetPath}'s convention. */
    protected @NotNull String parentPath(@NotNull final String path) {
        return StringUtils.defaultIfBlank(StringUtils.substringBeforeLast(path, "/"), "/");
    }

    protected @NotNull String targetPath(@NotNull final SlingHttpServletRequest request) {
        return Optional.ofNullable(request.getRequestPathInfo().getSuffix()).orElse("/");
    }

    protected @NotNull Result<?> errorResult(final int statusCode, @Nullable final String message) {
        return new Result<>(statusCode, Map.of("message", StringUtils.defaultString(message, "Request failed.")));
    }

    public @NotNull Result<?> processGet(@NotNull final SlingHttpServletRequest request,
                                         @NotNull final SlingHttpServletResponse response,
                                         @NotNull List<String> selectors) {
        switch (Manager.consume(selectors, "")) {
            case "resource":
                return resource(request);
            case "dialog":
                return dialog(request, Manager.consume(selectors, ""));
            case "pending":
                return pendingChanges(request);
            case "pathSuggest":
                return pathSuggest(request);
            case "primaryTypeSuggest":
                return primaryTypeSuggest(request);
            case "mixinTypeSuggest":
                return mixinTypeSuggest(request);
            case "tree":
                return treeResult(targetResource(request), targetPath(request));
            default:
                return new Result<>(SC_NOT_FOUND);
        }
    }

    /** the path-picker autocomplete endpoint - see {@link #pathSuggestions} for the shared lookup. */
    protected @NotNull Result<?> pathSuggest(@NotNull final SlingHttpServletRequest request) {
        final String partial = StringUtils.defaultString(request.getParameter("path"));
        return new Result<>(pathSuggestions(resolver(request), partial, 20));
    }

    /**
     * The jcr:primaryType autocomplete endpoint - reuses the generic 'PathPicker' client widget
     * (see {@code changes/dialogs/propertyValue.html}'s value field), so takes the same 'path'
     * request parameter that widget always sends, even though what it holds here is a partial type
     * name, not a path. A separate route from {@link #mixinTypeSuggest} - see
     * {@link PlatformConfig#primaryTypes()} for why the two candidate lists are kept disjoint.
     */
    protected @NotNull Result<?> primaryTypeSuggest(@NotNull final SlingHttpServletRequest request) {
        final String partial = StringUtils.defaultString(request.getParameter("path"));
        return new Result<>(nodeTypeSuggestions(platformConfig.primaryTypes(), partial, 20));
    }

    /** the jcr:mixinTypes counterpart of {@link #primaryTypeSuggest} - see there. */
    protected @NotNull Result<?> mixinTypeSuggest(@NotNull final SlingHttpServletRequest request) {
        final String partial = StringUtils.defaultString(request.getParameter("path"));
        return new Result<>(nodeTypeSuggestions(platformConfig.mixinTypes(), partial, 20));
    }

    public @NotNull Result<?> processPost(@NotNull final SlingHttpServletRequest request,
                                          @NotNull final SlingHttpServletResponse response,
                                          @NotNull List<String> selectors) {
        if (!config.writeEnabled()) {
            return errorResult(SC_FORBIDDEN, "Node-modification actions are disabled.");
        }
        switch (Manager.consume(selectors, "")) {
            case "create":
                return createNode(request);
            case "delete":
                return deleteNode(request);
            case "move":
                return moveNode(request);
            case "copy":
                return copyNode(request);
            case "property":
                return changeProperty(request);
            case "propertyCopy":
                return copyProperties(request);
            case "propertyDelete":
                return deleteProperties(request);
            case "commit":
                return commitChanges(request);
            case "discard":
                return discardChanges(request);
            default:
                return new Result<>(SC_BAD_REQUEST);
        }
    }

    // Dialogs (loaded on demand, see the shared 'CPM.Dialog' client framework); the generic
    // "Confirm" pattern (Delete/Enable/... style yes-no dialogs) lives in
    // '/sling/tools/dialogs/confirm.html', shared with every other plugin - not duplicated here.

    protected @NotNull Result<?> dialog(@NotNull final SlingHttpServletRequest request, @NotNull final String name) {
        switch (name) {
            case "create":
                return renderDialog(DIALOGS_ROOT + "create.html", new Values()
                        .with("dialog.action", actionLink("create") + targetPath(request)));
            case "delete": {
                final Resource resource = targetResource(request);
                if (resource == null) {
                    return new Result<>(SC_NOT_FOUND);
                }
                return renderDialog("/sling/tools/dialogs/confirm.html", new Values()
                        .with("dialog.action", actionLink("delete") + resource.getPath())
                        .with("dialog.title", "Delete Node")
                        .with("dialog.message", "Delete '" + resource.getPath() + "'?"));
            }
            case "move": {
                final Resource resource = targetResource(request);
                if (resource == null) {
                    return new Result<>(SC_NOT_FOUND);
                }
                return renderDialog(DIALOGS_ROOT + "move.html", new Values()
                        .with("dialog.action", actionLink("move") + targetPath(request))
                        .with("dialog.pathSuggest", actionLink("pathSuggest"))
                        .with("dialog.tree", actionLink("tree"))
                        .with("move.path", parentPath(resource.getPath()))
                        .with("move.name", resource.getName())
                        .with("references.available", referencesService != null));
            }
            case "paste": {
                final Resource destination = targetResource(request);
                final String sourcePath = request.getParameter("source");
                final Resource source = destination != null && StringUtils.isNotBlank(sourcePath)
                        ? resolver(request).getResource(sourcePath) : null;
                if (destination == null || source == null) {
                    return new Result<>(SC_NOT_FOUND);
                }
                // pasting into its own current parent would collide under the unchanged name, so
                // suggest an already-distinct one for that specific case - the common way this
                // feature gets used to duplicate a node - while any other destination just keeps
                // the original name, exactly like a plain (non-renaming) paste always did
                final String suggestedName = destination.getPath().equals(parentPath(source.getPath()))
                        ? source.getName() + "-copy" : source.getName();
                return renderDialog(DIALOGS_ROOT + "paste.html", new Values()
                        .with("dialog.action", actionLink("copy") + targetPath(request))
                        .with("paste.source", sourcePath)
                        .with("paste.name", suggestedName));
            }
            case "property": {
                final Resource resource = targetResource(request);
                if (resource == null) {
                    return new Result<>(SC_NOT_FOUND);
                }
                final String propertyName = request.getParameter("name");
                final Object rawValue = StringUtils.isNotBlank(propertyName) ? resource.getValueMap().get(propertyName) : null;
                return renderDialog(DIALOGS_ROOT + "property.html", propertyValues(propertyName, rawValue)
                        .with("dialog.action", actionLink("property") + resource.getPath())
                        .with("dialog.pathSuggest", actionLink("pathSuggest"))
                        .with("dialog.primaryTypeSuggest", actionLink("primaryTypeSuggest"))
                        .with("dialog.mixinTypeSuggest", actionLink("mixinTypeSuggest"))
                        .with("dialog.tree", actionLink("tree")));
            }
            default:
                return new Result<>(SC_NOT_FOUND);
        }
    }

    private @Nullable Resource targetResource(@NotNull final SlingHttpServletRequest request) {
        final Resource resource = resolver(request).getResource(targetPath(request));
        return resource != null && manager.isAllowedResource(resource) ? resource : null;
    }

    /**
     * The property-edit dialog's pre-fill values, derived from the property's current raw
     * {@link org.apache.sling.api.resource.ValueMap} value (or defaults, for "Add Property") -
     * {@code property.values} is one entry per value (rendered as one input row each, see
     * {@code changes/dialogs/propertyValue.html}), deliberately not a single newline-joined string:
     * a multi-value property can have individual values that themselves contain newlines, which a
     * shared, newline-split textarea cannot represent unambiguously.
     */
    protected @NotNull Values propertyValues(@Nullable final String name, @Nullable final Object rawValue) {
        String type = "String";
        boolean multi = false;
        List<String> values = new ArrayList<>(List.of(""));
        if (rawValue != null) {
            final Object sample;
            if (rawValue instanceof Object[]) {
                multi = true;
                final Object[] array = (Object[]) rawValue;
                values = new ArrayList<>(array.length);
                for (final Object item : array) {
                    values.add(editableString(item));
                }
                if (values.isEmpty()) {
                    values.add("");
                }
                sample = array.length > 0 ? array[0] : null;
            } else {
                values = new ArrayList<>(List.of(editableString(rawValue)));
                sample = rawValue;
            }
            type = ChangeOperations.typeNameOf(sample);
        }
        return new Values()
                .with("property.name", name)
                .with("property.type", type)
                .with("property.multi", multi)
                .with("property.values", values);
    }

    private static @NotNull String editableString(@Nullable final Object value) {
        if (value instanceof Calendar) {
            return new SimpleDateFormat(ChangeOperations.DATE_FORMAT).format(((Calendar) value).getTime());
        }
        return value != null ? value.toString() : "";
    }

    protected @NotNull Result<?> renderDialog(@NotNull final String templatePath, @NotNull final Values values) {
        final Reader content = templateReader(getTemplate(new TemplateContext(new Values().with(values)), templatePath));
        return content != null ? new Result<>(content, HTML_TYPE) : new Result<>(SC_NOT_FOUND);
    }

    /**
     * The pending-changes panel: the current {@link ChangeSession}'s log (empty if no session
     * exists yet) plus Save All / Revert All buttons.
     */
    protected @NotNull Result<?> pendingChanges(@NotNull final SlingHttpServletRequest request) {
        final ChangeSession session = ChangeSession.get(request, false);
        final Reader content = templateReader(getTemplate(new TemplateContext(new Values()
                .with("pending.changes", session != null ? session.pendingChanges() : List.of())
                .with("pending.commit", actionLink("commit"))
                .with("pending.discard", actionLink("discard"))
        ), DIALOGS_ROOT + "pending.html"));
        return content != null ? new Result<>(content, HTML_TYPE) : new Result<>(SC_NOT_FOUND);
    }

    // Node-modification actions - resolver-API only (create/delete/move/copy/ModifiableValueMap),
    // see ChangeOperations; every mutation is staged in the current request's ChangeSession, never
    // committed here - only the explicit 'commit'/'discard' actions end the editing session.

    protected @NotNull Result<?> createNode(@NotNull final SlingHttpServletRequest request) {
        final String name = request.getParameter("name");
        final String type = StringUtils.defaultIfBlank(request.getParameter("type"), "nt:unstructured");
        if (StringUtils.isBlank(name)) {
            return errorResult(SC_BAD_REQUEST, "Name is required.");
        }
        final ChangeSession session = ChangeSession.get(request, true);
        final Resource parent = session.resolver().getResource(targetPath(request));
        if (parent == null || !manager.isAllowedResource(parent)) {
            return new Result<>(SC_NOT_FOUND);
        }
        try {
            final Resource created = ChangeOperations.create(session.resolver(), parent, name, type);
            session.log("Created", created.getPath(), null);
            return new Result<>(Map.of("path", created.getPath(), "pending", session.pendingChanges().size()));
        } catch (PersistenceException ex) {
            return errorResult(SC_NOT_ACCEPTABLE, ex.getMessage());
        } catch (IllegalArgumentException ex) {
            // e.g. 'resolver.create' rejects a name containing '/' - a plain 400, not a 500
            return errorResult(SC_BAD_REQUEST, ex.getMessage());
        }
    }

    protected @NotNull Result<?> deleteNode(@NotNull final SlingHttpServletRequest request) {
        final ChangeSession session = ChangeSession.get(request, true);
        final Resource resource = session.resolver().getResource(targetPath(request));
        if (resource == null || !manager.isAllowedResource(resource)) {
            return new Result<>(SC_NOT_FOUND);
        }
        try {
            final String path = resource.getPath();
            // unlike a package's synthetic tree path, a resource's parent is a real, persistent
            // node that is never itself removed by deleting a child - always safe to navigate to
            final String parent = parentPath(path);
            ChangeOperations.delete(session.resolver(), resource);
            session.log("Deleted", path, null);
            return new Result<>(Map.of("path", parent, "deleted", path, "pending", session.pendingChanges().size()));
        } catch (PersistenceException ex) {
            return errorResult(SC_NOT_ACCEPTABLE, ex.getMessage());
        }
    }

    /**
     * Moves the request's suffix path (the node to move) to become a child of the 'path' request
     * parameter (the destination parent) - 'name', if given and different from the node's current
     * name, renames it too (see {@link ChangeOperations#move} for how, since a plain resolver
     * move/copy can never do this in one step); a move to the node's own current parent, with only
     * 'name' changed, is a rename-in-place. If 'adjustReferences' is 'true' (only honored while a
     * {@link ReferencesService} is bound), every resource under 'referencesRoot' (blank meaning the
     * whole repository) referencing the node's *old* path is found *before* the move and then
     * updated to the new one right after - each such adjustment gets its own pending-changes log
     * entry, so it shows (and can be individually reviewed/reverted via Revert All) exactly like
     * any other staged change.
     */
    protected @NotNull Result<?> moveNode(@NotNull final SlingHttpServletRequest request) {
        final String destParentPath = request.getParameter("path");
        if (StringUtils.isBlank(destParentPath)) {
            return errorResult(SC_BAD_REQUEST, "Destination path is required.");
        }
        final String newName = request.getParameter("name");
        final ChangeSession session = ChangeSession.get(request, true);
        final Resource resource = session.resolver().getResource(targetPath(request));
        final Resource destParent = session.resolver().getResource(destParentPath);
        if (resource == null || !manager.isAllowedResource(resource)
                || destParent == null || !manager.isAllowedResource(destParent)) {
            return new Result<>(SC_NOT_FOUND);
        }
        // gathered before the move itself, against the still-current source path
        final boolean adjustReferences = referencesService != null
                && "true".equalsIgnoreCase(request.getParameter("adjustReferences"));
        final String sourcePath = resource.getPath();
        final List<ReferencesService.Hit> references = adjustReferences
                ? referencesService.findReferences(session.resolver(),
                StringUtils.defaultString(request.getParameter("referencesRoot")), sourcePath)
                : List.of();
        try {
            final Resource moved = ChangeOperations.move(session.resolver(), resource, destParentPath, newName);
            session.log("Moved", sourcePath, "→ " + moved.getPath());
            for (final ReferencesService.Hit hit : references) {
                referencesService.updateReferences(hit, sourcePath, moved.getPath());
                session.log("Reference", hit.resource().getPath(),
                        "adjusted reference(s) " + String.join(", ", hit.propertyNames()));
            }
            return new Result<>(Map.of("path", moved.getPath(), "pending", session.pendingChanges().size()));
        } catch (PersistenceException ex) {
            return errorResult(SC_NOT_ACCEPTABLE, ex.getMessage());
        } catch (IllegalArgumentException ex) {
            return errorResult(SC_BAD_REQUEST, ex.getMessage());
        }
    }

    /**
     * Pastes the 'source' request parameter (the node last copied - a plain path remembered
     * client-side by the consuming plugin) as a child of the request's suffix path (the currently
     * selected destination) - 'name', if given and different from the source's own name, pastes it
     * under that name instead (see {@link ChangeOperations#copy}); this is what makes duplicating a
     * node via Copy/Paste into its own parent possible at all, since a same-name copy into the same
     * parent is just a name collision. "Copy" itself never reaches the server at all.
     */
    protected @NotNull Result<?> copyNode(@NotNull final SlingHttpServletRequest request) {
        final String sourcePath = request.getParameter("source");
        if (StringUtils.isBlank(sourcePath)) {
            return errorResult(SC_BAD_REQUEST, "Nothing has been copied yet.");
        }
        final String newName = request.getParameter("name");
        final ChangeSession session = ChangeSession.get(request, true);
        final Resource source = session.resolver().getResource(sourcePath);
        final String destParentPath = targetPath(request);
        final Resource destParent = session.resolver().getResource(destParentPath);
        if (source == null || !manager.isAllowedResource(source)
                || destParent == null || !manager.isAllowedResource(destParent)) {
            return new Result<>(SC_NOT_FOUND);
        }
        try {
            final Resource copied = ChangeOperations.copy(session.resolver(), source, destParentPath, newName);
            session.log("Copied", sourcePath, "→ " + copied.getPath());
            return new Result<>(Map.of("path", copied.getPath(), "pending", session.pendingChanges().size()));
        } catch (PersistenceException ex) {
            return errorResult(SC_NOT_ACCEPTABLE, ex.getMessage());
        } catch (IllegalArgumentException ex) {
            return errorResult(SC_BAD_REQUEST, ex.getMessage());
        }
    }

    /**
     * Sets, renames, or removes one property of the request's suffix path resource - 'name'
     * (required unless 'remove' with an 'oldName') the property name, 'type' one of
     * String/Long/Double/Boolean/Date (default 'String'), 'multi' whether it is multi-valued (one
     * 'value' field per value - see {@link #propertyValues}), 'oldName' the property's previous
     * name (renames it if it differs from 'name'; also what 'remove' deletes if present), 'remove'
     * ('true' to delete instead of set).
     */
    protected @NotNull Result<?> changeProperty(@NotNull final SlingHttpServletRequest request) {
        final String name = request.getParameter("name");
        final String oldName = request.getParameter("oldName");
        final boolean remove = "true".equalsIgnoreCase(request.getParameter("remove"));
        final String target = remove ? StringUtils.defaultIfBlank(oldName, name) : name;
        if (StringUtils.isBlank(target)) {
            return errorResult(SC_BAD_REQUEST, "Name is required.");
        }
        final ChangeSession session = ChangeSession.get(request, true);
        final Resource resource = session.resolver().getResource(targetPath(request));
        if (resource == null || !manager.isAllowedResource(resource)) {
            return new Result<>(SC_NOT_FOUND);
        }
        if (!manager.isAllowedProperty(target)) {
            return errorResult(SC_FORBIDDEN, "'" + target + "' cannot be changed.");
        }
        try {
            if (remove) {
                if (ChangeOperations.removeProperty(resource, target)) {
                    session.log("Property", resource.getPath(), "removed " + target);
                }
            } else {
                final String type = StringUtils.defaultIfBlank(request.getParameter("type"), "String");
                final boolean multi = "true".equalsIgnoreCase(request.getParameter("multi"));
                // one 'value' form field per row (see changes/dialogs/propertyValue.html) - the
                // browser submits each row under the same field name, so 'getParameterValues'
                // already gives one raw string per row, each preserving its own internal newlines
                final String[] submittedValues = Optional.ofNullable(request.getParameterValues("value"))
                        .orElse(new String[0]);
                boolean renamed = false;
                if (StringUtils.isNotBlank(oldName) && !oldName.equals(name)) {
                    if (!manager.isAllowedProperty(oldName)) {
                        return errorResult(SC_FORBIDDEN, "'" + oldName + "' cannot be changed.");
                    }
                    renamed = ChangeOperations.removeProperty(resource, oldName);
                }
                // a re-submitted, unedited dialog would otherwise write nothing (setProperty is
                // itself a no-op for an unchanged value) but still clutter Pending Changes with an
                // identical "name = value" entry every time - so only log when something actually
                // changed, i.e. the value itself changed, or a rename genuinely happened
                final boolean changed = ChangeOperations.setProperty(resource, name, type, multi, submittedValues);
                if (changed || renamed) {
                    session.log("Property", resource.getPath(), name + " = " + (multi
                            ? "[" + String.join(", ", submittedValues) + "]"
                            : "'" + submittedValues[0] + "'"));
                }
            }
            return new Result<>(Map.of("path", resource.getPath(), "pending", session.pendingChanges().size()));
        } catch (PersistenceException ex) {
            return errorResult(SC_NOT_ACCEPTABLE, ex.getMessage());
        }
    }

    /**
     * "Paste" for one or more previously "Copied" properties (a plain client-side clipboard, see
     * {@code PropertiesToolbar} in browser/script.js - the same pattern as node-level Copy/Paste,
     * see {@link #copyNode}): copies each of 'names' from the 'source' resource to the request's
     * suffix path resource, preserving its original type/value as-is (see
     * {@link ChangeOperations#copyProperty}).
     */
    protected @NotNull Result<?> copyProperties(@NotNull final SlingHttpServletRequest request) {
        final String sourcePath = request.getParameter("source");
        final String[] names = Optional.ofNullable(request.getParameterValues("names")).orElse(new String[0]);
        if (StringUtils.isBlank(sourcePath) || names.length == 0) {
            return errorResult(SC_BAD_REQUEST, "Nothing has been copied yet.");
        }
        final ChangeSession session = ChangeSession.get(request, true);
        final Resource target = session.resolver().getResource(targetPath(request));
        final Resource source = session.resolver().getResource(sourcePath);
        if (target == null || !manager.isAllowedResource(target)
                || source == null || !manager.isAllowedResource(source)) {
            return new Result<>(SC_NOT_FOUND);
        }
        for (final String name : names) {
            if (!manager.isAllowedProperty(name)) {
                return errorResult(SC_FORBIDDEN, "'" + name + "' cannot be changed.");
            }
        }
        try {
            for (final String name : names) {
                ChangeOperations.copyProperty(source, target, name);
            }
            session.log("Property", target.getPath(), "copied " + names.length
                    + (names.length == 1 ? " property" : " properties") + " ← " + source.getPath());
            return new Result<>(Map.of("path", target.getPath(), "pending", session.pendingChanges().size()));
        } catch (PersistenceException ex) {
            return errorResult(SC_NOT_ACCEPTABLE, ex.getMessage());
        }
    }

    /**
     * Bulk "Delete" for one or more selected properties of the request's suffix path resource
     * (see {@code PropertiesToolbar} in browser/script.js).
     */
    protected @NotNull Result<?> deleteProperties(@NotNull final SlingHttpServletRequest request) {
        final String[] names = Optional.ofNullable(request.getParameterValues("names")).orElse(new String[0]);
        if (names.length == 0) {
            return errorResult(SC_BAD_REQUEST, "No properties selected.");
        }
        final ChangeSession session = ChangeSession.get(request, true);
        final Resource resource = session.resolver().getResource(targetPath(request));
        if (resource == null || !manager.isAllowedResource(resource)) {
            return new Result<>(SC_NOT_FOUND);
        }
        for (final String name : names) {
            if (!manager.isAllowedProperty(name)) {
                return errorResult(SC_FORBIDDEN, "'" + name + "' cannot be changed.");
            }
        }
        try {
            for (final String name : names) {
                ChangeOperations.removeProperty(resource, name);
            }
            session.log("Property", resource.getPath(), "removed"
                    + (names.length == 1 ? " property " + names[0] : " " + names.length + " properties"));
            return new Result<>(Map.of("path", resource.getPath(), "pending", session.pendingChanges().size()));
        } catch (PersistenceException ex) {
            return errorResult(SC_NOT_ACCEPTABLE, ex.getMessage());
        }
    }

    protected @NotNull Result<?> commitChanges(@NotNull final SlingHttpServletRequest request) {
        try {
            ChangeSession.commit(request);
            return new Result<>(Map.of("pending", 0));
        } catch (PersistenceException ex) {
            return errorResult(SC_NOT_ACCEPTABLE, ex.getMessage());
        }
    }

    protected @NotNull Result<?> discardChanges(@NotNull final SlingHttpServletRequest request) {
        ChangeSession.discard(request);
        return new Result<>(Map.of("pending", 0));
    }

    @Override
    public @Nullable Template getTemplate(@NotNull TemplateContext context, @NotNull String key) {
        return key.startsWith("/") ? new Template(key, context, this) : null;
    }
}
