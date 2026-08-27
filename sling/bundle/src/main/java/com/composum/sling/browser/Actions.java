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

/**
 * A pluggable set of resource actions (e.g. edit, activate) shown in the browser's action bar.
 */
public interface Actions {

    /**
     * One action offered for the currently selected resource.
     */
    interface Action extends Values.Provider {

        /**
         * The URL to invoke for this action.
         *
         * @return the URL to invoke for this action, or 'null' if the action is not available for
         * the current resource
         */
        @Nullable String link();

        /**
         * The method to use for applying the link.
         *
         * @return the method to use for applying the link, if 'null' it a pure link, otherwise
         * an ajax request should be used to apply this link
         */
        default @Nullable String method() {
            return null;
        }

        /**
         * Handles a request routed to this action (e.g. via a redirect from {@link #link()}).
         *
         * @param request   the current request
         * @param response  the response to write to
         * @param selectors the request selectors remaining after routing
         * @return the result to render for this request; 'Bad Request' by default
         */
        default @NotNull Result<?> process(@NotNull SlingHttpServletRequest request,
                                           @NotNull SlingHttpServletResponse response,
                                           @NotNull List<String> selectors) {
            return new Result<>(SC_BAD_REQUEST);
        }
    }

    /**
     * The actions available for the current request's target resource.
     *
     * @param request the current request
     * @return the actions available for the current request's target resource, keyed by their key
     */
    @NotNull Map<String, Action> set(@NotNull SlingHttpServletRequest request);

    /**
     * The client-side stylesheet resource paths this action set needs.
     *
     * @return the client-side stylesheet resource paths this action set needs
     */
    @NotNull Collection<String> styles();

    /**
     * The client-side script resource paths this action set needs.
     *
     * @return the client-side script resource paths this action set needs
     */
    @NotNull Collection<String> scripts();
}
