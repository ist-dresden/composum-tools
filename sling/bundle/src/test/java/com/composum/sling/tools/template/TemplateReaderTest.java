package com.composum.sling.tools.template;

import com.composum.sling.tools.Manager;
import com.composum.sling.tools.ToolsPlugin;
import com.composum.sling.tools.TestManager;
import com.composum.sling.tools.template.TemplateContext.Values;
import org.apache.commons.io.IOUtils;
import org.apache.sling.xss.XSSAPI;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.Reader;
import java.io.StringReader;
import java.util.Calendar;
import java.util.GregorianCalendar;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.ListResourceBundle;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.ResourceBundle;
import java.util.function.Supplier;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

public class TemplateReaderTest {

    protected Manager manager = new TestManager();
    protected ToolsPlugin plugin = manager.plugins().get("test");
    protected TemplateBuilder builder = (TemplateBuilder) plugin;

    /**
     * a {@link TemplateBuilder} whose {@link XSSAPI} tags every encoding call with the name of the
     * method that produced it, so tests can verify that a placeholder type routes to the correct
     * encoding operation - the real {@link TestManager#XSSMOCK} is a pass-through and cannot show
     * that distinction
     */
    protected final TemplateBuilder TAGGING_BUILDER = new TemplateBuilder() {

        @Override
        public @Nullable Template getTemplate(@NotNull TemplateContext context, @NotNull String key) {
            return null;
        }

        @Override
        public @Nullable Reader openTemplate(@Nullable Template template) {
            return null;
        }

        @Override
        public @NotNull XSSAPI xssapi() {
            return TAGGING_XSSAPI;
        }

        @Override
        public @NotNull String adjustLink(@NotNull final String link) {
            return link.replaceFirst("^.+(" + Pattern.quote(manager.serverPath()) + ")", "$1");
        }

        @Override
        public @NotNull String pluginLink(@NotNull String path) {
            return "PLUGIN[" + path + "]";
        }

        @Override
        public @NotNull String toString(@NotNull Object value) {
            return String.valueOf(value);
        }

        @Override
        public @NotNull Object valuesOf(@NotNull Object value) {
            return value;
        }
    };

    protected static final XSSAPI TAGGING_XSSAPI = new XSSAPI() {

        @Override
        public Integer getValidInteger(String integer, int defaultValue) {
            return defaultValue;
        }

        @Override
        public Long getValidLong(String source, long defaultValue) {
            return defaultValue;
        }

        @Override
        public Double getValidDouble(String source, double defaultValue) {
            return defaultValue;
        }

        @Override
        public String getValidDimension(String dimension, String defaultValue) {
            return dimension;
        }

        @Override
        public @NotNull String getValidHref(String url) {
            return "HREF[" + url + "]";
        }

        @Override
        public String getValidJSToken(String token, String defaultValue) {
            return token;
        }

        @Override
        public String getValidStyleToken(String token, String defaultValue) {
            return token;
        }

        @Override
        public String getValidCSSColor(String color, String defaultColor) {
            return color;
        }

        @Override
        public String getValidMultiLineComment(String comment, String defaultComment) {
            return comment;
        }

        @Override
        public String getValidJSON(String json, String defaultJson) {
            return json;
        }

        @Override
        public String getValidXML(String xml, String defaultXml) {
            return xml;
        }

        @Override
        public String encodeForHTML(String source) {
            return "HTML[" + source + "]";
        }

        @Override
        public String encodeForHTMLAttr(String source) {
            return "ATTR[" + source + "]";
        }

        @Override
        public String encodeForXML(String source) {
            return source;
        }

        @Override
        public String encodeForXMLAttr(String source) {
            return source;
        }

        @Override
        public String encodeForJSString(String source) {
            return "JS[" + source + "]";
        }

        @Override
        public String encodeForCSSString(String source) {
            return "CSS[" + source + "]";
        }

        @Override
        public @NotNull String filterHTML(String source) {
            return source;
        }
    };

