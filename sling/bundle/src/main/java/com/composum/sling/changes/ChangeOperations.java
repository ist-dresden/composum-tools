package com.composum.sling.changes;

import com.composum.sling.tools.Common;
import org.apache.commons.lang3.StringUtils;
import org.apache.sling.api.resource.ModifiableValueMap;
import org.apache.sling.api.resource.PersistenceException;
import org.apache.sling.api.resource.Resource;
import org.apache.sling.api.resource.ResourceResolver;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.lang.reflect.Array;
import java.text.ParsePosition;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Calendar;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * The actual node/property mutations behind {@link Changes}' Create/Delete/Move/Copy/
 * Change-Property actions - a stateless utility, package-private (only {@link Changes} calls
 * this directly). Deliberately resolver-API-only, never touching {@code javax.jcr.*} - see
 * {@link ChangeSession}'s class Javadoc for why every call here is expected to run against a
 * {@code ChangeSession}'s long-lived resolver rather than a per-request one, and to stay
 * uncommitted until an explicit Save All.
 */
final class ChangeOperations {

    private ChangeOperations() {
    }

    /**
     * Well-known properties a repository itself manages (primary type, identity, versioning,
     * locking, ...) - deliberately still editable through {@link #setProperty}/{@link
     * #removeProperty} (some repositories do allow it, and forbidding it here would just be a
     * guess), this is purely a warning flag for the property-edit UI to visualize, mirroring the
     * legacy Composum Nodes browser this feature was migrated from (JCR
     * {@code PropertyDefinition#isProtected()} there, a static list here since resolver-only code
     * has no equivalent live, repository-specific API). If a given repository genuinely rejects a
     * change to one of these, that surfaces as a normal {@link PersistenceException} from the
     * resolver/JCR layer itself once committed.
     */
    static final Set<String> PROTECTED_PROPERTIES = Set.of(
            "jcr:primaryType", "jcr:mixinTypes", "jcr:uuid",
            "jcr:created", "jcr:createdBy",
            "jcr:isCheckedOut", "jcr:baseVersion", "jcr:predecessors", "jcr:versionHistory",
            "jcr:lockOwner", "jcr:lockIsDeep"
    );

    /**
     * Repository-assigned identity/audit metadata {@link #copyRenamed} must strip before handing a
     * source node's properties to {@link ResourceResolver#create} for a new node - a duplicate (or
     * a renamed node, which {@link #copyRenamed} rebuilds as one) needs its own uuid/created/etc.,
     * never the source's. Unlike {@link #PROTECTED_PROPERTIES}, 'jcr:primaryType'/'jcr:mixinTypes'
     * are deliberately not included: those describe what kind of node this is - exactly the point
     * of duplicating one - and {@code create} already accepts them as ordinary initial properties
     * (see {@link #create}).
     */
    private static final Set<String> IDENTITY_PROPERTIES = Set.of(
            "jcr:uuid", "jcr:created", "jcr:createdBy",
            "jcr:isCheckedOut", "jcr:baseVersion", "jcr:predecessors", "jcr:versionHistory",
            "jcr:lockOwner", "jcr:lockIsDeep"
    );

    /**
     * This feature's own 'Date' output format (used to pre-fill the edit dialog) - the same
     * {@link Common#PROPERTY_DATE_FORMAT} the Browser's Properties view uses to *display* a Date
     * value, so the same value always reads identically whether you are just looking at it or
     * editing it. Also the first (and therefore preferred) entry of {@link #INPUT_DATE_FORMATS}, so
     * a pre-filled value always round-trips unchanged.
     */
    static final String DATE_FORMAT = Common.PROPERTY_DATE_FORMAT;

    /**
     * Every pattern accepted when parsing a 'Date' property value, tried in this order - a few
     * common, more permissive variants a user might type by hand in addition to
     * {@link #DATE_FORMAT} itself; a pattern missing a time component (seconds, or the whole
     * time-of-day) leaves it at its default of zero (a plain {@link SimpleDateFormat} behavior:
     * only the fields present in the pattern are set).
     */
    private static final String[] INPUT_DATE_FORMATS = {
            DATE_FORMAT,
            "yyyy-MM-dd HH:mm:ss",
            "yyyy-MM-dd HH:mm",
            "yyyy-MM-dd"
    };

    static @NotNull Resource create(@NotNull final ResourceResolver resolver, @NotNull final Resource parent,
                                    @NotNull final String name, @NotNull final String primaryType)
            throws PersistenceException {
        return resolver.create(parent, name, Map.of("jcr:primaryType", primaryType));
    }

