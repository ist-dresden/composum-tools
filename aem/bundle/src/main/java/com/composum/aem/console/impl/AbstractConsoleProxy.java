package com.composum.aem.console.impl;

import com.composum.aem.console.ConsoleProxy;
import com.composum.sling.tools.Result;
import com.composum.sling.tools.template.TemplateContext;
import com.composum.sling.tools.template.TemplateContext.Values;
import org.apache.commons.lang3.StringUtils;
import org.apache.sling.api.SlingHttpServletRequest;
import org.apache.sling.api.SlingHttpServletResponse;
import org.apache.sling.api.wrappers.SlingHttpServletResponseWrapper;
import org.ccil.cowan.tagsoup.Parser;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.osgi.framework.BundleContext;
import org.osgi.framework.InvalidSyntaxException;
import org.osgi.framework.ServiceReference;
import org.xml.sax.Attributes;
import org.xml.sax.ContentHandler;
import org.xml.sax.InputSource;
import org.xml.sax.SAXException;
import org.xml.sax.XMLReader;
import org.xml.sax.helpers.AttributesImpl;
import org.xml.sax.helpers.XMLFilterImpl;

import javax.servlet.Servlet;
import javax.servlet.ServletOutputStream;
import javax.servlet.WriteListener;
import javax.servlet.http.HttpServletResponse;
import javax.xml.transform.OutputKeys;
import javax.xml.transform.Transformer;
import javax.xml.transform.sax.SAXTransformerFactory;
import javax.xml.transform.sax.TransformerHandler;
import javax.xml.transform.stream.StreamResult;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.PipedInputStream;
import java.io.PipedOutputStream;
import java.io.PipedReader;
import java.io.PipedWriter;
import java.io.PrintWriter;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.Collection;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

import static com.composum.sling.tools.Common.HTML_TYPE;
import static com.composum.sling.tools.Common.HTTP_LOCATION;
import static javax.servlet.http.HttpServletResponse.SC_MOVED_TEMPORARILY;

/**
 * Base class for a {@link ConsoleProxy} that embeds one Felix Web Console plugin into the Composum
 * {@link Console} page. A concrete subclass is a small OSGi component: it declares its own
 * {@code @ObjectClassDefinition Config} (key/label/rank), forwards {@code @Activate} to
 * {@link #activate(BundleContext, String, String, int)}, names the Felix plugin it proxies via
 * {@link #webConsoleLabel()}, and supplies a {@link #pageTitle()}. Everything else - looking up the
 * plugin's servlet, calling it, and rewriting its HTML response - is handled here.
 * <p>
 * {@link #proxy} does the actual proxying: it calls the plugin's servlet on one daemon thread, feeds
 * its raw output through a SAX pipeline on a second daemon thread, and returns a {@link Reader} for
 * the transformed result - the two threads are chained via pipes so a single caller reading that
 * {@code Reader} drives the whole pipeline without risking a pipe deadlock on the calling thread. The
 * SAX pipeline, built by {@link #transform}, runs in this order:
 * <ol>
 * <li>{@link FragmentFilter} - drops the synthetic {@code <html>}/{@code <head>}/{@code <body>}
 * wrapper TagSoup adds around what is really just an HTML fragment,</li>
 * <li>{@link ExclusionFilter} - drops whole elements (and their subtree) per {@link #isExcludedElement}
 * (by default {@code <link>}/{@code <script>}, since our own page template provides those),</li>
 * <li>{@link #createTransformingHandler} - by default {@link ProxyContentHandler}, which rewrites the
 * remaining {@code src}/{@code href} attributes and inline {@code <script>} content so they keep
 * pointing through this proxy instead of the plugin's original console (see
 * {@link #rewriteResourceLink}, {@link #rewriteContentLink}, {@link #rewriteScriptContent}).</li>
 * </ol>
 */
public abstract class AbstractConsoleProxy implements ConsoleProxy {

    /**
     * Default constructor.
     */
    protected AbstractConsoleProxy() {
    }

    /** size (in characters/bytes) of the pipes chaining the proxy's background threads, see {@link #proxy} */
    protected static final int PIPE_BUFFER_SIZE = 8192;

    /**
     * The console this proxy is embedded in.
     *
     * @return the console this proxy is embedded in
     */
    public abstract Console console();

    @Override
    public boolean isEnabled() {
        return true;
    }