    /**
     * a {@link TemplateBuilder} whose {@link XSSAPI#getValidHref} simulates an AEM Cloud Service
     * publisher's implicit href externalization (e.g. via the resource resolver's request-based
     * mapping): a relative href is prefixed with an absolute, external host; an already-absolute
     * href (a genuine external link) is left untouched - used to verify that
     * {@link TemplateBuilder#adjustLink} normalizes the former back to a link relative to this
     * plugin's own server path, while leaving the latter alone
     */
    protected final TemplateBuilder MAPPING_BUILDER = new TemplateBuilder() {

        @Override
        public @Nullable Template getTemplate(@NotNull TemplateContext context, @NotNull String key) {
            return null;
        }

        @Override
        public @Nullable Reader openTemplate(@Nullable Template template) {
            return null;
        }

        @Override
        public @NotNull XSSAPI xssapi() {
            return MAPPING_XSSAPI;
        }

        @Override
        public @NotNull String adjustLink(@NotNull final String link) {
            return link.replaceFirst("^.+(" + Pattern.quote(manager.serverPath()) + ")", "$1");
        }

        @Override
        public @NotNull String pluginLink(@NotNull String path) {
            return manager.serverPath() + ".test.resource.html" + path;
        }

        @Override
        public @NotNull String toString(@NotNull Object value) {
            return String.valueOf(value);
        }

        @Override
        public @NotNull Object valuesOf(@NotNull Object value) {
            return value;
        }
    };

    protected static final XSSAPI MAPPING_XSSAPI = new XSSAPI() {

        @Override
        public Integer getValidInteger(String integer, int defaultValue) {
            return defaultValue;
        }

        @Override
        public Long getValidLong(String source, long defaultValue) {
            return defaultValue;
        }

        @Override
        public Double getValidDouble(String source, double defaultValue) {
            return defaultValue;
        }

        @Override
        public String getValidDimension(String dimension, String defaultValue) {
            return dimension;
        }

        @Override
        public @NotNull String getValidHref(String url) {
            return url.matches("^[a-zA-Z][a-zA-Z0-9+.-]*://.*$") ? url
                    : "https://publish-p123-e456.adobeaemcloud.com" + url;
        }

        @Override
        public String getValidJSToken(String token, String defaultValue) {
            return token;
        }

        @Override
        public String getValidStyleToken(String token, String defaultValue) {
            return token;
        }

        @Override
        public String getValidCSSColor(String color, String defaultColor) {
            return color;
        }

        @Override
        public String getValidMultiLineComment(String comment, String defaultComment) {
            return comment;
        }

        @Override
        public String getValidJSON(String json, String defaultJson) {
            return json;
        }

        @Override
        public String getValidXML(String xml, String defaultXml) {
            return xml;
        }

        @Override
        public String encodeForHTML(String source) {
            return source;
        }

        @Override
        public String encodeForHTMLAttr(String source) {
            return source;
        }

        @Override
        public String encodeForXML(String source) {
            return source;
        }

        @Override
        public String encodeForXMLAttr(String source) {
            return source;
        }

        @Override
        public String encodeForJSString(String source) {
            return source;
        }

        @Override
        public String encodeForCSSString(String source) {
            return source;
        }

        @Override
        public @NotNull String filterHTML(String source) {
            return source;
        }
    };

    protected String render(String template, Values values) throws IOException {
        return render(builder, template, values, null, null);
    }

    protected String render(String template, Values values, Locale locale) throws IOException {
        return render(builder, template, values, locale, null);
    }

    protected String render(String template, Values values, ResourceBundle bundle) throws IOException {
        return render(builder, template, values, null, bundle);
    }

    protected static String render(TemplateBuilder builder, String template, Values values) throws IOException {
        return render(builder, template, values, null, null);
    }

    protected static String render(TemplateBuilder builder, String template, Values values,
                                   ResourceBundle bundle) throws IOException {
        return render(builder, template, values, null, bundle);
    }

    protected static String render(TemplateBuilder builder, String template, Values values,
                                   @Nullable Locale locale, @Nullable ResourceBundle bundle) throws IOException {
        Template tmpl = new Template("/inline", new TemplateContext(values), builder);
        Reader reader = new TemplateReader(tmpl, new StringReader(template), locale, bundle);
        return IOUtils.toString(reader);
    }

