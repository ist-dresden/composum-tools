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

/** Common constants and helper functions of the tools. */
public interface Common {

    /** the name of a node's content child node */
    String JCR_CONTENT = JcrConstants.JCR_CONTENT;
    /** the property holding a node's title */
    String JCR_TITLE = JcrConstants.JCR_TITLE;
    /** the property holding a node's description */
    String JCR_DESCRIPTION = JcrConstants.JCR_DESCRIPTION;
    /** the property holding the binary data of a file content node */
    String JCR_DATA = JcrConstants.JCR_DATA;
    /** the property holding a node's primary type */
    String JCR_PRIMARY_TYPE = JcrConstants.JCR_PRIMARYTYPE;
    /** the property holding a node's mixin types */
    String JCR_MIXIN_TYPES = JcrConstants.JCR_MIXINTYPES;
    /** the property holding the last modification date */
    String JCR_LAST_MODIFIED = JcrConstants.JCR_LASTMODIFIED;
    /** the property holding the creation date */
    String JCR_CREATED = JcrConstants.JCR_CREATED;
    /** the property holding the creating user */
    String JCR_CREATED_BY = JcrConstants.JCR_CREATED_BY;
    /** the property holding a referenceable node's UUID */
    String JCR_UUID = JcrConstants.JCR_UUID;
    /** the property telling if a versionable node is checked out */
    String JCR_IS_CHECKED_OUT = JcrConstants.JCR_ISCHECKEDOUT;
    /** the property referencing the base version of a versionable node */
    String JCR_BASE_VERSION = JcrConstants.JCR_BASEVERSION;
    /** the property referencing the predecessor versions of a versionable node */
    String JCR_PREDECESSORS = JcrConstants.JCR_PREDECESSORS;
    /** the property referencing the version history of a versionable node */
    String JCR_VERSION_HISTORY = JcrConstants.JCR_VERSIONHISTORY;
    /** the property holding the owner of a lock */
    String JCR_LOCK_OWNER = JcrConstants.JCR_LOCKOWNER;
    /** the property telling if a lock is deep */
    String JCR_LOCK_IS_DEEP = JcrConstants.JCR_LOCKISDEEP;
    /** the property holding the MIME type of a file content node */
    String JCR_MIME_TYPE = JcrConstants.JCR_MIMETYPE;
    /** the property holding a resource's Sling resource type */
    String SLING_RESOURCE_TYPE = "sling:resourceType";
    /** the property holding a resource's Sling resource super type */
    String SLING_RES_SUPER_TYPE = "sling:resourceSuperType";
    /** the node type of an unstructured node */
    String NT_UNSTRUCTURED = JcrConstants.NT_UNSTRUCTURED;
    /** the node type of a file content node */
    String NT_RESOURCE = JcrConstants.NT_RESOURCE;
    /** the node type of a file node */
    String NT_FILE = JcrConstants.NT_FILE;
    /** the node type of a folder node */
    String NT_FOLDER = JcrConstants.NT_FOLDER;
    /** the base node type of all nodes */
    String NT_BASE = JcrConstants.NT_BASE;
    /** the mixin type of a versionable node */
    String MIX_VERSIONABLE = JcrConstants.MIX_VERSIONABLE;
    /** the mixin type of a referenceable node */
    String MIX_REFERENCEABLE = JcrConstants.MIX_REFERENCEABLE;
    /** the mixin type of a lockable node */
    String MIX_LOCKABLE = JcrConstants.MIX_LOCKABLE;
    /** the mixin type of a node with title and description */
    String MIX_TITLE = JcrConstants.MIX_TITLE;
    /** the mixin type of a node with creation date and creating user */
    String MIX_CREATED = JcrConstants.MIX_CREATED;
    /** the mixin type of a node with last modification date and modifying user */
    String MIX_LAST_MODIFIED = JcrConstants.MIX_LAST_MODIFIED;
    /** the node type of a Sling folder */
    String SLING_FOLDER = "sling:Folder";
    /** the node type of a Sling folder with ordered children */
    String ORDERED_FOLDER = "sling:OrderedFolder";
    /** the node type of an Oak file content node */
    String OAK_RESOURCE = "oak:Resource";
    /** the name of the node holding the access control policy of a node */
    String AC_POLICY = "rep:policy";
    /** the mixin type of a node with an access control policy */
    String REP_ACCESS_CONTROLLABLE = "rep:AccessControllable";
    /** the mixin type of a node holding repository level access control */
    String REP_REPO_ACCESS_CONTROLLABLE = "rep:RepoAccessControllable";
    /** the mixin type of a node with a closed user group policy */
    String REP_CUG_MIXIN = "rep:CugMixin";
    /** the node type of a folder in the authorizables tree */
    String REP_AUTHORIZABLE_FOLDER = "rep:AuthorizableFolder";
    /** the property holding the ID of an authorizable */
    String REP_AUTHORIZABLE_ID = "rep:authorizableId";
    /** the node type of a user */
    String REP_USER = "rep:User";
    /** the node type of a system user */
    String REP_SYSTEM_USER = "rep:SystemUser";
    /** the node type of a group */
    String REP_GROUP = "rep:Group";
    /** the node type of a granting access control entry */
    String REP_GRANT_ACE = "rep:GrantACE";
    /** the node type of a denying access control entry */
    String REP_DENY_ACE = "rep:DenyACE";
    /** the property holding the principal name of an access control entry */
    String REP_PRINCIPAL_NAME = "rep:principalName";
    /** the property holding the privileges of an access control entry */
    String REP_PRIVILEGES = "rep:privileges";
    /** the name of the node holding the restrictions of an access control entry */
    String REP_RESTRICTIONS = "rep:restrictions";
    /** the mixin type of an AEM node with replication status */
    String CQ_REPLICATION_STATUS = "cq:ReplicationStatus";
    /** the mixin type of an AEM node with extended replication status */
    String CQ_REPLICATION_STATUS2 = "cq:ReplicationStatus2";

