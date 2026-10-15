package com.composum.sling.browser.view;

import com.composum.sling.browser.AbstractView;
import com.composum.sling.browser.Browser;
import com.composum.sling.browser.View;
import com.composum.sling.tools.Manager;
import com.composum.sling.tools.PlatformConfig;
import com.composum.sling.tools.Result;
import com.composum.sling.tools.template.Template;
import com.composum.sling.tools.template.TemplateContext;
import com.composum.sling.tools.template.TemplateContext.Values;
import org.apache.commons.lang3.StringUtils;
import org.apache.sling.api.SlingHttpServletRequest;
import org.apache.sling.api.SlingHttpServletResponse;
import org.apache.sling.api.resource.Resource;
import org.apache.sling.api.resource.ValueMap;
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
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.util.Calendar;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static com.composum.sling.tools.Common.HTML_TYPE;
import static com.composum.sling.tools.Common.HTTP_CONTENT_DISPOSITION;
import static com.composum.sling.tools.Common.HTTP_LAST_MODIFIED;
import static com.composum.sling.tools.Common.JCR_CONTENT;
import static com.composum.sling.tools.Common.JCR_DATA;
import static com.composum.sling.tools.Common.JCR_LAST_MODIFIED;
import static com.composum.sling.tools.Common.JCR_MIME_TYPE;
import static com.composum.sling.tools.Common.JCR_PRIMARY_TYPE;
import static com.composum.sling.tools.Common.SLING_RESOURCE_TYPE;
import static com.composum.sling.tools.Common.TEXT_TYPE;
import static com.composum.sling.tools.Common.urlQueryOf;
import static javax.servlet.http.HttpServletResponse.SC_NOT_FOUND;
import static javax.servlet.http.HttpServletResponse.SC_OK;

/** The browser view displaying a preview of the current resource. */
@Component(service = {View.class, DisplayView.class}, immediate = true)
@Designate(ocd = DisplayView.Config.class)
public class DisplayView extends AbstractView {

    /**
     * Default constructor.
     */
    public DisplayView() {
    }

    /** the key of this view */
    public static final String KEY = "display";

    /** OSGi metatype configuration of the Display view. */
    @ObjectClassDefinition(name = "Composum Browser Display View")
    public @interface Config {

        /**
         * The selector key of this view.
         *
         * @return the selector key of this view
         */
        @AttributeDefinition()
        String key() default DisplayView.KEY;

        /**
         * The navigation label of this view.
         *
         * @return the navigation label of this view
         */
        @AttributeDefinition()
        String label() default "Preview";

        /**
         * The navigation rank of this view.
         *
         * @return the navigation rank of this view
         */
        @AttributeDefinition()
        int rank() default 6000;
    }

    @Reference
    /** the platform configuration used to resolve files and their original resources */
    private PlatformConfig platformConfig;

    @Reference
    /** the browser plugin this view belongs to */
    private Browser browser;

    /** the bundle context this view was activated with */
    protected BundleContext bundleContext;
    /** the current OSGi configuration */
    protected Config config;

    /**
     * Activates or reconfigures this view and attaches it to the browser.
     *
     * @param bundleContext the bundle context of this component
     * @param config        the current OSGi configuration
     */
    @Activate
    @Modified
    protected void activate(final BundleContext bundleContext, final Config config) {
        this.bundleContext = bundleContext;
        this.config = config;
        browser.views().attach(this);
    }

    /** Detaches this view from the browser. */
    @Deactivate
    protected void deactivate() {
        browser.views().detach(this);
    }

    @Override
    public Browser browser() {
        return browser;
    }

    /**
     * The platform configuration.
     *
     * @return the platform configuration
     */
    public PlatformConfig platformConfig() {
        return platformConfig;
    }

    @Override
    public @NotNull String key() {
        return Optional.ofNullable(config).map(Config::key).orElse(KEY);
    }

    @Override
    public @NotNull String label() {
        return Optional.ofNullable(config).map(Config::label).orElse("Preview");
    }

    @Override
    public int rank() {
        return Optional.ofNullable(config).map(Config::rank).orElse(6000);
    }

