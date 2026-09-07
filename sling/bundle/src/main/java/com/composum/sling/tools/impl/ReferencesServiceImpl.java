package com.composum.sling.tools.impl;

import com.composum.sling.tools.Manager;
import com.composum.sling.tools.RelatedPath;
import com.composum.sling.tools.ReferencesService;
import org.apache.commons.lang3.StringUtils;
import org.apache.sling.api.resource.ModifiableValueMap;
import org.apache.sling.api.resource.PersistenceException;
import org.apache.sling.api.resource.Resource;
import org.apache.sling.api.resource.ResourceResolver;
import org.jetbrains.annotations.NotNull;
import org.osgi.service.component.annotations.Component;
import org.osgi.service.component.annotations.Reference;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Default {@link ReferencesService} implementation - see that interface's Javadoc for the overall
 * contract. The reference search is modeled after (a much reduced variant of) Composum Nodes'
 * {@code PathReferencesService}: a resolver-API {@code jcr:contains} full-text query (never raw
 * JCR API) narrows the candidates down to whatever the search index can find quickly, then each
 * candidate's own {@link org.apache.sling.api.resource.ValueMap} is inspected directly to confirm
 * (and locate) the actual match - the index is a fast pre-filter, never the source of truth.
 */
@Component(service = ReferencesService.class, immediate = true)
public class ReferencesServiceImpl implements ReferencesService {

    @Reference
    protected Manager manager;

    @Override
    public @NotNull List<RelatedPath> getSupertypeChain(@NotNull final Resource resource) {
        return new RelatedPathsCalculation(manager, resource).getSupertypeChain();
    }

    @Override
    public @NotNull List<RelatedPath> getRelatedPathSet(@NotNull final Resource resource) {
        return new RelatedPathsCalculation(manager, resource).getRelatedPathSet();
    }

    @Override
    public @NotNull List<Hit> findReferences(@NotNull final ResourceResolver resolver,
                                             @NotNull final String searchRoot, @NotNull final String path) {
        String root = StringUtils.defaultIfBlank(searchRoot, "/");
        while (root.length() > 1 && root.endsWith("/")) {
            root = root.substring(0, root.length() - 1);
        }
        final String queryString = "/jcr:root" + ("/".equals(root) ? "" : root)
                + "//*[jcr:contains(.,'" + escapeXPathLiteral(path) + "')]";
        final List<Hit> result = new ArrayList<>();
        try {
            final Iterator<Resource> candidates = resolver.findResources(queryString, "xpath");
            while (candidates.hasNext()) {
                final Resource candidate = candidates.next();
                final Set<String> matchingProperties = matchingPropertyNames(candidate, path);
                if (!matchingProperties.isEmpty()) {
                    result.add(new HitImpl(candidate, matchingProperties));
                }
            }
        } catch (RuntimeException ex) {
            // a malformed query or an unavailable search index - fail soft, this is an optional
            // convenience feature, not something that should block whatever the caller is doing
        }
        return result;
    }

    private static @NotNull Set<String> matchingPropertyNames(@NotNull final Resource resource, @NotNull final String path) {
        final Set<String> names = new LinkedHashSet<>();
        for (final Map.Entry<String, Object> entry : resource.getValueMap().entrySet()) {
            final Object value = entry.getValue();
            if (value instanceof String) {
                if (matches((String) value, path)) {
                    names.add(entry.getKey());
                }
            } else if (value instanceof String[]) {
                for (final String item : (String[]) value) {
                    if (matches(item, path)) {
                        names.add(entry.getKey());
                        break;
                    }
                }
            }
        }
        return names;
    }

    private static boolean matches(@NotNull final String value, @NotNull final String path) {
        return value.equals(path) || value.startsWith(path + "/") || richTextPattern(path).matcher(value).find();
    }

    @Override
    public void updateReferences(@NotNull final Hit hit, @NotNull final String oldPath,
                                 @NotNull final String newPath) throws PersistenceException {
        final ModifiableValueMap values = hit.resource().adaptTo(ModifiableValueMap.class);
        if (values == null) {
            throw new PersistenceException("Properties of '" + hit.resource().getPath() + "' cannot be modified.");
        }
        for (final String name : hit.propertyNames()) {
            final Object current = values.get(name);
            if (current instanceof String) {
                values.put(name, rewrite((String) current, oldPath, newPath));
            } else if (current instanceof String[]) {
                final String[] array = (String[]) current;
                final String[] updated = new String[array.length];
                for (int i = 0; i < array.length; i++) {
                    updated[i] = rewrite(array[i], oldPath, newPath);
                }
                values.put(name, updated);
            }
        }
    }

    /**
     * Replaces every occurrence of 'oldPath' (or a descendant of it) in 'value' with the
     * corresponding path under 'newPath' - a plain-valued match (the whole value is exactly
     * 'oldPath', or 'oldPath/...') outright, a rich-text-embedded match (an {@code ="oldPath..."}
     * pattern) leaving everything else in the string untouched.
     */
    private static @NotNull String rewrite(@NotNull final String value, @NotNull final String oldPath,
                                           @NotNull final String newPath) {
        if (value.equals(oldPath)) {
            return newPath;
        }
        if (value.startsWith(oldPath + "/")) {
            return newPath + value.substring(oldPath.length());
        }
        final Matcher matcher = richTextPattern(oldPath).matcher(value);
        final StringBuilder result = new StringBuilder();
        int last = 0;
        while (matcher.find()) {
            result.append(value, last, matcher.start());
            result.append(matcher.group(1)).append(newPath).append(matcher.group(2)).append(matcher.group(3));
            last = matcher.end();
        }
        return last == 0 ? value : result.append(value, last, value.length()).toString();
    }

    /** matches e.g. {@code href="path"} or {@code src='path/child'} - group 1/3 are the quotes, group 2 the suffix (if any) after 'path' */
    private static @NotNull Pattern richTextPattern(@NotNull final String path) {
        return Pattern.compile("(=[\"'])" + Pattern.quote(path) + "((?:/[^\"']*)?)([\"'])");
    }

    private static @NotNull String escapeXPathLiteral(@NotNull final String value) {
        return value.replace("'", "''");
    }

    private static final class HitImpl implements Hit {

        private final Resource resource;
        private final Set<String> propertyNames;

        private HitImpl(@NotNull final Resource resource, @NotNull final Set<String> propertyNames) {
            this.resource = resource;
            this.propertyNames = propertyNames;
        }

        @Override
        public @NotNull Resource resource() {
            return resource;
        }

        @Override
        public @NotNull Set<String> propertyNames() {
            return propertyNames;
        }
    }
}