    /**
     * Reference for {@link #consoleServlet}.
     */
    protected transient ServiceReference<Servlet> consoleServletRef;

    /**
     * The console servlet we proxy for.
     */
    protected transient Servlet consoleServlet;

    /** the bundle context this proxy was activated with */
    protected BundleContext bundleContext;

    /**
     * The {@code felix.webconsole.label} of the Felix Web Console plugin this proxy embeds, used to
     * look up its servlet (see {@link #initConsoleServlet}).
     *
     * @return the proxied plugin's Felix Web Console label
     */
    protected abstract @NotNull String webConsoleLabel();

    /**
     * Backing fields for {@link #key()}, {@link #label()} and {@link #rank()}, set once via
     * {@link #activate(BundleContext, String, String, int)}.
     */
    protected String key;
    /** backing field for {@link #label()} */
    protected String label;
    /** backing field for {@link #rank()} */
    protected int rank;

    /**
     * Stores the bundle context; called by subclasses that don't use the 4-arg
     * {@link #activate(BundleContext, String, String, int)} convenience overload.
     *
     * @param bundleContext the bundle context of the activating component
     */
    protected void activate(final BundleContext bundleContext) {
        this.bundleContext = bundleContext;
    }

    /**
     * Convenience for subclasses: stores the values every {@code ConsoleProxy} config provides
     * ({@link #key()}, {@link #label()}, {@link #rank()}) so they don't each need their own fields
     * and {@code Optional.ofNullable(config)...} boilerplate.
     *
     * @param bundleContext the bundle context of the activating component
     * @param key           this proxy's registration key
     * @param label         this proxy's navigation label
     * @param rank          this proxy's navigation rank
     */
    protected void activate(final BundleContext bundleContext, @NotNull final String key,
                            @NotNull final String label, final int rank) {
        activate(bundleContext);
        this.key = key;
        this.label = label;
        this.rank = rank;
    }

    /**
     * Releases the bound {@link #consoleServlet} service reference, if any.
     */
    protected void deactivate() {
        this.consoleServlet = null;
        ServiceReference<Servlet> ref = this.consoleServletRef;
        this.consoleServletRef = null;
        if (ref != null) {
            bundleContext.ungetService(ref);
        }
    }

    @Override
    public @NotNull String key() {
        return key;
    }

    @Override
    public @NotNull String label() {
        return label;
    }

    @Override
    public int rank() {
        return rank;
    }

    /**
     * The proxied plugin's servlet, looked up lazily (and cached) via {@link #initConsoleServlet}.
     *
     * @return the proxied plugin's servlet, or {@code null} if it is not currently registered
     */
    protected Servlet getConsoleServlet() {
        if (consoleServlet == null) {
            initConsoleServlet();
        }
        return consoleServlet;
    }

    /**
     * Looks up the OSGi {@link Servlet} service registered as the Felix Web Console plugin named by
     * {@link #webConsoleLabel()} (i.e. one whose {@code felix.webconsole.label} service property
     * matches), and binds it as {@link #consoleServlet}. Leaves {@link #consoleServlet} {@code null}
     * if no such plugin is currently registered.
     */
    protected void initConsoleServlet() {
        try {
            Collection<ServiceReference<Servlet>> candidates = bundleContext.getServiceReferences(Servlet.class,
                    "(felix.webconsole.label=" + webConsoleLabel() + ")");
            if (!candidates.isEmpty()) {
                this.consoleServletRef = candidates.iterator().next();
                this.consoleServlet = bundleContext.getService(consoleServletRef);
            }
        } catch (InvalidSyntaxException ignore) {
        }
    }

    /**
     * Dispatches to {@link #processPost} for POST and {@link #processGet} for GET; any other method
     * yields a 400 response.
     */
    @Override
    public @NotNull Result<?> process(@NotNull final SlingHttpServletRequest request,
                                      @NotNull final SlingHttpServletResponse response,
                                      @NotNull final List<String> selectors) {
        switch (request.getMethod()) {
            case "POST":
                return processPost(request, response, selectors);
            case "GET":
                return processGet(request, response, selectors);
        }
        return new Result<>(HttpServletResponse.SC_BAD_REQUEST);
    }