    static void delete(@NotNull final ResourceResolver resolver, @NotNull final Resource resource)
            throws PersistenceException {
        if ("/".equals(resource.getPath())) {
            throw new PersistenceException("The repository root cannot be deleted.");
        }
        resolver.delete(resource);
    }

    /**
     * Moves the given resource to become a child of 'destParentPath' - if 'newName' is blank or
     * unchanged, this is a plain {@link ResourceResolver#move} (its 'destAbsPath' names the
     * destination *parent*, not the new full path, and it always keeps the source's name); a
     * genuinely different 'newName' instead rebuilds the resource under that name at the
     * destination (see {@link #copyRenamed} for why - there is no resolver-API rename primitive)
     * and deletes the original, covering the common "move to the same parent" rename-in-place case.
     * A destination that is neither a different parent nor a different name is rejected up front
     * with a message naming the actual problem, rather than letting it reach {@link
     * ResourceResolver#move} - which would still correctly refuse it (the resource's own current
     * self already occupies that exact destination), but only with a generic, resolver-internal
     * "unable to move to &lt;parent&gt;" message that doesn't explain why or even show the name
     * that's colliding.
     */
    static @NotNull Resource move(@NotNull final ResourceResolver resolver, @NotNull final Resource resource,
                                  @NotNull final String destParentPath, @Nullable final String newName)
            throws PersistenceException {
        final boolean renaming = StringUtils.isNotBlank(newName) && !newName.equals(resource.getName());
        if (!renaming) {
            final Resource currentParent = resource.getParent();
            if (currentParent != null && currentParent.getPath().equals(destParentPath)) {
                throw new PersistenceException("'" + resource.getName() + "' is already in '"
                        + destParentPath + "' - nothing to move.");
            }
            return resolver.move(resource.getPath(), destParentPath);
        }
        final Resource created = copyRenamed(resolver, resource, requireResource(resolver, destParentPath), newName);
        resolver.delete(resource);
        return created;
    }

    /**
     * Copies the given resource to become a child of 'destParentPath' - same "blank/unchanged
     * 'newName' means a plain {@link ResourceResolver#copy}, otherwise a rebuild under the new
     * name" semantics as {@link #move}, see there - the rebuild path is what makes "Paste" able to
     * duplicate a node into its own parent at all (a same-name copy into the same parent is simply
     * a name collision {@link ResourceResolver#copy} itself cannot resolve).
     */
    static @NotNull Resource copy(@NotNull final ResourceResolver resolver, @NotNull final Resource resource,
                                  @NotNull final String destParentPath, @Nullable final String newName)
            throws PersistenceException {
        if (StringUtils.isBlank(newName) || newName.equals(resource.getName())) {
            return resolver.copy(resource.getPath(), destParentPath);
        }
        return copyRenamed(resolver, resource, requireResource(resolver, destParentPath), newName);
    }

    private static @NotNull Resource requireResource(@NotNull final ResourceResolver resolver, @NotNull final String path)
            throws PersistenceException {
        final Resource resource = resolver.getResource(path);
        if (resource == null) {
            throw new PersistenceException("'" + path + "' does not exist.");
        }
        return resource;
    }

    /**
     * Deep-copies 'source' - its own properties, then recursively every descendant under its
     * original name - to become a new child named 'newName' of 'destParent'. The only way to
     * rename via the resolver API alone: unlike raw JCR's own {@code Session#move} (which takes an
     * arbitrary full destination path, rename included), {@link ResourceResolver#move}/{@link
     * ResourceResolver#copy} always keep the source's name, deliberately not worked around by
     * dropping to JCR API here (see the class Javadoc).
     */
    private static @NotNull Resource copyRenamed(@NotNull final ResourceResolver resolver, @NotNull final Resource source,
                                                  @NotNull final Resource destParent, @NotNull final String newName)
            throws PersistenceException {
        final Map<String, Object> properties = new HashMap<>(source.getValueMap());
        IDENTITY_PROPERTIES.forEach(properties::remove);
        final Resource created = resolver.create(destParent, newName, properties);
        for (final Resource child : source.getChildren()) {
            copyRenamed(resolver, child, created, child.getName());
        }
        return created;
    }

