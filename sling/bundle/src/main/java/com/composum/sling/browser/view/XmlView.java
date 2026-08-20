package com.composum.sling.browser.view;

import com.composum.sling.browser.Browser;
import com.composum.sling.browser.View;
import com.composum.sling.tools.Common;
import com.composum.sling.tools.Manager;
import com.composum.sling.tools.Result;
import com.composum.sling.tools.template.Template;
import com.composum.sling.tools.template.TemplateContext;
import com.composum.sling.tools.template.TemplateContext.Values;
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

import javax.jcr.RepositoryException;
import javax.jcr.Session;
import java.io.InputStream;
import java.io.Reader;
import java.io.StringReader;
import java.math.BigDecimal;
import java.text.SimpleDateFormat;
import java.util.Arrays;
import java.util.Calendar;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

import static com.composum.sling.tools.Common.HTML_TYPE;
import static com.composum.sling.tools.Common.JCR_CONTENT;
import static com.composum.sling.tools.Common.JCR_MIXIN_TYPES;
import static com.composum.sling.tools.Common.JCR_PRIMARY_TYPE;
import static com.composum.sling.tools.Common.NT_FILE;
import static javax.servlet.http.HttpServletResponse.SC_NOT_FOUND;

@Component(service = {View.class, XmlView.class}, immediate = true)
@Designate(ocd = XmlView.Config.class)
public class XmlView extends AbstractSourceView {

    public static final String KEY = "xml";

    protected static final String XML_TYPE = "text/xml;charset=utf-8";
    protected static final String INDENT = "  ";

    @ObjectClassDefinition(name = "Composum Browser XML View")
    public @interface Config {

        @AttributeDefinition()
        String key() default XmlView.KEY;

        @AttributeDefinition()
        String label() default "XML";

        @AttributeDefinition()
        int rank() default 3500;

        @AttributeDefinition(name = "Max Depth")
        int maxDepth() default 0;

        @AttributeDefinition(name = "Source Mode",
                description = "hides technical noise properties (jcr:uuid, jcr:created, ...); can be " +
                        "switched off per request with the 'raw' request parameter")
        boolean sourceMode() default true;

        @AttributeDefinition(name = "Non Source Properties",
                description = "property name patterns hidden in source mode")
        String[] nonSourceProperties() default {
                "^jcr:(uuid|data)$",
                "^jcr:(baseVersion|predecessors|versionHistory|isCheckedOut)$",
                "^jcr:(created|lastModified).*$",
                "^cq:last(Modified|Replicat).*$"
        };

        @AttributeDefinition(name = "Non Source Mixins",
                description = "mixin type patterns hidden in source mode")
        String[] nonSourceMixins() default {
                "^rep:AccessControllable$"
        };
    }

    @Reference
    protected Browser browser;

    protected Config config;