    /**
     * Renders the proxy's page template with {@link #pageTitle()} / {@link #pageStyles()} and the
     * proxied console content (see {@link #proxy}) as its {@code content} value.
     *
     * @param request   the current request
     * @param response  the current response
     * @param selectors the request selectors remaining after routing
     * @return the rendered page, or a 'Not Found' result if the page template cannot be rendered
     */
    protected @NotNull Result<?> processGet(@NotNull final SlingHttpServletRequest request,
                                            @NotNull final SlingHttpServletResponse response,
                                            @NotNull final List<String> selectors) {
        final Reader content = console().templateReader(console().getTemplate(new TemplateContext(new Values()
                .with("page", new Values()
                        .with("link", pageLink())
                        .with("label", label())
                        .with("title", pageTitle())
                        .with("styles", pageStyles()))
                .with("content", (Supplier<Reader>) () -> proxy(request, response))
                .with("html.cssClasses", (Supplier<?>) () -> console().getHtmlCssClasses(key() + "-page"))
        ), "page"));
        return content != null
                ? new Result<>(content, HTML_TYPE)
                : new Result<>(HttpServletResponse.SC_NOT_FOUND);
    }

    /**
     * Handles a POST by calling the proxied plugin's servlet (via {@link #proxy}). What happens with
     * the plugin's (transformed) response depends on {@link #redirectAfterPost()}:
     * <ul>
     * <li>non-blank (the default) - a regular browser form submission, where the plugin's response is
     * only needed to run its side effect (e.g. Felix's "Recent Requests" plugin clearing its log); it
     * is read through to completion (see below) and discarded, and a redirect to that URL is returned
     * instead - the usual Post/Redirect/Get pattern, avoiding a resubmission on browser refresh;</li>
     * <li>blank/{@code null} - the POST is driven by client-side {@code fetch()} and the caller needs
     * the resulting content directly (the full proxied/transformed chain), so it is returned as this
     * call's own result instead, exactly like {@link #processGet} would.</li>
     * </ul>
     * The plugin call happens on background threads (see {@link #proxy}) that only make progress
     * while their output is drained; simply closing an unread result early can leave those threads
     * blocked forever on a full, unread pipe once the plugin's response exceeds
     * {@link #PIPE_BUFFER_SIZE} - so the discard branch always reads {@code content} through to its
     * end (via {@link #drain}) rather than just closing it, which also ensures the plugin's side
     * effect has actually finished before the redirect is issued.
     *
     * @param request   the current request
     * @param response  the current response
     * @param selectors the request selectors remaining after routing
     * @return the proxied/transformed content, a redirect, or a 'Not Found'/'Internal Server Error' result
     */
    protected @NotNull Result<?> processPost(@NotNull final SlingHttpServletRequest request,
                                             @NotNull final SlingHttpServletResponse response,
                                             @NotNull final List<String> selectors) {
        final Reader content = proxy(request, response);
        if (content == null) {
            return new Result<>(HttpServletResponse.SC_NOT_FOUND);
        }
        final String redirectUri = redirectAfterPost();
        if (StringUtils.isBlank(redirectUri)) {
            return new Result<>(content, HTML_TYPE);
        }
        try (content) {
            drain(content);
            return new Result<>(SC_MOVED_TEMPORARILY, Map.of(
                    HTTP_LOCATION, redirectUri
            ), null);
        } catch (IOException ignore) {
        }
        return new Result<>(HttpServletResponse.SC_INTERNAL_SERVER_ERROR);
    }

    /**
     * Reads {@code reader} through to its end, discarding the content. Used by {@link #processPost}
     * to let the background-driven pipeline behind {@link #proxy} run to completion without actually
     * keeping its result.
     *
     * @param reader the reader to read and discard
     * @throws IOException if reading fails
     */
    @SuppressWarnings("StatementWithEmptyBody")
    protected void drain(@NotNull final Reader reader) throws IOException {
        final char[] buffer = new char[1024];
        while (reader.read(buffer) >= 0) {
            // discard
        }
    }

    /**
     * The URL to redirect to after a successful POST (see {@link #processPost}), following the
     * {@code <serverPath>.console.<key>.html} convention used throughout this console (see e.g.
     * {@link Console}'s {@code WIDGETS} and {@code AbstractConsoleProxy}'s link rewriting). Override
     * to redirect elsewhere, or return a blank/{@code null} value if this proxy's POST is driven by
     * client-side {@code fetch()} and needs the proxied response content directly instead of a
     * redirect (see {@link #processPost}).
     *
     * @return the redirect target, or a blank/{@code null} value to return the proxied content directly
     */
    protected @Nullable String redirectAfterPost() {
        return pageLink();
    }