    // --- template composition (resource based) -----------------------------------------------

    @Test
    public void testTemplateParts01() throws Exception {
        Map<String, String> parts = new LinkedHashMap<>();
        parts.put("p1", "P1");
        parts.put("p2", "P2");
        TemplateContext context = new TemplateContext(new Values()
                .with("tmpl", "<ul>${each.p:parts;'<li>${pIndex}: ${p.key}=${p.value}</li>'}</ul>")
                .with("parts", parts)
                .with("condition", true)
        );
        Template template = builder.getTemplate(context, "main");
        assertNotNull(template);
        Reader reader = new TemplateReader(template, Objects.requireNonNull(builder.openTemplate(template)));
        assertEquals(
                "<main><ul><li>0: p1=P1</li><li>1: p2=P2</li></ul></main>", IOUtils.toString(reader));
    }

    @Test
    public void testTemplateParts02() throws Exception {
        TemplateContext context = new TemplateContext(new Values()
                .with("tmpl", "/test/parts-02.html")
                .with("parts", List.of("x", "y"))
                .with("condition", true)
        );
        Template template = builder.getTemplate(context, "main");
        assertNotNull(template);
        Reader reader = new TemplateReader(template, Objects.requireNonNull(builder.openTemplate(template)));
        assertEquals(
                "<main><ul><li>0: x</li><li>1: y</li></ul>\n" +
                        "<ul>0: <li>0.0: x</li><li>0.1: y</li>1: <li>1.0: x</li><li>1.1: y</li></ul>\n" +
                        "<div><span>Test</span></div></main>", IOUtils.toString(reader));
    }

    @Test
    public void testTemplateParts03() throws Exception {
        TemplateContext context = new TemplateContext(new Values()
                .with("tmpl", (Supplier<?>) () -> "/test/parts-03.html")
                .with("parts", (Supplier<?>) () -> new String[]{"XX", "YY", ""})
                .with("condition", true)
        );
        Template template = builder.getTemplate(context, "main");
        assertNotNull(template);
        Reader reader = new TemplateReader(template, Objects.requireNonNull(builder.openTemplate(template)));
        assertEquals(
                "<main><ul><li>0: XX</li><li>1: YY</li><li>Tools Test</li></ul>\n" +
                        "<div><span>Test</span><span class=\"not-found\"></span></div></main>",
                IOUtils.toString(reader));
    }

    // --- simple value substitution ------------------------------------------------------------

    @Nested
    class ValueSubstitution {

        @Test
        public void plainKeyIsSubstituted() throws Exception {
            assertEquals("Hello", render("${title}", new Values().with("title", "Hello")));
        }

        @Test
        public void undefinedKeyYieldsEmptyString() throws Exception {
            assertEquals("[]", render("[${missing}]", new Values()));
        }

        @Test
        public void quotedStringLiteralIsUsedAsIs() throws Exception {
            assertEquals("literal text", render("${'literal text'}", new Values()));
        }

        @Test
        public void dotSeparatedHierarchicalKeyIsResolved() throws Exception {
            assertEquals("Home", render("${pages.home.title}",
                    new Values().with("pages", Map.of("home", Map.of("title", "Home")))));
        }

        @Test
        public void bracketSeparatedHierarchicalKeyIsResolved() throws Exception {
            assertEquals("Home", render("${pages[home].title}",
                    new Values().with("pages", Map.of("home", Map.of("title", "Home")))));
        }

        @Test
        public void placeholderKeyIsTrimmed() throws Exception {
            assertEquals("Hi", render("${  title  }", new Values().with("title", "Hi")));
        }

        @Test
        public void supplierValueIsEvaluatedLazily() throws Exception {
            assertEquals("computed", render("${lazy}", new Values().with("lazy", (Supplier<?>) () -> "computed")));
        }
    }

    // --- negation ------------------------------------------------------------------------------

    @Nested
    class Negation {

