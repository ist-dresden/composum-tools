package com.composum.sling.browser.tool;

import com.composum.sling.browser.AbstractTool;
import com.composum.sling.browser.Browser;
import com.composum.sling.browser.Tool;
import com.composum.sling.tools.Common;
import com.composum.sling.tools.Manager;
import com.composum.sling.tools.Result;
import com.composum.sling.tools.impl.Server;
import com.composum.sling.tools.template.Template;
import com.composum.sling.tools.template.TemplateContext;
import com.composum.sling.tools.template.TemplateContext.Values;
import com.fasterxml.jackson.core.JsonProcessingException;
import org.apache.commons.lang3.StringUtils;
import org.apache.sling.api.SlingHttpServletRequest;
import org.apache.sling.api.SlingHttpServletResponse;
import org.apache.sling.api.resource.Resource;
import org.apache.sling.api.resource.ResourceResolver;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.osgi.framework.BundleContext;
import org.osgi.service.component.annotations.Activate;
import org.osgi.service.component.annotations.Component;
import org.osgi.service.component.annotations.Deactivate;
import org.osgi.service.component.annotations.Modified;
import org.osgi.service.component.annotations.Reference;
import org.osgi.service.metatype.annotations.AttributeDefinition;
import org.osgi.service.metatype.annotations.Designate;
import org.osgi.service.metatype.annotations.ObjectClassDefinition;

import java.io.InputStream;
import java.io.Reader;
import java.io.StringReader;
import java.text.SimpleDateFormat;
import java.util.Arrays;
import java.util.Calendar;
import java.util.Collection;
import java.util.Collections;
import java.util.Date;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;
import java.util.function.Supplier;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static com.composum.sling.tools.Common.EXT_JSON;
import static com.composum.sling.tools.Common.HTML_TYPE;
import static com.composum.sling.tools.Common.HTTP_CONTENT_DISPOSITION;
import static com.composum.sling.tools.Common.JCR_CONTENT;
import static com.composum.sling.tools.Common.JCR_PRIMARY_TYPE;
import static com.composum.sling.tools.Common.JSON_TYPE;
import static com.composum.sling.tools.Common.SLING_RESOURCE_TYPE;
import static com.composum.sling.tools.Common.TEXT_TYPE;
import static javax.servlet.http.HttpServletResponse.SC_INTERNAL_SERVER_ERROR;
import static javax.servlet.http.HttpServletResponse.SC_NOT_FOUND;
import static javax.servlet.http.HttpServletResponse.SC_OK;

/**
 * The browser tool to execute JCR queries (SQL2, XPath or full text) and to present or export the hits.
 */
@Component(service = {Tool.class, Query.class}, immediate = true)
@Designate(ocd = Query.Config.class)
public class Query extends AbstractTool {

    /** The key of this tool. */
    public static final String KEY = "query";

    /** The content type of the CSV export. */
    protected static final String CSV_TYPE = "text/csv;charset=utf-8";

    /**
     * Default constructor.
     */
    public Query() {
    }

    /**
     * The OSGi configuration of the query tool.
     */
    @ObjectClassDefinition(name = "Composum Browser Query Tool")
    public @interface Config {

        /**
         * The key of the tool.
         *
         * @return the tool key
         */
        @AttributeDefinition()
        String key() default Query.KEY;

        /**
         * The label of the tool.
         *
         * @return the tool label
         */
        @AttributeDefinition()
        String label() default "Query";

        /**
         * The icon of the tool.
         *
         * @return the tool icon
         */
        @AttributeDefinition()
        String icon() default "search";

        /**
         * The rank of the tool.
         *
         * @return the tool rank
         */
        @AttributeDefinition()
        int rank() default 3000;

        /**
         * The maximum number of hits presented for a query.
         *
         * @return the maximum number of hits
         */
        @AttributeDefinition()
        int maxResults() default 500;

        /**
         * The maximum number of entries kept in the query history.
         *
         * @return the maximum history size
         */
        @AttributeDefinition()
        int historyMax() default 20;
    }

    /** the browser this plugin is registered with */
    @Reference
    private void bindBrowser(Browser service) {
        browser = service;
    }