    /**
     * Sets 'name' to the parsed/coerced form of 'rawValues' - a no-op (the {@link ModifiableValueMap}
     * is left untouched) if the resource already carries exactly that value, so re-submitting an
     * unedited property dialog does not manufacture a spurious pending-change entry. Returns
     * whether anything was actually written.
     */
    static boolean setProperty(@NotNull final Resource resource, @NotNull final String name,
                               @NotNull final String type, final boolean multi,
                               @NotNull final String[] rawValues) throws PersistenceException {
        final ModifiableValueMap values = modifiableValueMap(resource);
        final Object next;
        try {
            next = coerce(type, multi, rawValues);
        } catch (RuntimeException ex) {
            // NumberFormatException / IllegalArgumentException (see #parseDate) and the like -
            // the legacy browser lets these propagate uncaught for the numeric types; wrapping
            // them here at least gives the client a proper error message instead of a generic 500
            final String badValue = rawValues.length > 0 ? rawValues[0] : "";
            throw new PersistenceException("'" + badValue + "' is not a valid " + type + " value.", ex);
        }
        final Object existing = resource.getValueMap().get(name);
        final boolean sameShape = sameShape(existing, multi, type);
        if (sameShape && sameValue(existing, next)) {
            return false;
        }
        try {
            if (existing != null && !sameShape) {
                // a plain put() overwrites an existing property's VALUE but keeps its established
                // cardinality/type - the repository (e.g. Jackrabbit) then rejects trying to store a
                // shape-incompatible value (single <-> multi, or a different type) into it; removing
                // it first makes the following put() create it fresh under the new shape instead
                values.remove(name);
            }
            values.put(name, next);
        } catch (RuntimeException ex) {
            // a repository-level rejection (e.g. a node type constraint on 'name') surfaces here as
            // an unchecked exception from the resolver - previously this propagated uncaught past
            // this method entirely, past Changes#changeProperty's own PersistenceException-only
            // catch, past the servlet dispatch (which has no catch-all of its own either) and out to
            // the container's generic error handling, which never reaches the client as a readable
            // message; converting it here gives the client an actual error to display instead of
            // the dialog silently doing nothing
            throw new PersistenceException("'" + name + "' could not be changed: " + ex.getMessage(), ex);
        }
        return true;
    }

    /**
     * Removes 'name' - a no-op if it doesn't exist. Returns whether a property was actually
     * removed, so a caller can skip logging a pending-change entry for an already-absent property.
     */
    static boolean removeProperty(@NotNull final Resource resource, @NotNull final String name)
            throws PersistenceException {
        try {
            return modifiableValueMap(resource).remove(name) != null;
        } catch (RuntimeException ex) {
            throw new PersistenceException("'" + name + "' could not be removed: " + ex.getMessage(), ex);
        }
    }

    /**
     * Whether 'existing' (a raw {@link org.apache.sling.api.resource.ValueMap} value, possibly
     * {@code null} if the property doesn't exist yet) already has the cardinality ('multi') and
     * type a save with these parameters would give it - checked explicitly against 'existing'
     * itself, not only inferred from comparing it to the freshly {@link #coerce}d value, so a
     * Multi/Single toggle or a Type change is always recognized, both as a real change for
     * {@link #setProperty}'s no-op check and as the trigger for removing 'existing' first (see
     * there) before writing a shape-incompatible replacement.
     */
    private static boolean sameShape(@Nullable final Object existing, final boolean multi,
                                     @NotNull final String type) {
        if (existing == null) {
            return false;
        }
        final boolean existingMulti = existing.getClass().isArray();
        if (existingMulti != multi) {
            return false;
        }
        final Object existingSample = existingMulti
                ? (Array.getLength(existing) > 0 ? Array.get(existing, 0) : null) : existing;
        return existingSample == null || type.equals(typeNameOf(existingSample));
    }

    /**
     * Whether 'existing' (a raw {@link org.apache.sling.api.resource.ValueMap} value) already
     * represents the same value as 'next' (a freshly {@link #coerce}d one), assuming both are
     * already known to agree on cardinality/type (see {@link #sameShape}). Arrays are compared
     * element-wise (a plain {@link Object#equals} on two arrays is identity-based and would never
     * match); {@link Calendar} values are compared by instant ({@link Calendar#getTimeInMillis()}),
     * since a freshly parsed one and a repository-stored one can differ in timezone/locale while
     * still denoting the same point in time.
     */
    private static boolean sameValue(@NotNull final Object existing, @NotNull final Object next) {
        if (!existing.getClass().isArray()) {
            return sameScalar(existing, next);
        }
        final int length = Array.getLength(existing);
        if (length != Array.getLength(next)) {
            return false;
        }
        for (int i = 0; i < length; i++) {
            if (!sameScalar(Array.get(existing, i), Array.get(next, i))) {
                return false;
            }
        }
        return true;
    }

