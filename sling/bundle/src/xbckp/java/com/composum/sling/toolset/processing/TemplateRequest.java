package com.composum.sling.tools.processing;

import org.apache.sling.api.SlingHttpServletRequest;
import org.apache.sling.api.SlingHttpServletResponse;
import org.apache.sling.api.request.RequestPathInfo;
import org.apache.sling.api.resource.Resource;
import org.apache.sling.api.resource.ResourceResolver;
import org.apache.sling.api.wrappers.SlingHttpServletRequestWrapper;
import org.apache.sling.engine.SlingRequestProcessor;
import org.apache.sling.servlethelpers.internalrequests.InternalRequest;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.servlet.ServletException;
import java.io.IOException;

public class TemplateRequest extends InternalRequest {

    private static final Logger LOG = LoggerFactory.getLogger(TemplateRequest.class);

    protected static class RequestWrapper extends SlingHttpServletRequestWrapper {

        protected class PathInfoWrapper implements RequestPathInfo {

            protected final RequestPathInfo pathInfo;

            public PathInfoWrapper(RequestPathInfo pathInfo) {
                this.pathInfo = pathInfo;
            }

            @Override
            public @NotNull String getResourcePath() {
                final String result = TemplateResolver.resourcePath(template.getPath());
                LOG.info("PathInfo.getResourcePath(): {}", result);
                return result;
            }

            @Override
            public @Nullable String getExtension() {
                return pathInfo.getExtension();
            }

            @Override
            public @Nullable String getSelectorString() {
                return pathInfo.getSelectorString();
            }

            @Override
            public @NotNull String[] getSelectors() {
                return pathInfo.getSelectors();
            }

            @Override
            public @Nullable String getSuffix() {
                return pathInfo.getSuffix();
            }

            @Override
            public @Nullable Resource getSuffixResource() {
                return pathInfo.getSuffixResource();
            }
        }

        protected final Resource template;
        protected final PathInfoWrapper pathInfo;

        public RequestWrapper(SlingHttpServletRequest wrappedRequest,
                              Resource template) {
            super(wrappedRequest);
            this.template = template;
            this.pathInfo = new PathInfoWrapper(wrappedRequest.getRequestPathInfo());
        }

        @Override
        public @NotNull RequestPathInfo getRequestPathInfo() {
            return pathInfo;
        }
    }

    protected final SlingRequestProcessor processor;
    protected final Resource template;

    /** Setup an internal request that uses a SlingRequestProcessor */
    public TemplateRequest(@NotNull final SlingRequestProcessor processor, @NotNull final Resource template) {
        super(template.getResourceResolver(), TemplateResolver.resourcePath(template.getPath()));
        checkNotNull(SlingRequestProcessor.class, processor);
        this.processor = processor;
        this.template = template;
    }

    /** Return essential request info, used to set the logging MDC */
    public String toString() {
        return String.format(
                "%s: %s P=%s S=%s EXT=%s",
                getClass().getSimpleName(),
                requestMethod,
                path,
                selectorString,
                extension
        );
    }

    @Override
    protected void delegateExecute(SlingHttpServletRequest request, SlingHttpServletResponse response, ResourceResolver resourceResolver)
            throws ServletException, IOException {
        log.info("Executing request using a SlingRequestProcessor");
        processor.processRequest(new RequestWrapper(request, template), response, resourceResolver);
    }

    @Override
    protected Resource getExecutionResource() {
        return template;
    }
}
