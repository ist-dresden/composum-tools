package com.composum.sling.tools;

import com.composum.sling.tools.dto.Widget;
import com.composum.sling.tools.template.Template;
import com.composum.sling.tools.template.TemplateContext;
import org.apache.sling.api.SlingHttpServletRequest;
import org.apache.sling.api.SlingHttpServletResponse;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import javax.servlet.http.HttpServletResponse;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Supplier;

public class TestToolsPlugin extends AbstractToolsPlugin {

    public TestToolsPlugin(@NotNull final Manager manager) {
        this.manager = manager;
    }

    @Override
    public @NotNull String key() {
        return "test";
    }

    @Override
    public @NotNull String label() {
        return "Test";
    }

    @Override
    public int rank() {
        return 0;
    }

    @Override
    public boolean isEnabled() {
        return true;
    }

    @Override
    public @NotNull List<Widget> widgets() {
        return List.of();
    }

    @Override
    public @NotNull Result<?> process(@NotNull SlingHttpServletRequest request,
                                      @NotNull SlingHttpServletResponse response,
                                      @NotNull List<String> selectors) {
        return new Result<>(HttpServletResponse.SC_BAD_REQUEST);
    }

    public final Map<String, Factory> templates = Map.of(
            "main", current -> new Template("/com/composum/test/main.html",
                    new TemplateContext(current, new TemplateContext.Values()
                            .with("page.label", "Test")
                            .with("page.title", "Tools Test")
                            .with("html.cssClasses", (Supplier<?>) () -> getHtmlCssClasses("test-page"))
                            .with(toolsValues())
                    ), this),
            "test", current -> new Template("/com/composum/test/test.txt",
                    new TemplateContext(current, new TemplateContext.Values()
                    ), this)
    );

    @Override
    public @Nullable Template getTemplate(@NotNull TemplateContext context, @NotNull String key) {
        return Optional.ofNullable(templates.get(key))
                .map(factory -> factory.create(context))
                .orElse(key.startsWith("/") ? new Template(key, context, this) : null);
    }
}