    @Override
    public @NotNull Result<?> process(@NotNull final SlingHttpServletRequest request,
                                      @NotNull final SlingHttpServletResponse response,
                                      @NotNull final List<String> selectors) {
        Result<?> result = new Result<>(SC_NOT_FOUND);
        switch (Manager.consume(selectors, "")) {
            case "resource":
                result = browser().resource(request);
                break;
            case "form":
                result = params(request, response);
                break;
            case "load":
                result = openContent(request, null);
                break;
            case "text":
                result = openContent(request, TEXT_TYPE);
                break;
            default: {
                final Resource resource = browser().manager.requestResource(request);
                if (resource != null) {
                    final Resource target = getTargetResource(resource);
                    final Type displayType = getDisplayType(resource);
                    switch (displayType) {
                        case PREVIEW:
                            result = preview(request, response, displayType, new Values()
                                    .with("targetUrl", getTargetUri(resource, "html") + urlQueryOf(request,
                                            (name, value) -> StringUtils.isNotBlank(value))));
                            break;
                        case IMAGE:
                        case VIDEO:
                        case BINARY:
                            result = preview(request, response, displayType, new Values()
                                    .with("targetUrl", getTargetUri(resource, null))
                                    .with("targetType", getExtension(target))
                                    .with("filename", target.getName()));
                            break;
                        case TEXT:
                        case CODE:
                            result = preview(request, response, displayType, new Values()
                                    .with("targetUrl", browser().manager.serverPath()
                                            + ".browser.view.display.text.html"
                                            + getTargetUri(resource, null))
                                    .with("targetType", getExtension(target))
                                    .with("filename", target.getName()));
                            break;
                        case DOCUMENT:
                        default:
                            result = preview(request, response, displayType, new Values()
                                    .with("targetUrl", browser().manager.serverPath()
                                            + ".browser.view.display.load.html"
                                            + getTargetUri(resource, null)));
                            break;
                    }
                }
            }
            break;
        }
        return result;
    }

    /** the template factories of this view by template key */
    public final Map<String, Factory> templates = Map.of(
            "view", current ->
                    new Template("/sling/browser/view/properties/properties.html",
                            new TemplateContext(current, new Values()), this)
    );

    @Override
    public @Nullable Template getTemplate(@NotNull TemplateContext context, @NotNull String key) {
        return Optional.ofNullable(templates.get(key))
                .map(factory -> factory.create(context))
                .orElse(key.startsWith("/") ? new Template(key, context, this) : null);

    }

    /**
     * Provides the (empty) form of this view.
     *
     * @param request  the current request
     * @param response the current response
     * @return an 'OK' result
     */
    protected Result<?> params(@NotNull final SlingHttpServletRequest request,
                               @NotNull final SlingHttpServletResponse response) {
        return new Result<>(SC_OK);
    }

    /** The kinds of content a resource is displayed as. */
    protected enum Type {
        /** a rendered preview of the resource itself */
        PREVIEW,
        /** plain text content */
        TEXT,
        /** source code or markup content */
        CODE,
        /** an image */
        IMAGE,
        /** a video */
        VIDEO,
        /** a document opened in the browser (e.g. PDF, JSON) */
        DOCUMENT,
        /** binary content without a specific display */
        BINARY,
        /** the display type cannot be determined */
        UNKNOWN
    }

    /**
     * Renders the display template of the given type.
     *
     * @param ignoredRequest the current request (not used)
     * @param response       the current response
     * @param type            the display type selecting the template
     * @param values          the values for the template
     * @return the rendered template, or {@code null} if the template cannot be read
     */
    protected Result<?> preview(@NotNull final SlingHttpServletRequest ignoredRequest,
                                @NotNull final SlingHttpServletResponse response,
                                @NotNull final Type type, @NotNull final Values values) {
        final Template template = getTemplate(new TemplateContext(values),
                "/sling/browser/view/display/" + type.name().toLowerCase() + ".html");
        final Reader content = browser().templateReader(template);
        if (content != null) {
            return new Result<>(content, HTML_TYPE);
        }
        return null;
    }

    /**
     * The URI path of the target resource of the given resource, optionally with an extension.
     *
     * @param resource  the resource to show
     * @param extension the extension to append, can be blank
     * @return the path of the target resource with the extension
     */
    protected @NotNull String getTargetUri(@NotNull Resource resource, @Nullable String extension) {
        resource = getTargetResource(resource);
        StringBuilder result = new StringBuilder();
        result.append(resource.getPath());
        if (StringUtils.isNotBlank(extension)) {
            result.append('.').append(extension);
        }
        return result.toString();
    }