    /** The bundle context of this component. */
    protected BundleContext bundleContext;
    /** The current configuration. */
    protected Config config;

    /** The CSV export columns with their candidate property names (in configured order). */
    protected final Map<String, List<String>> csvProperties = new LinkedHashMap<>();

    /**
     * Reads the configuration, parses the CSV column rules and attaches this tool to the browser.
     *
     * @param bundleContext the bundle context
     * @param config        the component configuration
     */
    @Activate
    @Modified
    protected void activate(final BundleContext bundleContext, final Config config) {
        this.bundleContext = bundleContext;
        this.config = config;
        csvProperties.clear();
        for (String rule : browser.queryCsvProperties()) {
            Matcher matcher = Pattern.compile("^([^=]+)(=(.*))?$").matcher(rule);
            if (matcher.matches()) {
                csvProperties.put(matcher.group(1), Optional.ofNullable(matcher.group(3))
                        .filter(StringUtils::isNotBlank)
                        .map(s -> Arrays.asList(StringUtils.split(s, '|')))
                        .orElse(Collections.emptyList()));
            }
        }
        browser.tools().attach(this);
    }

    /**
     * Detaches this tool from the browser.
     */
    @Deactivate
    protected void deactivate() {
        browser.tools().detach(this);
    }

    @Override
    public @NotNull String key() {
        return Optional.ofNullable(config).map(Config::key).orElse(KEY);
    }

    @Override
    public @NotNull String label() {
        return Optional.ofNullable(config).map(Config::label).orElse("Query");
    }

    @Override
    public @NotNull String icon() {
        return Optional.ofNullable(config).map(Config::icon).orElse("search");
    }

    @Override
    public int rank() {
        return Optional.ofNullable(config).map(Config::rank).orElse(3000);
    }

    @Override
    public @NotNull Result<?> process(@NotNull SlingHttpServletRequest request, @NotNull SlingHttpServletResponse response, @NotNull List<String> selectors) {
        Result<?> result = new Result<>(SC_NOT_FOUND);
        switch (Manager.consume(selectors, "")) {
            case "save": {
                final String expression = buildQuery(request);
                final JcrQuery query = StringUtils.isNotBlank(expression) ? new JcrQuery(expression) : null;
                if (query != null) {
                    try {
                        final String path = Optional.ofNullable(request.getRequestPathInfo().getSuffix()).orElse("query");
                        final String filename = expression.toLowerCase()
                                .replaceFirst("^.*isdescendantnode *\\( *[^,]+, *", "")
                                .replaceAll("\\$\\{[^}]*}", "").replace("jcr:", "")
                                .replaceAll("[^a-zA-Z_]+", "-")
                                .replaceFirst("^-+", "").replaceFirst("-+$", "");
                        final ResourceResolver resolver = request.getResourceResolver();
                        final HitIterator iterator = new HitIterator(query.find(resolver), null);
                        switch (StringUtils.defaultString(request.getRequestPathInfo().getExtension(), "")) {
                            case "csv":
                            case "txt": {
                                final String extension = request.getRequestPathInfo().getExtension();
                                final Reader content = browser.templateReader(getTemplate(new TemplateContext(new Values()
                                        .with("header", csvHeader())
                                        .with("iterator", iterator)), "save.csv"));
                                if (content != null) {
                                    result = new Result<>(content, CSV_TYPE);
                                    result.setHeader(HTTP_CONTENT_DISPOSITION, "attachment; filename=" + filename + "." + extension);
                                }
                            }
                            break;
                            case "json":
                            default: {
                                final Reader content = browser.templateReader(getTemplate(new TemplateContext(new Values()
                                        .with("iterator", iterator)), "save.json"));
                                if (content != null) {
                                    result = new Result<>(content, JSON_TYPE);
                                    result.setHeader(HTTP_CONTENT_DISPOSITION, "attachment; filename=" + filename + "." + EXT_JSON);
                                }
                            }
                            break;
                        }
                    } catch (RuntimeException ex) {
                        result = new Result<>(SC_INTERNAL_SERVER_ERROR);
                    }
                }
            }
            break;
            case "detail": {
                final Resource target = browser.manager.requestResource(request);
                if (target != null) {
                    final Map<String, Object> properties = hitProperties(target);
                    final Reader content = browser.templateReader(getTemplate(new TemplateContext(new Values()
                            .with("target", new Values()
                                    .with("path", target.getPath())
                                    .with("json", (Supplier<?>) () -> (Supplier<?>) () -> {
                                        try {
                                            return new StringReader(Server.MAPPER.writerWithDefaultPrettyPrinter()
                                                    .writeValueAsString(properties));
                                        } catch (JsonProcessingException ex) {
                                            return ex.getMessage();
                                        }
                                    })
                            )), "detail"));
                    if (content != null) {
                        result = new Result<>(content, TEXT_TYPE);
                    }
                }
            }
            break;
            case "find": {
                final String expression = buildQuery(request);
                final JcrQuery query = StringUtils.isNotBlank(expression) ? new JcrQuery(expression) : null;
                if (query != null) {
                    final Values queryValues = new Values().with("expression", query.getQuery());
                    try {
                        final ResourceResolver resolver = request.getResourceResolver();
                        final HitIterator iterator = new HitIterator(query.find(resolver), config.maxResults());
                        queryValues
                                .with("find", (Supplier<?>) () -> iterator)
                                .with("count", (Supplier<?>) iterator::getCount)
                                .with("hasMore", (Supplier<?>) iterator::hasMore)
                                .with("maxResults", config.maxResults());
                    } catch (RuntimeException ex) {
                        queryValues.with("exception", ex.getMessage());
                    }
                    final Reader content = browser.templateReader(getTemplate(new TemplateContext(new Values()
                            .with("query", queryValues)), "find"));
                    if (content != null) {
                        result = new Result<>(content, HTML_TYPE);
                    }
                } else {
                    result = new Result<>(SC_OK);
                }
            }
            break;
            default: {
                final String path = Optional.ofNullable(request.getRequestPathInfo().getSuffix()).orElse("");
                final Reader content = browser.templateReader(getTemplate(new TemplateContext(new Values()
                        .with("query.uri", (Supplier<String>) () -> uri() + path)
                        .with("query.action", (Supplier<String>) () -> uri("find") + path)
                        .with("query.save.csv", (Supplier<String>) () -> uri(key() + ".save", "csv") + path)
                        .with("query.save.json", (Supplier<String>) () -> uri(key() + ".save", EXT_JSON) + path)
                ), "tool"));
                if (content != null) {
                    result = new Result<>(content, HTML_TYPE);
                }
            }
            break;
        }
        return result;
    }