        @Test
        public void negatedTrueBooleanIsFalse() throws Exception {
            assertEquals("false", render("${!flag}", new Values().with("flag", Boolean.TRUE)));
        }

        @Test
        public void negatedFalseBooleanIsTrue() throws Exception {
            assertEquals("true", render("${!flag}", new Values().with("flag", Boolean.FALSE)));
        }

        @Test
        public void negatedUndefinedValueIsTrue() throws Exception {
            assertEquals("true", render("${!missing}", new Values()));
        }
    }

    // --- formatting ------------------------------------------------------------------------------

    @Nested
    class Formatting {

        @Test
        public void numberIsFormattedWithGivenPattern() throws Exception {
            assertEquals("12.50", render("${price;'%.2f'}",
                    new Values().with("price", 12.5), Locale.US));
        }

        @Test
        public void numberFormattingHonorsLocale() throws Exception {
            assertEquals("12,50", render("${price;'%.2f'}",
                    new Values().with("price", 12.5), Locale.GERMANY));
        }

        @Test
        public void dateIsFormattedWithGivenPattern() throws Exception {
            Calendar calendar = new GregorianCalendar(2026, Calendar.MARCH, 5, 12, 0, 0);
            assertEquals("2026-03-05", render("${modified;'%1$tF'}",
                    new Values().with("modified", calendar.getTime()), Locale.US));
        }

        @Test
        public void unresolvableFormatExpressionFallsBackToPlainValue() throws Exception {
            // 'missingPattern' is not a quoted literal and not present in the context
            assertEquals("12.5", render("${price;missingPattern}", new Values().with("price", "12.5")));
        }

        @Test
        public void blankFormatExpressionFallsBackToPlainValue() throws Exception {
            assertEquals("12.5", render("${price;'   '}", new Values().with("price", "12.5")));
        }
    }

    // --- output encoding routing (per placeholder type) -----------------------------------------

    @Nested
    class Encoding {

        @Test
        public void defaultTypeUsesHtmlEncoding() throws Exception {
            assertEquals("HTML[<script>]", render(TAGGING_BUILDER, "${v}",
                    new Values().with("v", "<script>")));
        }

        @Test
        public void attrTypeUsesHtmlAttrEncoding() throws Exception {
            assertEquals("ATTR[<script>]", render(TAGGING_BUILDER, "${attr:v}",
                    new Values().with("v", "<script>")));
        }

        @Test
        public void linkTypeUsesHrefValidation() throws Exception {
            assertEquals("HREF[/a/b]", render(TAGGING_BUILDER, "${link:v}",
                    new Values().with("v", "/a/b")));
        }

        @Test
        public void srcTypeAppliesPluginLinkThenHrefValidation() throws Exception {
            assertEquals("HREF[PLUGIN[/img.png]]", render(TAGGING_BUILDER, "${src:v}",
                    new Values().with("v", "/img.png")));
        }

        @Test
        public void srcTypeWithRealBuilderProducesActualPluginLink() throws Exception {
            assertEquals("/apps/cpm/test.test.resource.html/lib/img.png", render("${src:v}",
                    new Values().with("v", "/lib/img.png")));
        }

        @Test
        public void linkTypeWithExternalizedHrefIsNormalizedBackToRelative() throws Exception {
            // simulates an AEM Cloud Service publisher's XSSAPI implicitly externalizing a
            // relative href with an absolute host - adjustLink must strip that host back off
            assertEquals("/apps/cpm/test/some/page.html", render(MAPPING_BUILDER, "${link:v}",
                    new Values().with("v", "/apps/cpm/test/some/page.html")));
        }

        @Test
        public void srcTypeWithExternalizedHrefIsNormalizedBackToRelative() throws Exception {
            assertEquals("/apps/cpm/test.test.resource.html/img.png", render(MAPPING_BUILDER, "${src:v}",
                    new Values().with("v", "/img.png")));
        }

        @Test
        public void linkTypeLeavesGenuineExternalUrlUnchanged() throws Exception {
            // a link that never contained this plugin's own server path (a real external URL)
            // must pass through both getValidHref and adjustLink untouched
            assertEquals("https://example.com/other", render(MAPPING_BUILDER, "${link:v}",
                    new Values().with("v", "https://example.com/other")));
        }