    /** the date format used in HTML views */
    String HTML_DATE_FORMAT = "yyyy-MM-dd HH:mm:ss";
    /** the date format used in JSON output */
    String JSON_DATE_FORMAT = "yyyy-MM-dd HH:mm:ss.SSSZ";
    /** the date format used in XML output */
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

    /** the decimal number format used in HTML views */
    String HTML_DECIMAL_FORMAT = "#,##0.00";

    /** the name of the HTTP header holding the content type */
    String HTTP_CONTENT_TYPE = "Content-Type";
    /** the name of the HTTP header holding the content length */
    String HTTP_CONTENT_LENGTH = "Content-Length";
    /** the name of the HTTP header holding the content disposition */
    String HTTP_CONTENT_DISPOSITION = "Content-Disposition";
    /** the name of the HTTP header holding the last modification date */
    String HTTP_LAST_MODIFIED = HttpConstants.HEADER_LAST_MODIFIED;
    /** the name of the HTTP header holding the redirect location */
    String HTTP_LOCATION = "Location";

    /** the file extension of HTML content */
    String EXT_HTML = "html";
    /** the file extension of JSON content */
    String EXT_JSON = "json";

    /** the content type of HTML responses */
    String HTML_TYPE = "text/html;charset=utf-8";
    /** the content type of JSON responses */
    String JSON_TYPE = "application/json;charset=utf-8";
    /** the content type of plain text responses */
    String TEXT_TYPE = "text/plain;charset=utf-8";

    /**
     * Builds the MIME types of the supported file extensions.
     *
     * @return the MIME type by file extension
     */
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

    /** the MIME types by lower case file extension, as built by {@link #extMimeTypes()} */
    Map<String, String> EXT_TYPES = extMimeTypes();

    /**
     * Determines the MIME type of a path from its file extension.
     *
     * @param path the path or file name (can be {@code null})
     * @return the MIME type; {@code null} if the extension is unknown
     */
    static @Nullable String pathMimeType(@Nullable final String path) {
        return extMimeType(StringUtils.substringAfterLast(path, "."));
    }

    /**
     * Determines the MIME type of a file extension.
     *
     * @param ext the file extension without dot, case insensitive (can be {@code null})
     * @return the MIME type; {@code null} if the extension is unknown
     */
    static @Nullable String extMimeType(@Nullable final String ext) {
        return ext != null ? EXT_TYPES.get(ext.toLowerCase()) : null;
    }

    /**
     * Converts an array to a modifiable list.
     *
     * @param array the array (can be {@code null})
     * @return a new list with the array elements; empty if the array is {@code null}
     */
    static @NotNull List<String> listOf(@Nullable final String[] array) {
        List<String> list = new ArrayList<>();
        if (array != null) {
            list.addAll(Arrays.asList(array));
        }
        return list;
    }

    /**
     * Compiles a configuration of regular expressions, blank rules are skipped.
     *
     * @param config the regular expression rules
     * @return the compiled patterns
     */
    static List<Pattern> patternList(@Nullable final String[] config) {
        List<Pattern> patterns = new ArrayList<>();
        for (String rule : config) {
            if (StringUtils.isNotBlank(rule)) {
                patterns.add(Pattern.compile(rule));
            }
        }
        return patterns;
    }

    /**
     * Builds a URL query string from the request parameters accepted by a filter.
     *
     * @param request the request providing the parameters
     * @param filter  decides for each parameter name and value if it is included
     * @return the URL encoded query starting with '?'; empty if no parameter is accepted
     */
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