    @Activate
    @Modified
    protected void activate(final BundleContext bundleContext, final Config config) {
        this.config = config;
        activateSourceMode(bundleContext, config.maxDepth(), config.sourceMode(),
                config.nonSourceProperties(), config.nonSourceMixins());
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

    @Override
    public @NotNull String key() {
        return Optional.ofNullable(config).map(Config::key).orElse(KEY);
    }

    @Override
    public @NotNull String label() {
        return Optional.ofNullable(config).map(Config::label).orElse("XML");
    }

    @Override
    public int rank() {
        return Optional.ofNullable(config).map(Config::rank).orElse(3500);
    }

    @Override
    public @NotNull Result<?> process(@NotNull final SlingHttpServletRequest request,
                                      @NotNull final SlingHttpServletResponse response,
                                      @NotNull final List<String> selectors) {
        Result<?> result = new Result<>(SC_NOT_FOUND);
        switch (Manager.consume(selectors, "")) {
            case "resource":
                result = browser.resource(request);
                break;
            case "form":
                result = params(request, response);
                break;
            default: {
                final Resource resource = browser.manager().requestResource(request);
                if (resource != null) {
                    final Integer depth = getIntParameter(request, "depth", maxDepth);
                    final boolean raw = getBooleanParameter(request, "raw", false);
                    final boolean source = !raw && sourceModeSupport;
                    final StringBuilder xml = new StringBuilder();
                    dumpXml(xml, "", resource, 0, depth, source);
                    final Reader content = browser.templateReader(getTemplate(new TemplateContext(
                            new Values()
                                    .with("content", new StringReader(xml.toString()))
                                    .with("option", new Values()
                                            .with("wrap", true)
                                    )), "view"));
                    if (content != null) {
                        result = new Result<>(content, HTML_TYPE);
                    }
                }
            }
            break;
        }
        return result;
    }

    public final Map<String, Factory> templates = Map.of(
            "view", current ->
                    new Template("/sling/browser/view/display/code.html",
                            new TemplateContext(current, new Values()), this)
    );

    @Override
    public @Nullable Template getTemplate(@NotNull TemplateContext context, @NotNull String key) {
        return Optional.ofNullable(templates.get(key))
                .map(factory -> factory.create(context))
                .orElse(key.startsWith("/") ? new Template(key, context, this) : null);
    }

    // docview XML dump (JCR System View / '.content.xml' attribute encoding)

    protected void dumpXml(@NotNull final StringBuilder writer, @NotNull final String indent,
                           @NotNull final Resource resource, final int depth, @Nullable final Integer maxDepth,
                           final boolean sourceMode) {
        final String name = resource.getName();
        if (depth == 0) {
            final Set<String> namespaces = new TreeSet<>();
            namespaces.add("jcr");
            determineNamespaces(namespaces, resource, depth, maxDepth, sourceMode);
            writer.append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n");
            writer.append("<jcr:root");
            writeNamespaceAttributes(resource.getResourceResolver(), writer, namespaces);
        } else {
            writer.append(indent).append("<").append(xmlName(name));
        }
        xmlProperties(writer, indent + INDENT + INDENT, resource, sourceMode);
        writer.append(">\n");
        Integer childMaxDepth = maxDepth;
        if (sourceMode && (JCR_CONTENT.equals(name) || resource.getPath().contains("/" + JCR_CONTENT + "/"))) {
            childMaxDepth = null;
        }
        final Resource content = resource.getChild(JCR_CONTENT);
        if (content != null && browser.manager().isAllowedResource(content)) {
            if (sourceMode || childMaxDepth == null || depth < childMaxDepth) {
                dumpXml(writer, indent + INDENT, content, depth + 1, childMaxDepth, sourceMode);
            }
        }
        if (childMaxDepth == null || depth < childMaxDepth) {
            for (final Resource child : resource.getChildren()) {
                if (browser.manager().isAllowedResource(child) && (!sourceMode ||
                        !(depth > 0 && NT_FILE.equals(child.getValueMap().get(JCR_PRIMARY_TYPE, ""))))) {
                    final String childName = child.getName();
                    if (!JCR_CONTENT.equals(childName)) {
                        dumpXml(writer, indent + INDENT, child, depth + 1, childMaxDepth, sourceMode);
                    }
                }
            }
        }
        if (depth == 0) {
            writer.append("</jcr:root>\n");
        } else {
            writer.append(indent).append("</").append(xmlName(name)).append(">\n");
        }
    }

    protected void xmlProperties(@NotNull final StringBuilder writer, @NotNull final String indent,
                                 @NotNull final Resource resource, final boolean sourceMode) {
        final Map<String, Object> sorted = new TreeMap<>(PROPERTY_NAME_COMPARATOR);
        for (final Map.Entry<String, Object> property : resource.getValueMap().entrySet()) {
            final String name = property.getKey();
            final Object value = property.getValue();
            if (!isAllowedProperty(name, sourceMode)) {
                continue;
            }
            if (JCR_PRIMARY_TYPE.equals(name)) {
                xmlProperty(writer, indent, name, value);
            } else if (sourceMode && JCR_MIXIN_TYPES.equals(name)) {
                final String[] mixins = filterMixins(value instanceof String[] ? (String[]) value : null);
                if (mixins != null && mixins.length > 0) {
                    Arrays.sort(mixins);
                    sorted.put(name, mixins);
                }
            } else if (value instanceof InputStream) {
                sorted.put(name, "<binary>");
            } else if (value != null) {
                sorted.put(name, value);
            }
        }
        for (final Map.Entry<String, Object> property : sorted.entrySet()) {
            xmlProperty(writer, indent, property.getKey(), property.getValue());
        }
    }

    protected void xmlProperty(@NotNull final StringBuilder writer, @NotNull final String indent,
                               @NotNull final String name, @Nullable final Object value) {
        writer.append("\n").append(indent).append(xmlName(name)).append("=\"").append(xmlType(value));
        xmlValue(writer, value);
        writer.append("\"");
    }

    protected @NotNull String xmlType(@Nullable final Object value) {
        if (value instanceof Object[]) {
            final Object[] values = (Object[]) value;
            return xmlType(values.length > 0 ? values[0] : null);
        } else if (value instanceof Calendar || value instanceof Date) {
            return "{Date}";
        } else if (value instanceof Boolean) {
            return "{Boolean}";
        } else if (value instanceof Long || value instanceof Integer) {
            return "{Long}";
        } else if (value instanceof Double || value instanceof Float) {
            return "{Double}";
        } else if (value instanceof BigDecimal) {
            return "{Decimal}";
        }
        return "";
    }

    protected void xmlValue(@NotNull final StringBuilder writer, @Nullable final Object value) {
        if (value != null) {
            if (value instanceof Object[]) {
                xmlArray(writer, (Object[]) value);
            } else {
                String string = xmlString(value);
                if (string.startsWith("[") || string.startsWith("{")) {
                    string = "\\" + string;
                }
                writer.append(string);
            }
        }
    }

    protected void xmlArray(@NotNull final StringBuilder writer, @NotNull final Object[] values) {
        writer.append("[");
        for (int i = 0; i < values.length; ) {
            writer.append(xmlString(values[i]).replace(",", "\\,"));
            if (++i < values.length) {
                writer.append(",");
            }
        }
        writer.append("]");
    }

    protected @NotNull String xmlString(@Nullable final Object value) {
        if (value instanceof Calendar) {
            return xmlString(new SimpleDateFormat(Common.XML_DATE_FORMAT).format(((Calendar) value).getTime()));
        } else if (value instanceof Date) {
            return xmlString(new SimpleDateFormat(Common.XML_DATE_FORMAT).format((Date) value));
        }
        return value != null ? xmlString(value.toString()) : "";
    }

    protected @NotNull String xmlString(@NotNull final String value) {
        return value.replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace("\"", "&quot;")
                .replace("\t", "&#x9;")
                .replace("\n", "&#xa;")
                .replace("\r", "&#xd;")
                .replace("\\", "\\\\");
    }

    /**
     * a dependency-free, simplified stand-in for {@code org.apache.jackrabbit.util.ISO9075.encode(...)}:
     * only guards against the most common real-world case (a local name starting with a digit); JCR names
     * using characters otherwise illegal in an XML Name are not fully re-encoded
     */
    protected @NotNull String xmlName(@NotNull final String name) {
        String escaped = name.replaceAll("\\s+", "_");
        final int colon = escaped.indexOf(':');
        final String local = colon >= 0 ? escaped.substring(colon + 1) : escaped;
        if (local.isEmpty() || !(Character.isLetter(local.charAt(0)) || local.charAt(0) == '_')) {
            escaped = escaped.substring(0, colon + 1) + "_" + local;
        }
        return escaped;
    }

    protected void determineNamespaces(@NotNull final Set<String> keys, @NotNull final Resource resource,
                                       final int depth, @Nullable final Integer maxDepth, final boolean sourceMode) {
        final String name = resource.getName();
        extractNamespace(keys, name);
        for (final Map.Entry<String, Object> entry : resource.getValueMap().entrySet()) {
            final String propertyName = entry.getKey();
            if (isAllowedProperty(propertyName, sourceMode)) {
                extractNamespace(keys, propertyName);
                if (JCR_PRIMARY_TYPE.equals(propertyName)) {
                    extractNamespace(keys, entry.getValue());
                } else if (JCR_MIXIN_TYPES.equals(propertyName)) {
                    extractNamespace(keys, sourceMode
                            ? filterMixins(entry.getValue() instanceof String[] ? (String[]) entry.getValue() : null)
                            : entry.getValue());
                }
            }
        }
        Integer childMaxDepth = maxDepth;
        if (sourceMode && (JCR_CONTENT.equals(name) || resource.getPath().contains("/" + JCR_CONTENT + "/"))) {
            childMaxDepth = null;
        }
        final Resource content = resource.getChild(JCR_CONTENT);
        if (content != null && browser.manager().isAllowedResource(content)) {
            if (sourceMode || childMaxDepth == null || depth < childMaxDepth) {
                determineNamespaces(keys, content, depth + 1, childMaxDepth, sourceMode);
            }
        }
        if (childMaxDepth == null || depth < childMaxDepth) {
            for (final Resource child : resource.getChildren()) {
                if (browser.manager().isAllowedResource(child) && (!sourceMode ||
                        !(depth > 0 && NT_FILE.equals(child.getValueMap().get(JCR_PRIMARY_TYPE, ""))))) {
                    final String childName = child.getName();
                    if (!JCR_CONTENT.equals(childName)) {
                        determineNamespaces(keys, child, depth + 1, childMaxDepth, sourceMode);
                    }
                }
            }
        }
    }

    protected void extractNamespace(@NotNull final Set<String> keys, final Object... values) {
        if (values != null && values.length > 0) {
            Object[] items = values;
            if (values.length == 1 && values[0] instanceof Object[]) {
                items = (Object[]) values[0];
            }
            for (final Object value : items) {
                if (value instanceof String) {
                    final String string = (String) value;
                    if (StringUtils.isNotBlank(string) && string.contains(":")) {
                        keys.add(StringUtils.substringBefore(string, ":"));
                    }
                }
            }
        }
    }

    protected void writeNamespaceAttributes(@NotNull final ResourceResolver resolver,
                                            @NotNull final StringBuilder writer, @NotNull final Set<String> namespaces) {
        final Session session = resolver.adaptTo(Session.class);
        if (session != null) {
            int index = 0;
            for (final String ns : namespaces) {
                try {
                    final String nsUri = session.getNamespaceURI(ns);
                    if (StringUtils.isNotBlank(nsUri)) {
                        writer.append(" xmlns:").append(ns).append("=\"").append(nsUri).append("\"");
                        if (++index < namespaces.size()) {
                            writer.append("\n         ");
                        }
                    }
                } catch (final RepositoryException ignore) {
                }
            }
        }
    }
}