        @Test
        public void cssTypeUsesCssStringEncoding() throws Exception {
            assertEquals("CSS[a\"b]", render(TAGGING_BUILDER, "${css:v}",
                    new Values().with("v", "a\"b")));
        }

        @Test
        public void jsTypeUsesJsStringEncoding() throws Exception {
            assertEquals("JS[a'b]", render(TAGGING_BUILDER, "${js:v}",
                    new Values().with("v", "a'b")));
        }

        @Test
        public void i18nTypeTranslatesThenAppliesHtmlEncoding() throws Exception {
            ResourceBundle bundle = new ListResourceBundle() {
                @Override
                protected Object[][] getContents() {
                    return new Object[][]{{"greeting.key", "Hallo"}};
                }
            };
            assertEquals("HTML[Hallo]", render(TAGGING_BUILDER, "${i18n:v}",
                    new Values().with("v", "greeting.key"), bundle));
        }

        @Test
        public void i18nTypeWithoutBundleKeepsRawKeyEncoded() throws Exception {
            assertEquals("HTML[greeting.key]", render(TAGGING_BUILDER, "${i18n:v}",
                    new Values().with("v", "greeting.key")));
        }

        @Test
        public void i18nTypeWithMissingBundleEntryKeepsRawKeyEncoded() throws Exception {
            ResourceBundle bundle = new ListResourceBundle() {
                @Override
                protected Object[][] getContents() {
                    return new Object[][]{{"other.key", "Something else"}};
                }
            };
            assertEquals("HTML[greeting.key]", render(TAGGING_BUILDER, "${i18n:v}",
                    new Values().with("v", "greeting.key"), bundle));
        }
    }

    // --- include / include.name --------------------------------------------------------------

    @Nested
    class Include {

        @Test
        public void includeOfInlineStringExpressionUsesSameContext() throws Exception {
            assertEquals("<b>Hi</b>", render("${include:frag}", new Values()
                    .with("frag", "<b>${title}</b>")
                    .with("title", "Hi")));
        }

        @Test
        public void includeOfAbsolutePathResourceUsesSameContext() throws Exception {
            assertEquals("<b>Hi</b>", render("${include:target}", new Values()
                    .with("target", "/test/include-target.html")
                    .with("title", "Hi")));
        }

        @Test
        public void includeWithNameBindsValueIntoNestedContext() throws Exception {
            assertEquals("<span>X</span>", render("${include.item:value;'<span>${item}</span>'}",
                    new Values().with("value", "X")));
        }

        @Test
        public void includeOfUndefinedValueRendersNothing() throws Exception {
            assertEquals("beforeafter", render("before${include:missing}after", new Values()));
        }

        @Test
        public void includeWithNameAndUnresolvableTemplateExpressionRendersNothing() throws Exception {
            // 'missingFormatKey' is not a quoted literal and not present in the context
            assertEquals("X::Y", render("X:${include.item:value;missingFormatKey}:Y",
                    new Values().with("value", "X")));
        }
    }

    // --- each / each.name ----------------------------------------------------------------------

    @Nested
    class Each {

        @Test
        public void eachOverListUsesCustomItemName() throws Exception {
            assertEquals("<i>0:a</i><i>1:b</i>", render("${each.p:parts;'<i>${pIndex}:${p}</i>'}",
                    new Values().with("parts", List.of("a", "b"))));
        }

        @Test
        public void eachOverArray() throws Exception {
            assertEquals("<i>0:x</i><i>1:y</i>", render("${each.p:parts;'<i>${pIndex}:${p}</i>'}",
                    new Values().with("parts", new String[]{"x", "y"})));
        }

        @Test
        public void eachOverIterator() throws Exception {
            assertEquals("<i>0:m</i><i>1:n</i>", render("${each.p:parts;'<i>${pIndex}:${p}</i>'}",
                    new Values().with("parts", List.of("m", "n").iterator())));
        }