    /**
     * This proxy's own page URL.
     *
     * @return this proxy's own page URL, following the {@code <serverPath>.console.<key>.html} convention
     */
    protected @NotNull String pageLink() {
        return console().manager.serverPath() + ".console." + key() + ".html";
    }

    /**
     * The page title shown above the proxied content (e.g. "Recent Requests"), distinct from the
     * (usually shorter) navigation {@link #label()}.
     *
     * @return this proxy's page title
     */
    protected abstract @NotNull String pageTitle();

    /**
     * Stylesheets loaded by the page template around the proxied content. Default: a single stylesheet
     * following the convention {@code /aem/console/<key>.css}; override if a proxy needs something else.
     *
     * @return the stylesheet resource paths loaded by this proxy's page
     */
    protected @NotNull List<String> pageStyles() {
        return List.of("/aem/console/" + key() + ".css");
    }

    /**
     * A response wrapper whose output stream/writer feed a pipe instead of the real (Sling) response,
     * so the proxied servlet's raw output can be read (and transformed) on a separate thread; see
     * {@link #proxy}.
     */
    protected static class ProxyResponse extends SlingHttpServletResponseWrapper {

        /** forwards bytes written by the proxied servlet into {@link #pipedOutput} */
        protected class ServletOutput extends ServletOutputStream {

            /**
             * Default constructor.
             */
            protected ServletOutput() {
            }

            @Override
            public boolean isReady() {
                return true;
            }

            @Override
            public void setWriteListener(WriteListener writeListener) {
                try {
                    getSlingResponse().getOutputStream().setWriteListener(writeListener);
                } catch (IOException ignore) {
                }
            }

            @Override
            public void write(int b) throws IOException {
                pipedOutput.write(b);
            }

            @Override
            public void write(byte @NotNull [] b, int off, int len) throws IOException {
                pipedOutput.write(b, off, len);
            }

            @Override
            public void close() throws IOException {
                pipedOutput.close();
            }
        }

        /** the writing end of the pipe fed by {@link #servletOutput}/{@link #proxyWriter} */
        protected final PipedOutputStream pipedOutput;
        /** the reading end of the pipe, drained by the transform thread, see {@link #proxy} */
        protected final PipedInputStream pipedInput;
        /** the output stream returned by {@link #getOutputStream()} */
        protected final ServletOutput servletOutput;
        /** the writer returned by {@link #getWriter()}, wrapping {@link #servletOutput} */
        protected final PrintWriter proxyWriter;

        /**
         * Wraps the given response, setting up the internal pipe.
         *
         * @param wrappedResponse the real response this response wraps
         * @throws IOException if the underlying pipe cannot be created
         */
        public ProxyResponse(@NotNull final SlingHttpServletResponse wrappedResponse) throws IOException {
            super(wrappedResponse);
            pipedOutput = new PipedOutputStream();
            pipedInput = new PipedInputStream(pipedOutput, PIPE_BUFFER_SIZE);
            servletOutput = new ServletOutput();
            proxyWriter = new PrintWriter(new OutputStreamWriter(servletOutput, characterEncoding()));
        }

        /**
         * The wrapped response's character encoding.
         *
         * @return the wrapped response's character encoding, or UTF-8 if none is set
         */
        protected String characterEncoding() {
            final String encoding = getCharacterEncoding();
            return encoding != null ? encoding : StandardCharsets.UTF_8.name();
        }

        @Override
        public ServletOutputStream getOutputStream() {
            return servletOutput;
        }

        @Override
        public PrintWriter getWriter() {
            return proxyWriter;
        }

        /**
         * Signals EOF to the reading side of the pipe, whichever of writer/stream the proxied servlet used.
         */
        public void finish() {
            proxyWriter.flush();
            proxyWriter.close();
            try {
                pipedOutput.close();
            } catch (IOException ignore) {
            }
        }
    }

