package com.composum.sling.browser;

import com.composum.sling.tools.Result;
import com.composum.sling.tools.template.TemplateContext.Values;
import org.apache.sling.api.SlingHttpServletRequest;
import org.apache.sling.api.SlingHttpServletResponse;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.Collection;
import java.util.List;
import java.util.Map;

import static javax.servlet.http.HttpServletResponse.SC_BAD_REQUEST;

public interface Actions {

    interface Action extends Values.Provider {

        @Nullable String link();

        default @NotNull Result<?> process(@NotNull SlingHttpServletRequest request,
                                           @NotNull SlingHttpServletResponse response,
                                           @NotNull List<String> selectors) {
            return new Result<>(SC_BAD_REQUEST);
        }
    }

    @NotNull Map<String, Action> set(@NotNull SlingHttpServletRequest request);

    @NotNull Collection<String> styles();

    @NotNull Collection<String> scripts();
}