        @Test
        public void eachOverMapIteratesEntriesWithKeyAndValue() throws Exception {
            Map<String, String> parts = new LinkedHashMap<>();
            parts.put("p1", "P1");
            parts.put("p2", "P2");
            assertEquals("<i>0:p1=P1</i><i>1:p2=P2</i>",
                    render("${each.e:parts;'<i>${eIndex}:${e.key}=${e.value}</i>'}",
                            new Values().with("parts", parts)));
        }

        @Test
        public void eachWithoutNameUsesDefaultItemAndItemIndex() throws Exception {
            assertEquals("<i>0:a</i><i>1:b</i>", render("${each:parts;'<i>${itemIndex}:${item}</i>'}",
                    new Values().with("parts", List.of("a", "b"))));
        }

        @Test
        public void eachOverEmptyIterableRendersNothing() throws Exception {
            assertEquals("AB", render("A${each.p:parts;'<i>${p}</i>'}B",
                    new Values().with("parts", List.of())));
        }

        @Test
        public void eachWithBlankFormatRendersNothing() throws Exception {
            assertEquals("AB", render("A${each:parts;}B",
                    new Values().with("parts", List.of("a", "b"))));
        }

        @Test
        public void eachOfUndefinedIterableRendersNothing() throws Exception {
            assertEquals("AB", render("A${each:missing;'<i>${item}</i>'}B", new Values()));
        }

        @Test
        public void eachWithUnresolvableTemplatePathRendersNothing() throws Exception {
            // the template path is fixed and fails to resolve for every item - this is the
            // accepted 'silent abort' behavior of the each iteration
            assertEquals("AB", render("A${each:parts;'/does/not/exist'}B",
                    new Values().with("parts", List.of("a", "b"))));
        }
    }

    // --- if / if-else --------------------------------------------------------------------------

    @Nested
    class IfElse {

        @Test
        public void trueConditionEmbedsTemplate() throws Exception {
            assertEquals("<b>Yes</b>", render("${if:flag;'<b>Yes</b>'}",
                    new Values().with("flag", true)));
        }

        @Test
        public void falseConditionEmbedsNothing() throws Exception {
            assertEquals("", render("${if:flag;'<b>Yes</b>'}",
                    new Values().with("flag", false)));
        }

        @Test
        public void trueConditionEmbedsTrueBranchOfIfElse() throws Exception {
            assertEquals("<b>Yes</b>", render("${if:flag;'<b>Yes</b>'|'<i>No</i>'}",
                    new Values().with("flag", true)));
        }

        @Test
        public void falseConditionEmbedsFalseBranchOfIfElse() throws Exception {
            assertEquals("<i>No</i>", render("${if:flag;'<b>Yes</b>'|'<i>No</i>'}",
                    new Values().with("flag", false)));
        }

        @Test
        public void nonEmptyCollectionIsTruthy() throws Exception {
            assertEquals("has", render("${if:list;'has'|'empty'}",
                    new Values().with("list", List.of("x"))));
        }

        @Test
        public void emptyCollectionIsFalsy() throws Exception {
            assertEquals("empty", render("${if:list;'has'|'empty'}",
                    new Values().with("list", List.of())));
        }

        @Test
        public void nonEmptyMapIsTruthy() throws Exception {
            assertEquals("has", render("${if:map;'has'|'empty'}",
                    new Values().with("map", Map.of("a", "1"))));
        }

        @Test
        public void emptyMapIsFalsy() throws Exception {
            assertEquals("empty", render("${if:map;'has'|'empty'}",
                    new Values().with("map", Map.of())));
        }

        @Test
        public void nonEmptyArrayIsTruthy() throws Exception {
            assertEquals("has", render("${if:arr;'has'|'empty'}",
                    new Values().with("arr", new String[]{"x"})));
        }

        @Test
        public void emptyArrayIsFalsy() throws Exception {
            assertEquals("empty", render("${if:arr;'has'|'empty'}",
                    new Values().with("arr", new String[0])));
        }

        @Test
        public void nonEmptyStringIsTruthy() throws Exception {
            assertEquals("has", render("${if:s;'has'|'empty'}", new Values().with("s", "x")));
        }