    /**
     * SAX filter which rewrites the three kinds of URLs a proxied web console page typically embeds
     * for the console it was originally rendered for: {@code appRoot} / {@code pluginRoot} assigned in
     * an inline {@code <script>}, {@code src}/stylesheet {@code href} attributes referencing static
     * resources, and plain {@code <a href="...">} content links (e.g. the request detail links).
     * Each category is rewritten by its own overridable method so subclasses only need to implement
     * the URL mapping, not the SAX plumbing.
     */
    protected class ProxyContentHandler extends XMLFilterImpl {

        /** accumulates the text content of the {@code <script>} element currently being read */
        protected final StringBuilder scriptBuffer = new StringBuilder();
        /** whether a {@code <script>} element is currently being read */
        protected boolean inScript = false;

        /**
         * Wraps the given target content handler.
         *
         * @param target the content handler to forward the (rewritten) SAX events to
         */
        public ProxyContentHandler(@NotNull final ContentHandler target) {
            setContentHandler(target);
        }

        @Override
        public void startElement(String uri, String localName, String qName, Attributes attributes)
                throws SAXException {
            if ("script".equalsIgnoreCase(qName)) {
                inScript = true;
                scriptBuffer.setLength(0);
            }
            super.startElement(uri, localName, qName, rewriteAttributes(qName, attributes));
        }

        @Override
        public void characters(char[] ch, int start, int length) throws SAXException {
            if (inScript) {
                scriptBuffer.append(ch, start, length);
            } else {
                super.characters(ch, start, length);
            }
        }

        @Override
        public void endElement(String uri, String localName, String qName) throws SAXException {
            if ("script".equalsIgnoreCase(qName)) {
                inScript = false;
                final String script = rewriteScriptContent(scriptBuffer.toString());
                super.characters(script.toCharArray(), 0, script.length());
            }
            super.endElement(uri, localName, qName);
        }

        /**
         * Rewrites the {@code src}/link {@code href}/content {@code href} attributes, if present.
         *
         * @param qName      the qualified element name the attributes belong to
         * @param attributes the element's original attributes
         * @return the attributes with any {@code src}/link {@code href}/content {@code href} rewritten
         */
        protected Attributes rewriteAttributes(@NotNull final String qName, @NotNull final Attributes attributes) {
            final AttributesImpl result = new AttributesImpl(attributes);
            for (int i = 0; i < result.getLength(); i++) {
                final String name = result.getLocalName(i);
                if ("src".equalsIgnoreCase(name)
                        || ("href".equalsIgnoreCase(name) && "link".equalsIgnoreCase(qName))) {
                    result.setValue(i, rewriteResourceLink(result.getValue(i)));
                } else if ("href".equalsIgnoreCase(name) && "a".equalsIgnoreCase(qName)) {
                    result.setValue(i, rewriteContentLink(result.getValue(i)));
                }
            }
            return result;
        }
    }

    /**
     * Extension point: builds the SAX content handler chain that transforms the proxied servlet's
     * output before it is serialized again. The default implementation installs
     * {@link ProxyContentHandler}; override to add further transformation steps.
     *
     * @param target the content handler the built chain should ultimately forward events to
     * @return the (possibly chained) content handler to run the proxied output through
     */
    protected @NotNull ContentHandler createTransformingHandler(@NotNull final ContentHandler target) {
        return new ProxyContentHandler(target);
    }

    /**
     * Rewrites a {@code src} attribute (e.g. {@code <script>}, {@code <img>}) or a stylesheet
     * {@code <link href>} pointing at a static resource of the proxied console (e.g.
     * {@code /system/console/res/lib/jquery.js}). Default: routed through the console's own
     * resource proxy ({@link Console#pluginLink}).
     *
     * @param url the original resource URL
     * @return the rewritten resource URL
     */
    protected @NotNull String rewriteResourceLink(@NotNull final String url) {
        return console().pluginLink(url);
    }

    /**
     * Rewrites an {@code <a href>} content link of the proxied console (e.g. the request detail link
     * {@code requests?index=...}) so it keeps pointing through the proxy. Default: unchanged.
     *
     * @param url the original content link URL
     * @return the rewritten content link URL
     */
    protected @NotNull String rewriteContentLink(@NotNull final String url) {
        return url;
    }

    /**
     * Rewrites the text content of an inline {@code <script>} element, e.g. to adjust the
     * {@code appRoot} / {@code pluginRoot} variables the proxied console's own JS relies on.
     * Default: unchanged.
     *
     * @param script the original script text content
     * @return the rewritten script text content
     */
    protected @NotNull String rewriteScriptContent(@NotNull final String script) {
        return script;
    }

