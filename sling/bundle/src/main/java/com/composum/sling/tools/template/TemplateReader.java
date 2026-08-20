package com.composum.sling.tools.template;

import com.composum.sling.tools.template.TemplateContext.Values;
import org.apache.commons.lang3.StringUtils;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.io.Reader;
import java.io.StringReader;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.Formatter;
import java.util.Iterator;
import java.util.Locale;
import java.util.Map;
import java.util.MissingResourceException;
import java.util.Optional;
import java.util.ResourceBundle;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * A filtering {@link Reader} that renders a text template while it is read: each placeholder of the form
 * '${...}' is replaced by a value resolved from the {@link TemplateContext} of the given {@link Template}.
 * The reader is designed for HTML templates - every embedded value is XSS encoded according to its
 * declared placeholder type (see below).
 *
 * <h2>Placeholder syntax</h2>
 * <pre>
 * ${[type[.name]:]expression[;format]}
 * </pre>
 * The <em>expression</em> is either
 * <ul>
 * <li>a string literal enclosed in single quotes, e.g. {@code ${'text'}}, used as it is,</li>
 * <li>the key of a context value, e.g. {@code ${title}}; keys can be hierarchical to access nested
 * value maps, using '.' or '[...]' as path separators, e.g. {@code ${page.title}} or
 * {@code ${pages[home].title}},</li>
 * <li>a negated context value, e.g. {@code ${!hidden}}; the value is interpreted as a boolean using
 * the same rules as the 'if' condition (see below); an undefined value yields 'true'.</li>
 * </ul>
 * If the resolved value is a {@link Reader}, its content is copied into the output unmodified - as
 * raw, verbatim text, without any placeholder rendering or output encoding applied to it (caution:
 * the caller is responsible for that content being safe to embed as-is, and a Reader object can be
 * consumed only once). Placeholders that cannot be resolved (value 'null') are replaced by an empty
 * string.
 *
 * <h2>Output encoding (XSS protection)</h2>
 * Simple value placeholders are encoded for safe HTML output according to their <em>type</em>:
 * <dl>
 * <dt>{@code ${key}} (no type)</dt><dd>encoded for an HTML element body ('encodeForHTML')</dd>
 * <dt>{@code ${attr:key}}</dt><dd>encoded for use as an HTML attribute value ('encodeForHTMLAttr')</dd>
 * <dt>{@code ${link:key}}</dt><dd>validated as a href/URL value ('getValidHref')</dd>
 * <dt>{@code ${src:key}}</dt><dd>transformed into a plugin resource link
 * ({@link TemplateBuilder#pluginLink(String)}) and validated as a href value</dd>
 * <dt>{@code ${css:key}}</dt><dd>encoded for use inside a CSS string ('encodeForCSSString')</dd>
 * <dt>{@code ${js:key}}</dt><dd>encoded for use inside a JavaScript string ('encodeForJSString')</dd>
 * <dt>{@code ${i18n:key}}</dt><dd>the resolved value is translated using the readers
 * {@link ResourceBundle} (if one is configured) and then encoded for an HTML element body</dd>
 * <dt>{@code ${raw:key}}</dt><dd>embedded as-is, without any output encoding - use only for
 * values that are already known to be safe in their embedding context (e.g. a JCR path)</dd>
 * </dl>
 *
 * <h2>Formatting</h2>
 * A simple value placeholder can declare a format appended with ';'. The format is itself an expression
 * (usually a quoted literal) and is applied to the value via {@link java.util.Formatter} using the
 * readers {@link Locale}, e.g.
 * <pre>
 * ${price;'%.2f'}   ${modified;'%1$tF %1$tT'}
 * </pre>
 *
 * <h2>Control structures</h2>
 * The <em>template</em> argument of the following structures is either an absolute path (starting
 * with '/') of a template resource resolved via the {@link TemplateBuilder} or an inline template
 * string; '${...}' placeholders nested in a quoted template string are rendered recursively:
 * <dl>
 * <dt>{@code ${include:expression}}</dt>
 * <dd>embeds the template designated by the expressions value: a template resource path or a
 * template string</dd>
 * <dt>{@code ${include.name:expression;template}}</dt>
 * <dd>embeds the given template with the expressions value available as context value 'name'</dd>
 * <dt>{@code ${each:iterable;template}} or {@code ${each.name:iterable;template}}</dt>
 * <dd>repeats the given template for each element of the iterable value - an Iterator, an Iterable,
 * an array or a Map (a Map iterates over its entries); within the template each element is available
 * as context value 'item' (or 'name') and its zero-based position as 'itemIndex' ('nameIndex')</dd>
 * <dt>{@code ${if:condition;template}}</dt>
 * <dd>embeds the template only if the condition value is 'true': a Boolean TRUE, a non-empty String,
 * a non-empty Collection, Map or array, or any other non-null object; use '!' to negate the
 * condition, e.g. {@code ${if:!hidden;'...'}}</dd>
 * <dt>{@code ${if:condition;template|elseTemplate}}</dt>
 * <dd>embeds the first template if the condition is 'true', otherwise the second one</dd>
 * </dl>
 *
 * <h2>Escaping</h2>
 * '\$' and '\\' produce a literal '$' and '\'; a backslash preceding any other character is kept as
 * it is.
 */
public class TemplateReader extends Reader {

    /** placeholder type: embeds another template ({@code ${include:...}}, {@code ${include.name:...}}) */
    public static final String INCLUDE = "include";
    /** placeholder type: repeats a template for each element of an iterable ({@code ${each:...}}) */
    public static final String EACH = "each";
    /** placeholder type: conditionally embeds a template ({@code ${if:...}}) */
    public static final String IF = "if";
    /** output encoding type: validates the value as a href/URL ({@code ${link:...}}) */
    public static final String LINK = "link";
    /** output encoding type: encodes the value for use as an HTML attribute ({@code ${attr:...}}) */
    public static final String ATTR = "attr";
    /**
     * output encoding type: resolves the value via {@link TemplateBuilder#pluginLink(String)} and
     * validates it as a href ({@code ${src:...}})
     */
    public static final String SRC = "src";
    /** output encoding type: encodes the value for use inside a CSS string ({@code ${css:...}}) */
    public static final String CSS = "css";
    /** output encoding type: encodes the value for use inside a JavaScript string ({@code ${js:...}}) */
    public static final String JS = "js";
    /**
     * output encoding type: translates the value via the readers {@link ResourceBundle}, then
     * HTML-encodes it ({@code ${i18n:...}})
     */
    public static final String I18N = "i18n";
    /** output encoding type: embeds the value as-is, without any output encoding ({@code ${raw:...}}) */
    public static final String RAW = "raw";

    /**
     * the length threshold (in characters) at or above which an already encoded value is embedded
     * via a separate {@link StringReader} instead of being appended to the internal {@link Buffer}
     * - this keeps the buffer's fixed-size value reserve bounded
     */
    protected static final int STR_MAX = 2048;
    /** the target fill level (in characters) the internal {@link Buffer} is refilled up to by {@link #load()} */
    protected static final int BUFSIZE = STR_MAX * 3;

    /**
     * parses a placeholder key into its 'type', 'value' (the expression) and 'fmt' (format) groups,
     * see {@link Key}
     */
    protected static final Pattern KEY_PATTERN = Pattern.compile(
            "^((?<type>[^:'\\[;]*):)?(?<value>('[^']*'|[^;]*))(;(?<fmt>.*))?$");

    /**
     * splits the 'template' argument of an {@code ${if:...}} placeholder into its 'true' and
     * 'false' branch groups, separated by an unquoted '|'
     */
    protected static final Pattern IF_PATTERN = Pattern.compile(
            "^(?<true>('[^']*'|[^|]*))\\|(?<false>.*)$");

    /**
     * The parsed representation of a placeholder key of the form
     * {@code type[.property]:value[;format]} (any of 'type', 'property' and 'format' may be
     * absent). 'type' is lower-cased and trimmed; an optional '.property' suffix of the type is
     * split off (used by the 'each' and 'include' placeholders to name the bound context value).
     * 'value' is the (unparsed) expression, 'format' is the (unparsed) text following an unquoted
     * ';'. A key that does not match this syntax at all is treated as a plain, type-less 'value'
     * expression.
     */
    public static class Key {

        /** the (lower-cased, trimmed) placeholder type, or 'null' if none is given */
        public final String type;
        /** the '.name' suffix of the type (e.g. the bound context value name of 'each'/'include'), or 'null' */
        public final String property;
        /** the (unparsed) value expression */
        public final String value;
        /** the (unparsed) format/template text following an unquoted ';', or 'null' if none is given */
        public final String format;
        protected final String key;

        /**
         * @param key the raw, still unparsed placeholder key text (without the surrounding '${' and '}')
         */
        public Key(String key) {
            final Matcher matcher = KEY_PATTERN.matcher(this.key = key);
            if (matcher.matches()) {
                final String t = Optional.ofNullable(matcher.group("type")).map(String::toLowerCase).orElse(null);
                if (t != null) {
                    int dot = t.indexOf('.');
                    if (dot < 0) {
                        type = t.trim();
                        property = null;
                    } else {
                        type = t.substring(0, dot).trim();
                        property = t.substring(dot + 1).trim();
                    }
                } else {
                    type = null;
                    property = null;
                }
                value = matcher.group("value");
                format = matcher.group("fmt");
            } else {
                type = null;
                property = null;
                value = key;
                format = null;
            }
        }

        /**
         * @return the original, unparsed key text
         */
        @Override
        public String toString() {
            return key;
        }
    }

    /**
     * A simple character queue used as the read buffer shared by a stack of nested
     * {@link TemplateReader} instances (see {@link #buffer}): {@link #write} appends characters
     * produced while rendering the template, {@link #copy} consumes them from {@link #read}; the
     * backing array is sized to always have room for one more already encoded value (max length
     * {@link #STR_MAX}) in addition to the {@link #BUFSIZE} target fill level.
     */
    protected static class Buffer {

        private final char[] buf = new char[BUFSIZE + STR_MAX]; // reserve place for values (max length: STR_MAX)
        private int off = 0;
        private int len = 0;

        /** appends a single character */
        protected void write(int token) {
            buf[len++] = (char) token;
        }

        /** appends a string; caution: the caller must ensure the string is shorter than {@link #STR_MAX} */
        protected void write(String text) {
            text.getChars(0, text.length(), buf, len);
            len += text.length();
        }

        /** the number of characters currently buffered (written but not yet consumed) */
        protected int len() {
            return len;
        }

        /** compacts the buffer by discarding its already consumed prefix, making room to write again */
        protected void shift() {
            if (off > 0) {
                System.arraycopy(buf, off, buf, 0, len);
                off = 0;
            }
        }

        /**
         * consumes up to 'len' buffered characters into 'cbuf' starting at 'off'
         *
         * @return the number of characters actually copied
         */
        protected int copy(char[] cbuf, int off, int len) {
            int count = Math.min(this.len, len);
            if (count > 0) {
                System.arraycopy(this.buf, this.off, cbuf, off, count);
                this.off += count;
                this.len -= count;
            }
            return count;
        }
    }

    /** the underlying character stream of this level of the template (the raw, unrendered text) */
    protected final Reader reader;
    /** the template configuration (path, context, builder) rendered by this reader */
    protected final Template template;
    /** the locale used to format placeholder values, see the class Javadoc section "Formatting" */
    protected final Locale locale;
    /** the translations bundle used by the 'i18n' placeholder type, or 'null' if translation is off */
    protected final ResourceBundle resourceBundle;

    /** the read buffer shared by this reader and every reader nested/embedded below it */
    protected Buffer buffer;
    /** 'true' once the underlying {@link #reader} is exhausted */
    protected boolean eof = false;

    /**
     * the currently embedded reader - either a value {@link Reader} passed through unmodified, a
     * recursively rendered nested template, or a pre-encoded long value - or 'null' if none is active
     */
    private transient Reader embed;
    /** the currently active 'each' iteration, or 'null' if none is active */
    private transient EmbedIterator iterator;

    /**
     * @param template the template configuration and context
     * @param reader   the text to read - probably with embedded value placeholders
     */
    public TemplateReader(@NotNull Template template, @NotNull Reader reader) {
        this(template, reader, null, null);
    }

    /**
     * @param template the template configuration and context
     * @param reader   the text to read - probably with embedded value placeholders
     * @param locale   the locale to use for value formatting
     */
    public TemplateReader(@NotNull Template template, @NotNull Reader reader, @Nullable Locale locale) {
        this(template, reader, locale, null);
    }

    /**
     * @param template       the template configuration and context
     * @param reader         the text to read - probably with embedded value placeholders
     * @param locale         the locale to use for value formatting
     * @param resourceBundle the translations bundle (switches translation on)
     */
    public TemplateReader(@NotNull Template template, @NotNull Reader reader,
                          @Nullable Locale locale, @Nullable ResourceBundle resourceBundle) {
        this(new Buffer(), template, reader, locale, resourceBundle);
    }

    /**
     * @param buffer         the buffer shared by the readers stack
     * @param template       the template configuration and context
     * @param reader         the text to read - probably with embedded value placeholders
     * @param locale         the locale to use for value formatting
     * @param resourceBundle the translations bundle (switches translation on)
     */
    protected TemplateReader(@NotNull final Buffer buffer, @NotNull Template template, @NotNull Reader reader,
                             @Nullable Locale locale, @Nullable ResourceBundle resourceBundle) {
        this.buffer = buffer;
        this.template = template;
        this.reader = reader;
        this.locale = locale != null ? locale : Locale.getDefault();
        this.resourceBundle = resourceBundle;
    }

    /**
     * translates the given value via the {@link #resourceBundle} (used by the 'i18n' placeholder
     * type); returns the value unchanged if no bundle is configured, or if it has no translation
     * for it
     */
    protected @NotNull String i18n(@NotNull final String value) {
        if (resourceBundle != null) {
            try {
                return resourceBundle.getString(value);
            } catch (MissingResourceException ignore) {
            }
        }
        return value;
    }

    /**
     * resolves the given (unparsed) expression against the given context: a quoted string literal
     * is used as-is, a '!'-prefixed expression is resolved and negated (see {@link #booleanOf}),
     * any other expression is resolved as a context key via {@link TemplateContext#getValue(String)}
     */
    protected Object getValue(@NotNull final String expression, @NotNull final TemplateContext context) {
        return expression.matches("^'.*'$")
                ? expression.substring(1, expression.length() - 1)
                : expression.startsWith("!")
                ? !booleanOf(context.getValue(expression.substring(1)))
                : context.getValue(expression);
    }

    /**
     * interprets the given value as a boolean condition (used by the 'if' placeholder and by '!'
     * negation): 'false' for 'null', {@link Boolean#FALSE}, an empty String, an empty Collection,
     * an empty Map or an empty array; 'true' for everything else
     */
    protected boolean booleanOf(@Nullable final Object value) {
        return value != null &&
                (value instanceof Boolean ? ((Boolean) value) :
                        value instanceof String ? StringUtils.isNotEmpty((String) value) :
                                value instanceof Collection ? !((Collection<?>) value).isEmpty() :
                                        value instanceof Map ? !((Map<?, ?>) value).isEmpty() :
                                                !(value instanceof Object[]) || ((Object[]) value).length > 0);
    }

    /** closes the underlying {@link #reader} */
    @Override
    public void close() throws IOException {
        reader.close();
    }

    /**
     * implements the {@link Reader} contract: first drains a currently embedded reader
     * ({@link #embed}) or 'each' iteration ({@link #iterator}) if one is active, then refills the
     * shared {@link #buffer} via {@link #load()} and returns characters from it; returns -1 once
     * the underlying reader, and any pending embedded reader or iteration, are exhausted
     */
    @Override
    public int read(char @NotNull [] cbuf, int off, int len) throws IOException {
        do {
            if (embed != null) {
                if (buffer.len() > 0) { // flush buffer before embedding a reader
                    return buffer.copy(cbuf, off, len);
                }
                int count = embed.read(cbuf, off, len); // embed a readers content
                if (count >= 0) {
                    return count;
                }
                embed.close();
                embed = null;
            }
        }
        while (iterator != null && (embed = iterator.next()) != null);
        iterator = null;
        if (buffer.len() < len && !eof) {
            load();
        }
        if (buffer.len() < 1 && embed == null && iterator == null) {
            return -1;
        }
        return buffer.copy(cbuf, off, len);
    }

    /**
     * reads and renders characters from the underlying {@link #reader} into the shared
     * {@link #buffer} until it reaches {@link #BUFSIZE}, the underlying reader is exhausted, or a
     * placeholder needs to embed a nested reader or an 'each' iteration - in the latter cases this
     * method returns early (leaving {@link #embed}/{@link #iterator} set) so the caller can drain
     * the embedded content before further characters are buffered. Handles escaping ('\$', '\\'),
     * plain text, and, for every '${...}' placeholder found, resolves and renders it according to
     * its type (see the class Javadoc for the full placeholder syntax).
     */
    protected void load() throws IOException {
        buffer.shift();
        while (!eof && buffer.len() < BUFSIZE) {
            int token = reader.read();
            if (token < 0) {
                eof = true;
            } else if (token == '\\') { // escaped '$' or '\'?
                int next = reader.read();
                if (next < 0) {
                    buffer.write('\\');
                    eof = true;
                } else {
                    if (next != '\\' && next != '$') {
                        buffer.write('\\');
                    }
                    buffer.write(next);
                }
            } else if (token == '$') { // '${...} ?
                int next = reader.read();
                if (next < 0) {
                    buffer.write('$');
                    eof = true;
                } else {
                    if (next == '{') {
                        final String keyPattern = readKey(new StringBuilder()).toString();
                        if (!eof) {
                            final TemplateContext context = template.getContext();
                            final TemplateBuilder builder = template.getBuilder();
                            Key key = new Key(keyPattern.trim());
                            Object value = getValue(key.value, context);
                            if (value instanceof Reader) {
                                // embedding of a reader object unmodified raw content
                                embed = (Reader) value;
                                return; // stop buffering up to the end of the embedded reader
                            } else {
                                if (EACH.equals(key.type) && value != null) {
                                    // 'each' iteration...
                                    iterator = new EmbedIterator(key, value);
                                    return; // stop buffering up to the end of the iteration
                                } else if (IF.equals(key.type) && StringUtils.isNotBlank(key.format)) {
                                    // 'if' embedding...
                                    final boolean condition = booleanOf(value);
                                    final Matcher matcher = IF_PATTERN.matcher(key.format);
                                    final String pathOrExpression = Optional.ofNullable(matcher.matches()
                                                    ? matcher.group(Boolean.toString(condition))
                                                    : condition ? key.format : null)
                                            .map(expr -> getValue(expr, context))
                                            .map(builder::toString).orElse("");
                                    embed = templateReader(builder, pathOrExpression, context);
                                    if (embed != null) {
                                        return; // stop buffering up to the end of the embedded reader
                                    }
                                } else if (INCLUDE.equals(key.type) && value != null) {
                                    // recursive embedding of a template with the given context value set
                                    if (StringUtils.isNotBlank(key.property) && StringUtils.isNotBlank(key.format)) {
                                        TemplateContext includeCtx = new TemplateContext(context, new Values()
                                                .with(key.property, value));
                                        final Object tmpl;
                                        if ((tmpl = getValue(key.format, context)) != null) {
                                            embed = templateReader(builder, builder.toString(tmpl), includeCtx);
                                        }
                                    } else {
                                        embed = templateReader(builder, builder.toString(value), context);
                                    }
                                    if (embed != null) {
                                        return; // stop buffering up to the end of the embedded reader
                                    }
                                } else if (value != null) {
                                    String string;
                                    final Object format;
                                    final String pattern;
                                    if (StringUtils.isNotBlank(key.format)
                                            && (format = getValue(key.format, context)) != null
                                            && StringUtils.isNotBlank(pattern = builder.toString(format))) {
                                        StringBuilder buffer = new StringBuilder();
                                        try (Formatter formatter = new Formatter(buffer)) {
                                            formatter.format(locale, pattern, value);
                                            string = buffer.toString();
                                        }
                                    } else {
                                        string = builder.toString(value);
                                    }
                                    if (LINK.equals(key.type)) {
                                        string = builder.xssapi().getValidHref(string);
                                    } else if (ATTR.equals(key.type)) {
                                        string = builder.xssapi().encodeForHTMLAttr(string);
                                    } else if (SRC.equals(key.type)) {
                                        string = builder.xssapi().getValidHref(builder.pluginLink(string));
                                    } else if (CSS.equals(key.type)) {
                                        string = builder.xssapi().encodeForCSSString(string);
                                    } else if (JS.equals(key.type)) {
                                        string = builder.xssapi().encodeForJSString(string);
                                    } else if (I18N.equals(key.type)) {
                                        string = builder.xssapi().encodeForHTML(i18n(string));
                                    } else if (!RAW.equals(key.type)) {
                                        string = builder.xssapi().encodeForHTML(string);
                                    }
                                    if (string != null) {
                                        if (string.length() >= STR_MAX) {
                                            embed = new StringReader(string);
                                            return;
                                        }
                                        buffer.write(string);
                                    }
                                }
                            }
                        } else {
                            buffer.write('$');
                            buffer.write('{');
                            buffer.write(keyPattern);
                        }
                    } else {
                        buffer.write('$');
                        buffer.write(next);
                    }
                }
            } else {
                buffer.write(token);
            }
        }
    }

    /**
     * reads the raw key text of a placeholder up to (and excluding) the closing '}'; text enclosed
     * in single quotes is copied verbatim, including any nested '${...}' placeholders (parsed
     * recursively via this same method), so that a quoted template string can itself contain
     * placeholders
     *
     * @param buffer the buffer to append the read key text to
     * @return the given buffer, for chaining
     */
    protected StringBuilder readKey(StringBuilder buffer) throws IOException {
        int token;
        while (!eof && (token = reader.read()) != '}') {
            if (token < 0) {
                eof = true;
            } else if (token == '\'') { // read each token enclosed in '...' as it is
                buffer.append((char) token);
                while (!eof && (token = reader.read()) != '\'') {
                    if (token < 0) {
                        eof = true;
                    } else if (token == '$') {
                        buffer.append((char) token);
                        int next = reader.read();
                        if (next < 0) {
                            eof = true;
                        } else {
                            buffer.append((char) next);
                            if (next == '{') {
                                readKey(buffer);
                                if (!eof) {
                                    buffer.append('}'); // closing "}"
                                }
                            }
                        }
                    } else {
                        buffer.append((char) token);
                    }
                }
                if (!eof) {
                    buffer.append((char) token); // closing "'"
                }
            } else {
                buffer.append((char) token);
            }
        }
        return buffer;
    }

    /**
     * resolves the 'template' argument of an 'include', 'each' or 'if' placeholder into a nested
     * {@link TemplateReader}: an absolute path (starting with '/') is resolved as a template
     * resource via the given {@link TemplateBuilder}, anything else is rendered as an inline
     * template string; returns 'null' if the expression is blank, or if the resource cannot be
     * resolved or opened
     */
    protected @Nullable TemplateReader templateReader(@NotNull final TemplateBuilder builder,
                                                      @NotNull final String pathOrExpression,
                                                      @NotNull final TemplateContext context) {
        if (pathOrExpression.startsWith("/")) {
            Template tmpl = builder.getTemplate(context, pathOrExpression);
            if (tmpl != null) {
                final Reader reader = builder.openTemplate(tmpl);
                if (reader != null) {
                    return new TemplateReader(buffer, tmpl, reader, locale, resourceBundle);
                }
            }
        } else if (StringUtils.isNotBlank(pathOrExpression)) {
            return new TemplateReader(buffer, new Template(template.getPath(), context, template.getBuilder()),
                    new StringReader(pathOrExpression), locale, resourceBundle);
        }
        return null;
    }

    /**
     * Iterates the value of an 'each' placeholder, producing one nested {@link TemplateReader} per
     * element - each bound to a child {@link TemplateContext} that exposes the current element as
     * 'item' (or the placeholder's '.property' name) and its zero-based position as 'itemIndex'
     * ('nameIndex'). Supports an {@link Iterator}, an {@link Iterable}, an {@code Object[]} and a
     * {@link Map} (iterating its entries) as the underlying value; anything else yields an empty
     * iteration.
     */
    protected class EmbedIterator implements Iterator<Reader> {

        /** the context value name the current element is bound to */
        protected final String itemKey;
        /** the context value name the current element's zero-based position is bound to */
        protected final String indexKey;
        protected final String firstKey;
        /** the resolved 'template' argument (path or inline string), or 'null' if it could not be resolved */
        protected final String pathOrExpression;
        /** the iterator over the underlying value's elements */
        protected final Iterator<?> iterator;

        protected Object currentItem;
        protected int currentIndex = -1;

        /**
         * @param key   the parsed 'each'/'each.name' placeholder key
         * @param value the value to iterate - an Iterator, Iterable, array or Map
         */
        public EmbedIterator(@NotNull final Key key, @NotNull final Object value) {
            itemKey = StringUtils.defaultIfEmpty(key.property, "item");
            indexKey = itemKey + "Index";
            firstKey = "first" + StringUtils.capitalize(itemKey);
            final Object tmpl;
            pathOrExpression = StringUtils.isBlank(key.format)
                    || (tmpl = getValue(key.format, template.getContext())) == null
                    ? null : template.getBuilder().toString(tmpl);
            if (StringUtils.isNotBlank(pathOrExpression)) {
                iterator = value instanceof Iterator ? (Iterator<?>) value
                        : (value instanceof Iterable) ? ((Iterable<?>) value).iterator()
                        : value instanceof Object[] ? Arrays.asList((Object[]) value).iterator()
                        : value instanceof Map ? ((Map<?, ?>) value).entrySet().iterator()
                        : Collections.emptyIterator();
            } else {
                iterator = Collections.emptyIterator();
            }
        }

        @Override
        public boolean hasNext() {
            return iterator.hasNext();
        }

        /**
         * @return a nested reader rendering the template for the next element, or 'null' if there
         * is no next element or the template could not be resolved for it (see {@link #templateReader})
         */
        @Override
        public Reader next() {
            if (hasNext()) {
                final TemplateBuilder builder = template.getBuilder();
                currentIndex++;
                currentItem = builder.valuesOf(iterator.next());
                TemplateContext itemContext = new TemplateContext(template.getContext(), new Values()
                        .with(itemKey, currentItem)
                        .with(indexKey, currentIndex)
                        .with(firstKey, currentIndex == 0));
                return templateReader(builder, pathOrExpression, itemContext);
            }
            return null;
        }
    }
}
