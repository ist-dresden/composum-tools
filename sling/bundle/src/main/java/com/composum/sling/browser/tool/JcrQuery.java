package com.composum.sling.browser.tool;

import org.apache.commons.lang3.StringUtils;
import org.apache.sling.api.resource.Resource;
import org.apache.sling.api.resource.ResourceResolver;
import org.jetbrains.annotations.NotNull;

import javax.jcr.query.Query;
import java.util.Iterator;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import static com.composum.sling.tools.Common.NT_BASE;

/**
 * A query on the JCR repository which is derived from a query pattern entered by a user:
 * a SQL2 query, an XPath query or a simple full text search expression (translated to SQL2).
 */
public class JcrQuery {

    /** The type name used for full text search expressions. */
    public static final String FULL_TEXT = "text";

    /** Matches a SQL2 query. */
    public static final Pattern SQL2_QUERY = Pattern.compile("^(union +)?select +.*$", Pattern.CASE_INSENSITIVE);
    /** Matches an XPath query. */
    public static final Pattern XPATH_QUERY = Pattern.compile("^(/jcr:root)?/[^ ]*([*(\\[].*)?$");
    /** Matches a full text search expression with an optional node type, an optional root path and the search text. */
    public static final Pattern TEXT_QUERY = Pattern.compile(
            "^((\\[(?<type>[^]]+)])?(?<path>/[^/*(\\[ ]*(/[^/*(\\[ ]*)*)? +)?(?<text>.*)$");

    /** Splits a search text into its terms. */
    public static final Pattern SPLIT_TERMS = Pattern.compile(" +");

    /** Matches a search term prefixed by a property name in square brackets. */
    public static final Pattern NAME_AND_TEXT = Pattern.compile("^\\[(?<name>[^]]+)](?<text>.+)$");

    private final String pattern;
    private final String queryType;

    private transient String query;

    /** The matcher of the full text expression (set if the query is a full text search). */
    protected Matcher textMatcher;
    /** The SQL2 query built from the full text expression (built lazily). */
    protected String textQuery;

    /**
     * Creates a query from the given pattern and determines the query type.
     *
     * @param pattern the query pattern (SQL2, XPath or full text expression)
     */
    @SuppressWarnings("deprecation")
    public JcrQuery(@NotNull final String pattern) {
        if (SQL2_QUERY.matcher(pattern).matches()) {
            queryType = Query.JCR_SQL2;
        } else if (!XPATH_QUERY.matcher(pattern).matches()
                && (textMatcher = TEXT_QUERY.matcher(pattern)).matches()) {
            queryType = FULL_TEXT;
        } else {
            queryType = Query.XPATH;
        }
        if (Query.XPATH.equals(queryType)) {
            this.pattern = pattern.startsWith("/jcr:root/") ? pattern : ("/jcr:root" + pattern);
        } else {
            this.pattern = pattern;
        }
    }

    /**
     * Executes the query.
     *
     * @param resolver the resolver used to find the resources
     * @return the resources found by the query
     */
    public @NotNull Iterator<Resource> find(@NotNull final ResourceResolver resolver) {
        if (FULL_TEXT.equals(queryType)) {
            return resolver.findResources(getTextQuery(), Query.JCR_SQL2);
        } else {
            return resolver.findResources(pattern, queryType);
        }
    }

    /**
     * Returns the effective query string.
     *
     * @return the SQL2 query for a full text expression, otherwise the (normalized) query pattern
     */
    public @NotNull String getQuery() {
        if (FULL_TEXT.equals(queryType)) {
            return getTextQuery();
        } else {
            return pattern;
        }
    }

    /**
     * Returns the type of the query.
     *
     * @return the JCR query language, or {@link #FULL_TEXT} for a full text expression
     */
    public @NotNull String getQueryType() {
        return queryType;
    }

    /**
     * Returns the SQL2 query built from the full text expression.
     *
     * @return the SQL2 query
     */
    public @NotNull String getTextQuery() {
        if (textQuery == null) {
            textQuery = textQuery();
        }
        return textQuery;
    }

    /**
     * Builds the SQL2 query from the full text expression.
     *
     * @return the SQL2 query
     */
    protected @NotNull String textQuery() {
        String type = StringUtils.defaultIfBlank(textMatcher.group("type"), NT_BASE);
        String path = StringUtils.defaultIfBlank(textMatcher.group("path"), "/content");
        StringBuilder query = new StringBuilder();
        query.append("select * from [").append(type).append("] as node where")
                .append(" isdescendantnode(node, '").append(path).append("') and (");
        Matcher propMatcher;
        String property = "*";
        String operator = "";
        boolean openCond = true;
        String chr;
        StringBuilder collector = new StringBuilder();
        for (String term : SPLIT_TERMS.splitAsStream(textMatcher.group("text"))
                .filter(StringUtils::isNotBlank).collect(Collectors.toList())) {
            if (collector.length() == 0) {
                if (term.startsWith("+")) {
                    term = term.substring(1);
                    if (!openCond) {
                        query.append(" or ");
                        openCond = true;
                    }
                } else {
                    if (!openCond) {
                        query.append(" and ");
                        openCond = true;
                    }
                }
                if (term.startsWith("-")) {
                    term = term.substring(1);
                    query.append("not ");
                }
            }
            if (collector.length() > 0) {
                collector.append(" ");
                if (term.endsWith("\"")) {
                    collector.append(term, 0, term.length() - 1);
                    if (StringUtils.isBlank(operator)) {
                        collector.append("*");
                    }
                    term = collector.toString();
                    collector = new StringBuilder();
                } else {
                    collector.append(term);
                    continue;
                }
            } else {
                if ((propMatcher = NAME_AND_TEXT.matcher(term)).matches()) {
                    property = propMatcher.group("name");
                    term = propMatcher.group("text");
                }
                while (StringUtils.isNotBlank(term) && "=><".contains(chr = term.substring(0, 1))) {
                    if (!operator.contains(chr)) {
                        operator += chr;
                    }
                    term = term.substring(1);
                }
                if (term.startsWith("\"")) {
                    if (StringUtils.isBlank(operator)) {
                        collector.append("*");
                    }
                    term = term.substring(1);
                    if (term.endsWith("\"")) {
                        collector.append(term, 0, term.length() - 1);
                        if (StringUtils.isBlank(operator)) {
                            collector.append("*");
                        }
                        term = collector.toString();
                        collector = new StringBuilder();
                    } else {
                        collector.append(term);
                        continue;
                    }
                }
            }
            if (StringUtils.isNotBlank(term)) {
                if (StringUtils.isNotBlank(operator)) {
                    if (operator.contains(">")) { // ensure that a possible '><' will be '<>'
                        operator = operator.replace(">", "") + ">";
                    }
                    if (operator.contains("=")) { // ensure that a possible '=>' will be '>='
                        operator = operator.replace("=", "") + "=";
                    }
                    query.append("[").append(property).append("]")
                            .append(operator).append("'").append(term).append("'");
                } else {
                    if (term.contains("*")) {
                        query.append("[").append(property).append("] like '")
                                .append(term.replace('*', '%')).append("'");
                    } else {
                        query.append("contains([").append(property)
                                .append("],'").append(term).append("')");
                    }
                }
                property = "*";
                operator = "";
                openCond = false;
            }
        }
        query.append(")");
        return query.toString();
    }
}
