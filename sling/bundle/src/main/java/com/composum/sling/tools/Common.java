package com.composum.sling.tools;

import org.apache.commons.lang3.StringUtils;
import org.apache.jackrabbit.vault.util.JcrConstants;
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

    String JCR_CONTENT = JcrConstants.JCR_CONTENT;
    String JCR_TITLE = JcrConstants.JCR_TITLE;
    String JCR_DESCRIPTION = JcrConstants.JCR_DESCRIPTION;
    String JCR_DATA = JcrConstants.JCR_DATA;
    String JCR_PRIMARY_TYPE = JcrConstants.JCR_PRIMARYTYPE;
    String JCR_MIXIN_TYPES = JcrConstants.JCR_MIXINTYPES;
    String JCR_LAST_MODIFIED = JcrConstants.JCR_LASTMODIFIED;
    String JCR_CREATED = JcrConstants.JCR_CREATED;
    String JCR_CREATED_BY = JcrConstants.JCR_CREATED_BY;
    String JCR_UUID = JcrConstants.JCR_UUID;
    String JCR_IS_CHECKED_OUT = JcrConstants.JCR_ISCHECKEDOUT;
    String JCR_BASE_VERSION = JcrConstants.JCR_BASEVERSION;
    String JCR_PREDECESSORS = JcrConstants.JCR_PREDECESSORS;
    String JCR_VERSION_HISTORY = JcrConstants.JCR_VERSIONHISTORY;
    String JCR_LOCK_OWNER = JcrConstants.JCR_LOCKOWNER;
    String JCR_LOCK_IS_DEEP = JcrConstants.JCR_LOCKISDEEP;
    String JCR_MIME_TYPE = JcrConstants.JCR_MIMETYPE;
    String SLING_RESOURCE_TYPE = "sling:resourceType";
    String SLING_RES_SUPER_TYPE = "sling:resourceSuperType";
    String NT_UNSTRUCTURED = JcrConstants.NT_UNSTRUCTURED;
    String NT_RESOURCE = JcrConstants.NT_RESOURCE;
    String NT_FILE = JcrConstants.NT_FILE;
    String NT_FOLDER = JcrConstants.NT_FOLDER;
    String NT_BASE = JcrConstants.NT_BASE;
    String MIX_VERSIONABLE = JcrConstants.MIX_VERSIONABLE;
    String MIX_REFERENCEABLE = JcrConstants.MIX_REFERENCEABLE;
    String MIX_LOCKABLE = JcrConstants.MIX_LOCKABLE;
    String MIX_TITLE = JcrConstants.MIX_TITLE;
    String MIX_CREATED = JcrConstants.MIX_CREATED;
    String MIX_LAST_MODIFIED = JcrConstants.MIX_LAST_MODIFIED;
    String SLING_FOLDER = "sling:Folder";
    String ORDERED_FOLDER = "sling:OrderedFolder";
    String OAK_RESOURCE = "oak:Resource";
    String AC_POLICY = "rep:policy";
    String REP_ACCESS_CONTROLLABLE = "rep:AccessControllable";
    String REP_REPO_ACCESS_CONTROLLABLE = "rep:RepoAccessControllable";
    String REP_CUG_MIXIN = "rep:CugMixin";
    String REP_AUTHORIZABLE_FOLDER = "rep:AuthorizableFolder";
    String REP_AUTHORIZABLE_ID = "rep:authorizableId";
    String REP_USER = "rep:User";
    String REP_SYSTEM_USER = "rep:SystemUser";
    String REP_GROUP = "rep:Group";
    String REP_GRANT_ACE = "rep:GrantACE";
    String REP_DENY_ACE = "rep:DenyACE";
    String REP_PRINCIPAL_NAME = "rep:principalName";
    String REP_PRIVILEGES = "rep:privileges";
    String REP_RESTRICTIONS = "rep:restrictions";
    String CQ_REPLICATION_STATUS = "cq:ReplicationStatus";
    String CQ_REPLICATION_STATUS2 = "cq:ReplicationStatus2";

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