    /** The template factories of this tool by key. */
    public final Map<String, Factory> templates = Map.of(
            "tool", current ->
                    new Template("/sling/browser/tool/query/query.html", new TemplateContext(current, new Values()
                            .with("query", new Values()
                                    .with("popover", (Supplier<String>) () -> uri(key() + ".detail", EXT_JSON))
                                    .with("templates", (Supplier<?>) () -> browser.queryTemplates())
                                    .with("maxResults", (Supplier<?>) () -> config.maxResults())
                                    .with("historyMax", (Supplier<?>) () -> config.historyMax()))), this),
            "find", current ->
                    new Template("/sling/browser/tool/query/result.html",
                            new TemplateContext(current, new Values()), this),
            "detail", current ->
                    new Template("/sling/browser/tool/query/detail.tmpl",
                            new TemplateContext(current, new Values()), this),
            "save.json", current ->
                    new Template("/sling/browser/tool/query/save/json.tmpl",
                            new TemplateContext(current, new Values()), this),
            "save.csv", current ->
                    new Template("/sling/browser/tool/query/save/csv.tmpl",
                            new TemplateContext(current, new Values()), this)
    );

    @Override
    public @Nullable Template getTemplate(@NotNull TemplateContext context, @NotNull String key) {
        return Optional.ofNullable(templates.get(key))
                .map(factory -> factory.create(context))
                .orElse(key.startsWith("/") ? new Template(key, context, this) : null);
    }

    @Override
    public @NotNull Collection<String> styles() {
        return Collections.singletonList("/sling/browser/tool/query/style.css");
    }

    @Override
    public @NotNull Collection<String> scripts() {
        return Collections.singletonList("/sling/browser/tool/query/script.js");
    }