    /**
     * Decides whether an element - together with its whole subtree, including its text content -
     * should be dropped from the proxied output entirely. Default: drop {@code <link>} and
     * {@code <script>}, since our own page template already provides styling and behaviour for the
     * embedded content; override to keep them (or exclude more) for a particular proxy.
     *
     * @param qName      the element's qualified name
     * @param attributes the element's attributes
     * @return whether the element (and its subtree) should be dropped
     */
    protected boolean isExcludedElement(@NotNull final String qName, @NotNull final Attributes attributes) {
        return "link".equalsIgnoreCase(qName) || "script".equalsIgnoreCase(qName);
    }

    /**
     * Extension point: builds the SAX parser used to read the proxied response. The Felix web console
     * plugins emit plain (tag-soup) HTML, not well-formed XML (unclosed tags, undeclared entities like
     * {@code &nbsp;}), so this uses TagSoup's lenient {@link Parser} instead of the JDK's strict one.
     *
     * @return the SAX parser to use for the proxied response
     * @throws Exception if the parser cannot be created
     */
    protected @NotNull XMLReader createXmlReader() throws Exception {
        final Parser parser = new Parser();
        parser.setFeature(Parser.namespacesFeature, false);
        return parser;
    }

    /**
     * Calls the proxied console servlet and provides a {@link Reader} for its response content after
     * it has passed through the SAX transformation chain (see {@link #createTransformingHandler}).
     * <p>
     * Runs on two daemon threads chained via pipes ("servlet write" -> "SAX parse/transform" ->
     * returned reader) so that a single caller reading the result reader drives the whole pipeline
     * without risking a pipe deadlock on the calling thread.
     *
     * @param request  the current request, forwarded to the proxied servlet
     * @param response the current response, wrapped so the proxied servlet's output feeds the pipeline
     * @return a reader for the transformed proxied content, or {@code null} if no plugin is registered
     */
    protected @Nullable Reader proxy(@NotNull final SlingHttpServletRequest request,
                                     @NotNull final SlingHttpServletResponse response) {
        final Servlet servlet = getConsoleServlet();
        if (servlet == null) {
            return null;
        }
        try {
            final ProxyResponse proxyResponse = new ProxyResponse(response);

            final Thread servletThread = new Thread(() -> {
                try {
                    servlet.service(request, proxyResponse);
                } catch (Exception ignore) {
                } finally {
                    proxyResponse.finish();
                }
            }, "console-proxy-servlet-" + webConsoleLabel());
            servletThread.setDaemon(true);

            final PipedWriter transformedOutput = new PipedWriter();
            final PipedReader transformedInput = new PipedReader(transformedOutput, PIPE_BUFFER_SIZE);

            final Thread transformThread = new Thread(() -> {
                try {
                    transform(proxyResponse.pipedInput, proxyResponse.characterEncoding(), transformedOutput);
                } catch (Exception ignore) {
                } finally {
                    try {
                        transformedOutput.close();
                    } catch (IOException ignore) {
                    }
                }
            }, "console-proxy-transform-" + webConsoleLabel());
            transformThread.setDaemon(true);

            servletThread.start();
            transformThread.start();

            return transformedInput;

        } catch (IOException ignore) {
        }
        return null;
    }

    /**
     * TagSoup wraps every parsed document in a synthetic {@code <html>}/{@code <head>}/{@code <body>}
     * structure, even though the proxied console content is just an HTML fragment meant to be embedded
     * into our own page template. This filter drops those synthetic root elements (but keeps their
     * children) so downstream handlers only ever see the actual fragment content.
     */
    protected static class FragmentFilter extends XMLFilterImpl {

        /**
         * one entry per currently open element, {@code true} if that element (and thus its subtree)
         * is a dropped wrapper
         */
        protected final Deque<Boolean> suppressed = new ArrayDeque<>();

        /**
         * @param target the content handler to forward the (unwrapped) SAX events to
         */
        public FragmentFilter(@NotNull final ContentHandler target) {
            setContentHandler(target);
        }

        @Override
        public void startElement(String uri, String localName, String qName, Attributes attributes)
                throws SAXException {
            final boolean parentSuppressed = suppressed.isEmpty() || suppressed.peek();
            final boolean isWrapper = parentSuppressed && isWrapperElement(qName);
            suppressed.push(isWrapper);
            if (!isWrapper) {
                super.startElement(uri, localName, qName, attributes);
            }
        }