        @Test
        public void emptyStringIsFalsy() throws Exception {
            assertEquals("empty", render("${if:s;'has'|'empty'}", new Values().with("s", "")));
        }

        @Test
        public void negatedConditionInvertsTruth() throws Exception {
            assertEquals("<b>Yes</b>", render("${if:!flag;'<b>Yes</b>'|'<i>No</i>'}",
                    new Values().with("flag", false)));
        }

        @Test
        public void undefinedConditionIsFalsy() throws Exception {
            assertEquals("<i>No</i>", render("${if:missing;'<b>Yes</b>'|'<i>No</i>'}", new Values()));
        }

        @Test
        public void unquotedBranchesAreResolvedAsContextExpressions() throws Exception {
            assertEquals("<b>Hi</b>", render("${if:flag;trueTmpl|falseTmpl}", new Values()
                    .with("flag", true)
                    .with("trueTmpl", "<b>${title}</b>")
                    .with("falseTmpl", "<i>none</i>")
                    .with("title", "Hi")));
        }

        @Test
        public void nestedPlaceholderInsideQuotedBranchUsesSameContext() throws Exception {
            assertEquals("<b>Hi</b>", render("${if:flag;'<b>${title}</b>'}",
                    new Values().with("flag", true).with("title", "Hi")));
        }
    }

    // --- escaping and malformed input --------------------------------------------------------

    @Nested
    class Escaping {

        @Test
        public void escapedDollarSignIsLiteral() throws Exception {
            assertEquals("a$b", render("a\\$b", new Values()));
        }

        @Test
        public void escapedBackslashIsLiteral() throws Exception {
            assertEquals("a\\b", render("a\\\\b", new Values()));
        }

        @Test
        public void backslashBeforeOtherCharacterIsKeptAsIs() throws Exception {
            String template = "a\\xb";
            assertEquals(template, render(template, new Values()));
        }

        @Test
        public void dollarNotFollowedByBraceIsLiteral() throws Exception {
            String template = "a$b";
            assertEquals(template, render(template, new Values()));
        }

        @Test
        public void trailingDollarAtEndOfStreamIsLiteral() throws Exception {
            String template = "abc$";
            assertEquals(template, render(template, new Values()));
        }

        @Test
        public void trailingBackslashAtEndOfStreamIsLiteral() throws Exception {
            String template = "abc\\";
            assertEquals(template, render(template, new Values()));
        }

        @Test
        public void unterminatedPlaceholderIsEchoedVerbatim() throws Exception {
            String template = "abc${untermin";
            assertEquals(template, render(template, new Values()));
        }
    }

    // --- embedded Reader values -----------------------------------------------------------------

    @Nested
    class ReaderEmbedding {

        @Test
        public void readerValueIsEmbeddedAsItIs() throws Exception {
            assertEquals("X<b>${title}</b>Y", render("X${body}Y", new Values()
                    .with("body", new StringReader("<b>${title}</b>"))
                    .with("title", "Hi")));
        }
    }

    // --- values at/around the STR_MAX buffering boundary ---------------------------------------

    @Nested
    class OversizedValues {

        @Test
        public void valueJustBelowStrMaxUsesBufferedPath() throws Exception {
            String value = "A".repeat(TemplateReader.STR_MAX - 1);
            assertEquals("X" + value + "Y", render("X${v}Y", new Values().with("v", value)));
        }

        @Test
        public void valueAtStrMaxUsesEmbeddedReaderPath() throws Exception {
            String value = "A".repeat(TemplateReader.STR_MAX);
            assertEquals("X" + value + "Y", render("X${v}Y", new Values().with("v", value)));
        }

        @Test
        public void oversizedValueIsEncodedBeforeItIsEmbedded() throws Exception {
            // regression test: the type-specific XSS encoding must run before the STR_MAX check,
            // otherwise long values would bypass encoding entirely
            String value = "A".repeat(TemplateReader.STR_MAX);
            assertEquals("ATTR[" + value + "]", render(TAGGING_BUILDER, "${attr:v}",
                    new Values().with("v", value)));
        }
    }
}
