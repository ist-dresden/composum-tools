package com.composum.sling.tools;

import org.apache.commons.lang3.StringUtils;
import org.apache.sling.api.SlingHttpServletRequest;
import org.apache.sling.api.servlets.HttpConstants;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.BiFunction;
import java.util.regex.Pattern;

public interface Common {

    String JCR_CONTENT = "jcr:content";
    String JCR_TITLE = "jcr:title";
    String JCR_DESCRIPTION = "jcr:description";
    String JCR_DATA = "jcr:data";
    String JCR_PRIMARY_TYPE = "jcr:primaryType";
    String JCR_MIXIN_TYPES = "jcr:mixinTypes";
    String JCR_LAST_MODIFIED = "jcr:lastModified";
    String JCR_CREATED = "jcr:created";
    String JCR_MIME_TYPE = "jcr:mimeType";
    String SLING_RESOURCE_TYPE = "sling:resourceType";
    String SLING_RES_SUPER_TYPE = "sling:resourceSuperType";
    String NT_UNSTRUCTURED = "nt:unstructured";
    String NT_RESOURCE = "nt:resource";
    String NT_FILE = "nt:file";
    String NT_FOLDER = "nt:folder";
    String SLING_FOLDER = "sling:Folder";
    String ORDERED_FOLDER = "sling:OrderedFolder";
    String AC_POLICY = "rep:policy";

    String HTML_DATE_FORMAT = "yyyy-MM-dd HH:mm:ss";
    String JSON_DATE_FORMAT = "yyyy-MM-dd HH:mm:ss.SSSZ";
    String XML_DATE_FORMAT = "yyyy-MM-dd'T'HH:mm:ss.SSSXXX";
    /**
     * The single canonical 'Date' property format shared by every place that displays or edits one
     * (the Browser's Properties view and the Changes property-edit dialog) - kept here, rather than
     * in either package alone, precisely so both can reference the exact same literal without
     * either depending on the other for it. The trailing 'X' is a single-letter ISO-8601 offset:
     * a two-digit hour with no minutes, e.g. '+02' or '-05' - or, since a Date property's time zone
     * matters but is expected to be GMT far more often than not, the more immediately noticeable
     * 'Z' whenever it actually is (rather than the equivalent but easy-to-miss '+00').
     */
    String PROPERTY_DATE_FORMAT = "yyyy-MM-dd HH:mm:ss X";

    String HTML_DECIMAL_FORMAT = "#,##0.00";

    String HTTP_CONTENT_TYPE = "Content-Type";
    String HTTP_CONTENT_LENGTH = "Content-Length";
    String HTTP_CONTENT_DISPOSITION = "Content-Disposition";
    String HTTP_LAST_MODIFIED = HttpConstants.HEADER_LAST_MODIFIED;
    String HTTP_LOCATION = "Location";

    String EXT_HTML = "html";
    String EXT_JSON = "json";

    String HTML_TYPE = "text/html;charset=utf-8";
    String JSON_TYPE = "application/json;charset=utf-8";
    String TEXT_TYPE = "text/plain;charset=utf-8";

    static Map<String, String> extMimeTypes() {
        Map<String, String> set = new HashMap<>();
        set.put("html", HTML_TYPE);
        set.put("json", JSON_TYPE);
        set.put("js", "text/javascript");
        set.put("css", "text/css");
        set.put("svg", "image/svg+xml");
        set.put("webp", "image/webp");
        set.put("png", "image/png");
        set.put("jpg", "image/jpeg");
        set.put("jpeg", "image/jpeg");
        set.put("gif", "image/gif");
        set.put("woff", "font/woff");
        set.put("woff2", "font/woff2");
        return set;
    }

    Map<String, String> EXT_TYPES = extMimeTypes();

    static @Nullable String pathMimeType(@Nullable final String path) {
        return extMimeType(StringUtils.substringAfterLast(path, "."));
    }

    static @Nullable String extMimeType(@Nullable final String ext) {
        return ext != null ? EXT_TYPES.get(ext.toLowerCase()) : null;
    }

    static @NotNull List<String> listOf(@Nullable final String[] array) {
        List<String> list = new ArrayList<>();
        if (array != null) {
            list.addAll(Arrays.asList(array));
        }
        return list;
    }

    static List<Pattern> patternList(@Nullable final String[] config) {
        List<Pattern> patterns = new ArrayList<>();
        for (String rule : config) {
            if (StringUtils.isNotBlank(rule)) {
                patterns.add(Pattern.compile(rule));
            }
        }
        return patterns;
    }

    static @NotNull String urlQueryOf(@NotNull final SlingHttpServletRequest request,
                                      @NotNull final BiFunction<String, String, Boolean> filter) {
        final StringBuilder parameters = new StringBuilder();
        for (final Map.Entry<String, String[]> entry : request.getParameterMap().entrySet()) {
            final String name = entry.getKey();
            for (final String value : entry.getValue()) {
                if (filter.apply(name, value)) {
                    parameters.append(parameters.length() == 0 ? '?' : '&')
                            .append(URLEncoder.encode(name, StandardCharsets.UTF_8))
                            .append('=')
                            .append(URLEncoder.encode(value, StandardCharsets.UTF_8));
                }
            }
        }
        return parameters.toString();
    }
}