        @Override
        public void endElement(String uri, String localName, String qName) throws SAXException {
            if (!suppressed.pop()) {
                super.endElement(uri, localName, qName);
            }
        }

        @Override
        public void startPrefixMapping(String prefix, String uri) {
            // dropped: a plain HTML fragment doesn't need namespace declarations, and forwarding this
            // would otherwise leak the html/xhtml namespace declaration onto the first real element
            // (the serializer attaches pending prefix mappings to whatever startElement follows next)
        }

        @Override
        public void endPrefixMapping(String prefix) {
            // dropped, see startPrefixMapping
        }

        /**
         * @param qName the element's qualified name
         * @return whether the element is a synthetic {@code <html>}/{@code <head>}/{@code <body>} wrapper
         */
        protected boolean isWrapperElement(@NotNull final String qName) {
            return "html".equalsIgnoreCase(qName)
                    || "head".equalsIgnoreCase(qName)
                    || "body".equalsIgnoreCase(qName);
        }
    }

    /**
     * SAX filter which drops elements for which {@link #isExcludedElement} returns {@code true},
     * together with their whole subtree (child elements and text content). Bound to the outer
     * {@link AbstractConsoleProxy} instance so subclasses only need to override the one predicate
     * method, not any SAX plumbing.
     */
    protected class ExclusionFilter extends XMLFilterImpl {

        /**
         * one entry per currently open element, {@code true} if that element (and thus its subtree)
         * is excluded per {@link #isExcludedElement}
         */
        protected final Deque<Boolean> excluded = new ArrayDeque<>();

        /**
         * @param target the content handler to forward the (filtered) SAX events to
         */
        public ExclusionFilter(@NotNull final ContentHandler target) {
            setContentHandler(target);
        }

        @Override
        public void startElement(String uri, String localName, String qName, Attributes attributes)
                throws SAXException {
            final boolean parentExcluded = !excluded.isEmpty() && excluded.peek();
            final boolean isExcluded = parentExcluded || isExcludedElement(qName, attributes);
            excluded.push(isExcluded);
            if (!isExcluded) {
                super.startElement(uri, localName, qName, attributes);
            }
        }

        @Override
        public void characters(char[] ch, int start, int length) throws SAXException {
            if (excluded.isEmpty() || !excluded.peek()) {
                super.characters(ch, start, length);
            }
        }

        @Override
        public void endElement(String uri, String localName, String qName) throws SAXException {
            if (!excluded.pop()) {
                super.endElement(uri, localName, qName);
            }
        }
    }

    /**
     * Parses {@code input} as SAX events, drops the synthetic root wrapper TagSoup adds (see
     * {@link FragmentFilter}) as well as any elements excluded via {@link #isExcludedElement} (see
     * {@link ExclusionFilter}), runs what's left through {@link #createTransformingHandler} and
     * serializes the (possibly further modified) result to {@code output}.
     *
     * @param input    the proxied servlet's raw output
     * @param encoding the character encoding {@code input} is encoded with
     * @param output   the writer to serialize the transformed result to
     * @throws Exception if parsing or transforming the input fails
     */
    protected void transform(@NotNull final InputStream input, @NotNull final String encoding,
                             @NotNull final Writer output) throws Exception {
        final SAXTransformerFactory transformerFactory =
                (SAXTransformerFactory) SAXTransformerFactory.newInstance();
        final TransformerHandler serializer = transformerFactory.newTransformerHandler();
        final Transformer transformer = serializer.getTransformer();
        transformer.setOutputProperty(OutputKeys.METHOD, "html");
        transformer.setOutputProperty(OutputKeys.ENCODING, encoding);
        transformer.setOutputProperty(OutputKeys.OMIT_XML_DECLARATION, "yes");
        serializer.setResult(new StreamResult(output));

        final ContentHandler transformingHandler = createTransformingHandler(serializer);
        final ContentHandler exclusionFilter = new ExclusionFilter(transformingHandler);

        final XMLReader xmlReader = createXmlReader();
        xmlReader.setContentHandler(new FragmentFilter(exclusionFilter));
        xmlReader.parse(new InputSource(new InputStreamReader(input, encoding)));
    }
}