    /**
     * The resource to display; the parent for a 'jcr:content' resource, otherwise the resource itself.
     *
     * @param resource the requested resource
     * @return the resource to display
     */
    protected @NotNull Resource getTargetResource(@NotNull Resource resource) {
        if (JCR_CONTENT.equals(resource.getName())) {
            resource = Optional.ofNullable(resource.getParent()).orElse(resource);
        }
        return resource;
    }

    /**
     * The content stream of the original of the resource as UTF-8 text.
     *
     * @param resource the resource
     * @return the content reader, or {@code null} if there is no content
     */
    protected @Nullable Reader getContent(@NotNull Resource resource) {
        return Optional.ofNullable(platformConfig().originalOf(resource))
                .map(r -> r.getValueMap().get(JCR_DATA, InputStream.class))
                .map(stream -> new InputStreamReader(stream, StandardCharsets.UTF_8))
                .orElse(null);
    }

    /**
     * Determines how a resource is displayed, by file type (MIME type or extension) or resource type.
     *
     * @param resource the resource
     * @return the display type
     */
    protected @NotNull Type getDisplayType(@NotNull final Resource resource) {
        final String primaryType = resource.getValueMap().get(JCR_PRIMARY_TYPE, "");

        if (platformConfig().fileTypes().contains(primaryType)) {
            final String mimeType = getMimeType(resource);
            if (StringUtils.isNotBlank(mimeType)) {
                for (String pattern : new String[]{
                        mimeType,
                        StringUtils.substringBefore(mimeType, "/"),
                        StringUtils.substringAfter(mimeType, "/")
                }) {
                    if (!"text".equals(pattern)) {
                        Type type = TYPETABLE.get(pattern);
                        if (type != null) {
                            return type;
                        }
                    }
                }
            }
            final String extension = getExtension(resource);
            if (StringUtils.isNotBlank(extension)) {
                Type type = TYPETABLE.get(extension);
                if (type != null) {
                    return type;
                }
            }
            return Type.BINARY;
        }

        String resourceType = getResourceType(resource);
        if (StringUtils.isNotBlank(resourceType)) {
            return Type.PREVIEW;
        }

        return Type.UNKNOWN;
    }

    /**
     * The 'sling:resourceType' of the resource or of its 'jcr:content' child.
     *
     * @param resource the resource
     * @return the resource type, or {@code null} if there is none
     */
    protected @Nullable String getResourceType(@NotNull Resource resource) {
        final ValueMap values = resource.getValueMap();
        String resourceType = values.get(SLING_RESOURCE_TYPE, String.class);
        if (StringUtils.isBlank(resourceType) && !JCR_CONTENT.equals(resource.getName())) {
            final Resource content = resource.getChild(JCR_CONTENT);
            if (content != null) {
                resourceType = content.getValueMap().get(SLING_RESOURCE_TYPE, String.class);
            }
        }
        return resourceType;
    }

    /**
     * The MIME type of the resource or of its original.
     *
     * @param resource the resource
     * @return the MIME type, or {@code null} if there is none
     */
    protected @Nullable String getMimeType(@NotNull Resource resource) {
        String mimeType = resource.getValueMap().get(JCR_MIME_TYPE, String.class);
        if (StringUtils.isBlank(mimeType)) {
            resource = platformConfig().originalOf(resource);
            if (resource != null) {
                mimeType = resource.getValueMap().get(JCR_MIME_TYPE, String.class);
            }
        }
        return mimeType;
    }

    /**
     * The file name extension of the resource (or of the parent of a 'jcr:content' resource).
     *
     * @param resource the resource
     * @return the extension, or {@code null} if there is no resource
     */
    protected @Nullable String getExtension(@NotNull Resource resource) {
        if (JCR_CONTENT.equals(resource.getName())) {
            resource = resource.getParent();
        }
        return resource != null ? StringUtils.substringAfterLast(resource.getName(), ".") : null;
    }