    private static boolean sameScalar(@Nullable final Object existing, @Nullable final Object next) {
        if (existing instanceof Calendar && next instanceof Calendar) {
            return ((Calendar) existing).getTimeInMillis() == ((Calendar) next).getTimeInMillis();
        }
        return Objects.equals(existing, next);
    }

    /**
     * The Type-select name (one of the five this feature supports) that best describes a raw
     * {@link org.apache.sling.api.resource.ValueMap} value - used both to pre-fill the property
     * dialog's Type field ({@link Changes#propertyValues}) and, here, to detect a Type change as
     * part of {@link #unchanged}. A value of some other, unsupported JCR type (e.g. a Decimal or a
     * reference) falls back to "String", matching how this feature already treats any value it
     * doesn't have a dedicated type for.
     */
    static @NotNull String typeNameOf(@Nullable final Object value) {
        if (value instanceof Long || value instanceof Integer) {
            return "Long";
        } else if (value instanceof Double || value instanceof Float) {
            return "Double";
        } else if (value instanceof Boolean) {
            return "Boolean";
        } else if (value instanceof Calendar) {
            return "Date";
        }
        return "String";
    }

    /**
     * Copies one property's current raw value as-is from 'source' to 'target' - unlike
     * {@link #setProperty}, this takes the value directly from the source's
     * {@link org.apache.sling.api.resource.ValueMap} (single or multi, whatever type it already
     * has) rather than parsing it from form input, so a property copy never loses type or
     * precision. A no-op if 'source' has no such property.
     */
    static void copyProperty(@NotNull final Resource source, @NotNull final Resource target,
                             @NotNull final String name) throws PersistenceException {
        final Object value = source.getValueMap().get(name);
        if (value != null) {
            modifiableValueMap(target).put(name, value);
        }
    }

    private static @NotNull ModifiableValueMap modifiableValueMap(@NotNull final Resource resource)
            throws PersistenceException {
        final ModifiableValueMap values = resource.adaptTo(ModifiableValueMap.class);
        if (values == null) {
            throw new PersistenceException("Properties of '" + resource.getPath() + "' cannot be modified.");
        }
        return values;
    }

    private static @NotNull Object coerce(@NotNull final String type, final boolean multi,
                                          @NotNull final String[] rawValues) {
        if (multi) {
            switch (type) {
                case "Long":
                    return rawValues.length == 0 ? new Long[0]
                            : Arrays.stream(rawValues).map(Long::parseLong).toArray(Long[]::new);
                case "Double":
                    return rawValues.length == 0 ? new Double[0]
                            : Arrays.stream(rawValues).map(Double::parseDouble).toArray(Double[]::new);
                case "Boolean":
                    return rawValues.length == 0 ? new Boolean[0]
                            : Arrays.stream(rawValues).map(Boolean::parseBoolean).toArray(Boolean[]::new);
                case "Date": {
                    final List<Calendar> dates = new ArrayList<>();
                    for (final String raw : rawValues) {
                        dates.add(parseDate(raw));
                    }
                    return dates.toArray(new Calendar[0]);
                }
                default:
                    return rawValues;
            }
        }
        final String raw = rawValues.length > 0 ? rawValues[0] : "";
        switch (type) {
            case "Long":
                return Long.parseLong(raw.trim());
            case "Double":
                return Double.parseDouble(raw.trim());
            case "Boolean":
                return Boolean.parseBoolean(raw.trim());
            case "Date":
                return parseDate(raw.trim());
            default:
                return raw;
        }
    }

    /**
     * Parses one of {@link #INPUT_DATE_FORMATS}, in order, requiring a full match (not just a
     * parseable prefix - {@link SimpleDateFormat#parse(String, ParsePosition)} alone would happily
     * accept "2024-01-01 garbage" against the date-only pattern).
     */
    private static @NotNull Calendar parseDate(@NotNull final String raw) {
        final String trimmed = raw.trim();
        for (final String pattern : INPUT_DATE_FORMATS) {
            final SimpleDateFormat format = new SimpleDateFormat(pattern);
            format.setLenient(false);
            final ParsePosition position = new ParsePosition(0);
            final Date parsed = format.parse(trimmed, position);
            if (parsed != null && position.getIndex() == trimmed.length()) {
                final Calendar calendar = Calendar.getInstance();
                calendar.setTime(parsed);
                return calendar;
            }
        }
        throw new IllegalArgumentException("'" + raw + "' is not a valid date (expected one of: "
                + String.join(", ", INPUT_DATE_FORMATS) + ")");
    }
}
