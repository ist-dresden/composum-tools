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
import static com.composum.sling.tools.Common.JCR_MIME_TYPE;
import static com.composum.sling.tools.Common.JCR_PRIMARY_TYPE;
import static com.composum.sling.tools.Common.SLING_RESOURCE_TYPE;
import static com.composum.sling.tools.Common.TEXT_TYPE;
import static com.composum.sling.tools.Common.urlQueryOf;
import static javax.servlet.http.HttpServletResponse.SC_NOT_FOUND;
import static javax.servlet.http.HttpServletResponse.SC_OK;

@Component(service = {View.class, DisplayView.class}, immediate = true)
@Designate(ocd = DisplayView.Config.class)
public class DisplayView extends AbstractView {

    public static final String KEY = "display";

    @ObjectClassDefinition(name = "Composum Browser Display View")
    public @interface Config {

        @AttributeDefinition()
        String key() default DisplayView.KEY;

        @AttributeDefinition()
        String label() default "Preview";

        @AttributeDefinition()
        int rank() default 6000;
    }

    @Reference
    private PlatformConfig platformConfig;

    @Reference
    private Browser browser;

    protected BundleContext bundleContext;
    protected Config config;

    @Activate
    @Modified
    protected void activate(final BundleContext bundleContext, final Config config) {
        this.bundleContext = bundleContext;
        this.config = config;
        browser.views().attach(this);
    }

    @Deactivate
    protected void deactivate() {
        browser.views().detach(this);
    }

    @Override
    public Browser browser() {
        return browser;
    }

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

    protected Result<?> params(@NotNull final SlingHttpServletRequest request,
                               @NotNull final SlingHttpServletResponse response) {
        return new Result<>(SC_OK);
    }

    protected enum Type {PREVIEW, TEXT, CODE, IMAGE, VIDEO, DOCUMENT, BINARY, UNKNOWN}

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

    protected @NotNull String getTargetUri(@NotNull Resource resource, @Nullable String extension) {
        resource = getTargetResource(resource);
        StringBuilder result = new StringBuilder();
        result.append(resource.getPath());
        if (StringUtils.isNotBlank(extension)) {
            result.append('.').append(extension);
        }
        return result.toString();
    }

    protected @NotNull Resource getTargetResource(@NotNull Resource resource) {
        if (JCR_CONTENT.equals(resource.getName())) {
            resource = Optional.ofNullable(resource.getParent()).orElse(resource);
        }
        return resource;
    }

    protected @Nullable Reader getContent(@NotNull Resource resource) {
        return Optional.ofNullable(platformConfig().originalOf(resource))
                .map(r -> r.getValueMap().get(JCR_DATA, InputStream.class))
                .map(stream -> new InputStreamReader(stream, StandardCharsets.UTF_8))
                .orElse(null);
    }

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

    protected @Nullable String getExtension(@NotNull Resource resource) {
        if (JCR_CONTENT.equals(resource.getName())) {
            resource = resource.getParent();
        }
        return resource != null ? StringUtils.substringAfterLast(resource.getName(), ".") : null;
    }

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
                    Calendar lastModified = values.get("jcr:lastModified", Calendar.class);
                    if (lastModified != null) {
                        result.setHeader(HTTP_LAST_MODIFIED, lastModified.getTime());
                    }
                }
            }
        }
        return result;
    }
}