    /**
     * Builds the query expression from the 'query' request parameter, replacing the placeholders
     * '${path}' (request suffix) and '${1}' to '${3}' (request parameters 'arg1' to 'arg3').
     *
     * @param request the current request
     * @return the query expression
     */
    protected String buildQuery(@NotNull final SlingHttpServletRequest request) {
        String pattern = StringUtils.defaultString(request.getParameter("query"), "");
        try {
            pattern = pattern.replaceAll("\\$\\{path}", Optional.ofNullable(request
                    .getRequestPathInfo().getSuffix()).orElse(""));
            for (int i = 1; i <= 3; i++) {
                pattern = pattern
                        .replaceAll("\\$\\{" + i + "}", Optional.ofNullable(request
                                .getParameter("arg" + i)).orElse(""));
            }
        } catch (RuntimeException ignore) {
        }
        return pattern;
    }

    /**
     * builds the CSV header line from the configured {@link #csvProperties} columns (in their
     * configured order), CSV-escaped like any other cell.
     *
     * @return the CSV header line
     */
    protected @NotNull String csvHeader() {
        final StringBuilder row = new StringBuilder();
        for (final String column : csvProperties.keySet()) {
            if (row.length() > 0) {
                row.append(",");
            }
            row.append(csvCell(column));
        }
        return row.append("\n").toString();
    }

    /**
     * builds one CSV line for a single hit, one cell per configured {@link #csvProperties} column -
     * kept as a small, self-contained string per hit so the export as a whole can stream row by row
     * instead of buffering the full result. The 'path' column (no candidate properties configured)
     * is filled from the hit's resource path; every other column tries its configured cascade of
     * candidate property names in order and uses the first one present, defaulting to an empty cell.
     * A 'jcr:primaryType' candidate is always resolved against the hit's own resource, never against
     * 'properties' - which for a resource with a 'jcr:content' child holds the content node's
     * properties (e.g. 'cq:PageContent'), whereas the type column is meant to show the resource's
     * own type (e.g. 'cq:Page').
     *
     * @param path       the path of the hit
     * @param resource   the resource of the hit
     * @param properties the properties of the hit
     * @return the CSV line of the hit
     */
    protected @NotNull String csvRow(@NotNull final String path, @NotNull final Resource resource,
                                     @NotNull final Map<String, Object> properties) {
        final StringBuilder row = new StringBuilder();
        for (final Map.Entry<String, List<String>> column : csvProperties.entrySet()) {
            final String columnName = column.getKey();
            if (row.length() > 0) {
                row.append(",");
            }
            row.append("path".equals(columnName)
                    ? csvCell(path)
                    : csvCell(Optional.ofNullable(csvValue(column.getValue(), resource, properties))
                    .orElse("name".equals(columnName) ? StringUtils.substringAfterLast(path, "/") : null)));
        }
        return row.append("\n").toString();
    }

    /**
     * Determines the value of a CSV cell from the first candidate property which is present.
     *
     * @param candidates the candidate property names in order of precedence
     * @param resource   the resource of the hit
     * @param properties the properties of the hit
     * @return the value found, or {@code null} if none of the candidates is present
     */
    protected @Nullable Object csvValue(@NotNull final List<String> candidates, @NotNull final Resource resource,
                                        @NotNull final Map<String, Object> properties) {
        for (final String candidate : candidates) {
            final Object value = JCR_PRIMARY_TYPE.equals(candidate)
                    ? resource.getValueMap().get(JCR_PRIMARY_TYPE)
                    : properties.get(candidate);
            if (value != null) {
                return value;
            }
        }
        return null;
    }

    /**
     * Converts a value to a CSV cell, quoting it if necessary.
     *
     * @param value the cell value
     * @return the CSV cell
     */
    protected @NotNull String csvCell(@Nullable final Object value) {
        final String string = csvString(value);
        if (string.indexOf(',') >= 0 || string.indexOf('"') >= 0
                || string.indexOf('\n') >= 0 || string.indexOf('\r') >= 0) {
            return "\"" + string.replace("\"", "\"\"") + "\"";
        }
        return string;
    }