    /** the display types by MIME type, MIME main type, MIME subtype and file name extension */
    protected static final Map<String, Type> TYPETABLE = new HashMap<>() {{
        put("application/pdf", Type.DOCUMENT);
        put("pdf", Type.DOCUMENT);
        put("application/json", Type.DOCUMENT);
        put("json", Type.DOCUMENT);
        put("text/html", Type.CODE);
        put("html", Type.CODE);
        put("htm", Type.CODE);
        put("xhtml", Type.CODE);
        put("text/xml", Type.CODE);
        put("xml", Type.CODE);
        put("text", Type.TEXT);
        put("txt", Type.TEXT);
        put("csv", Type.TEXT);
        put("tsv", Type.TEXT);
        put("log", Type.TEXT);
        put("image", Type.IMAGE);
        put("avif", Type.IMAGE);
        put("webp", Type.IMAGE);
        put("png", Type.IMAGE);
        put("jpg", Type.IMAGE);
        put("jpeg", Type.IMAGE);
        put("tiff", Type.IMAGE);
        put("tif", Type.IMAGE);
        put("heic", Type.IMAGE);
        put("gif", Type.IMAGE);
        put("svg", Type.IMAGE);
        put("xml+svg", Type.IMAGE);
        put("video", Type.VIDEO);
        put("webm", Type.VIDEO);
        put("mp4", Type.VIDEO);
        put("m4v", Type.VIDEO);
        put("mov", Type.VIDEO);
        put("mpg", Type.VIDEO);
        put("mpeg", Type.VIDEO);
        put("mkv", Type.VIDEO);
        put("wmv", Type.VIDEO);
        put("avi", Type.VIDEO);
        put("c", Type.CODE);
        put("cc", Type.CODE);
        put("cfg", Type.CODE);
        put("clj", Type.CODE);
        put("conf", Type.CODE);
        put("config", Type.CODE);
        put("cpp", Type.CODE);
        put("cs", Type.CODE);
        put("css", Type.CODE);
        put("d", Type.CODE);
        put("dart", Type.CODE);
        put("diff", Type.CODE);
        put("e", Type.CODE);
        put("ecma", Type.CODE);
        put("esp", Type.CODE);
        put("ftl", Type.CODE);
        put("groovy", Type.CODE);
        put("gvy", Type.CODE);
        put("h", Type.CODE);
        put("handlebars", Type.CODE);
        put("hbs", Type.CODE);
        put("hh", Type.CODE);
        put("java", Type.CODE);
        put("javascript", Type.CODE);
        put("js", Type.CODE);
        put("jsf", Type.CODE);
        put("jsp", Type.CODE);
        put("jspf", Type.CODE);
        put("jspx", Type.CODE);
        put("kt", Type.CODE);
        put("less", Type.CODE);
        put("m", Type.CODE);
        put("markdown", Type.CODE);
        put("md", Type.CODE);
        put("mm", Type.CODE);
        put("patch", Type.CODE);
        put("php", Type.CODE);
        put("pl", Type.CODE);
        put("properties", Type.CODE);
        put("py", Type.CODE);
        put("rb", Type.CODE);
        put("rs", Type.CODE);
        put("ru", Type.CODE);
        put("ruby", Type.CODE);
        put("sass", Type.CODE);
        put("scala", Type.CODE);
        put("scss", Type.CODE);
        put("sh", Type.CODE);
        put("sql", Type.CODE);
    }};

    /**
     * Opens the binary content of the requested resource for delivery.
     *
     * @param request  the current request
     * @param mimeType the content type to deliver, or {@code null} to use the 'jcr:mimeType' of the content
     * @return the content stream result, or 'Not Found' if there is no content
     */
    protected Result<InputStream> openContent(@NotNull final SlingHttpServletRequest request,
                                              @Nullable final String mimeType) {
        Result<InputStream> result = new Result<>(SC_NOT_FOUND);
        Resource resource = browser().manager.requestResource(request);
        if (resource != null) {
            String filename = platformConfig().nameOf(resource);
            resource = platformConfig().originalOf(resource);
            if (resource != null) {
                ValueMap values = resource.getValueMap();
                InputStream stream = values.get(JCR_DATA, InputStream.class);
                if (stream != null) {
                    result = new Result<>(stream);
                    if (StringUtils.isNotBlank(mimeType)) {
                        result.setContentType(mimeType);
                    } else {
                        String jcrMimeType = values.get(JCR_MIME_TYPE, String.class);
                        if (StringUtils.isNotBlank(jcrMimeType)) {
                            result.setContentType(jcrMimeType);
                        }
                    }
                    String disposition = "inline";
                    if (StringUtils.isNotBlank(filename)) {
                        disposition += "; filename=" + filename;
                    }
                    result.setHeader(HTTP_CONTENT_DISPOSITION, disposition);
                    Calendar lastModified = values.get(JCR_LAST_MODIFIED, Calendar.class);
                    if (lastModified != null) {
                        result.setHeader(HTTP_LAST_MODIFIED, lastModified.getTime());
                    }
                }
            }
        }
        return result;
    }
}