    /**
     * Converts a value to its string representation for a CSV cell (array values are joined by '; ',
     * dates are formatted as HTML dates and binary values are rendered as empty strings).
     *
     * @param value the value
     * @return the string representation of the value
     */
    protected @NotNull String csvString(@Nullable final Object value) {
        if (value == null) {
            return "";
        } else if (value instanceof Object[]) {
            final Object[] values = (Object[]) value;
            final StringBuilder buffer = new StringBuilder();
            for (int i = 0; i < values.length; i++) {
                if (i > 0) {
                    buffer.append("; ");
                }
                buffer.append(csvString(values[i]));
            }
            return buffer.toString();
        } else if (value instanceof Calendar) {
            return new SimpleDateFormat(Common.HTML_DATE_FORMAT).format(((Calendar) value).getTime());
        } else if (value instanceof Date) {
            return new SimpleDateFormat(Common.HTML_DATE_FORMAT).format((Date) value);
        } else if (value instanceof InputStream) {
            return "";
        }
        return value.toString();
    }

    /**
     * An iterator over the template values of the query hits; hits which are not allowed to be
     * accessed are skipped and the number of hits can be limited.
     */
    protected class HitIterator implements Iterator<Values> {

        private final Iterator<Resource> iterator;
        private final Integer maxResults;
        private int count = 0;

        private transient Resource next;

        /**
         * Creates the iterator.
         *
         * @param iterator   the iterator over the found resources
         * @param maxResults the maximum number of hits to iterate, {@code null} for no limit
         */
        public HitIterator(@NotNull final Iterator<Resource> iterator, Integer maxResults) {
            this.iterator = iterator;
            this.maxResults = maxResults;
        }

        /**
         * Returns the number of hits delivered so far.
         *
         * @return the number of hits
         */
        public int getCount() {
            return count;
        }

        /**
         * Tells whether the underlying iterator has further resources.
         *
         * @return 'true' if there are more resources (e.g. after the limit has been reached)
         */
        public boolean hasMore() {
            return iterator.hasNext();
        }

        @Override
        public synchronized boolean hasNext() {
            if (next == null && (maxResults == null || count < maxResults)) {
                do {
                    next = iterator.hasNext() ? iterator.next() : null;
                } while (next != null && !browser.manager.isAllowedResource(next));
            }
            return next != null;
        }

        @Override
        public synchronized Values next() {
            Values values = null;
            if (hasNext()) {
                count++;
                final Resource resource = next;
                final String path = resource.getPath();
                final Map<String, Object> properties = hitProperties(next);
                values = new Values()
                        .with("path", path)
                        .with("separator", count > 1 ? "," : "")
                        .with("json", (Supplier<?>) () -> {
                            try {
                                return new StringReader(Server.MAPPER.writeValueAsString(properties));
                            } catch (JsonProcessingException ex) {
                                return ex.getMessage();
                            }
                        })
                        .with("csv", (Supplier<?>) () -> new StringReader(csvRow(path, resource, properties)))
                        .with("resourceType", properties.get(SLING_RESOURCE_TYPE))
                        .with("primaryType", resource.getValueMap().get(JCR_PRIMARY_TYPE))
                        .with("properties", properties);
                next = null;
            }
            return values;
        }
    }

    /**
     * Collects the properties of a hit, taken from its content child if there is one.
     *
     * @param resource the resource of the hit
     * @return the properties of the hit
     */
    protected @NotNull Map<String, Object> hitProperties(@NotNull final Resource resource) {
        final Resource content = resource.getName().equals(JCR_CONTENT) ? resource
                : Optional.ofNullable(resource.getChild(JCR_CONTENT)).orElse(resource);
        return extendHitProperties(content, resourceProperties(content, new TreeMap<>()));
    }

    /**
     * for the extended AEM implementation,
     *  TODO: provision of extended AEM Query implementation, should be empty here, extension point only
     *
     * @param resource   the content resource of the hit
     * @param properties the properties collected so far
     * @return the extended properties
     */
    protected @NotNull Map<String, Object> extendHitProperties(@NotNull final Resource resource,
                                                               @NotNull final Map<String, Object> properties) {
        Optional.ofNullable(resource.getChild("metadata")).ifPresent(metadata ->
                resourceProperties(metadata, properties, "dc:format"::equals));
        return properties;
    }
}
