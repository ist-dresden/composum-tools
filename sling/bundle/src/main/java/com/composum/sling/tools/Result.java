package com.composum.sling.tools;

import lombok.Getter;
import lombok.Setter;
import org.apache.sling.api.SlingHttpServletResponse;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.Date;
import java.util.LinkedHashMap;
import java.util.Map;

import static com.composum.sling.tools.Common.HTTP_CONTENT_LENGTH;
import static com.composum.sling.tools.Common.HTTP_CONTENT_TYPE;
import static javax.servlet.http.HttpServletResponse.SC_OK;

public class Result<T> {

    @Getter
    @Setter
    private Integer statusCode;
    @Getter
    @Setter
    private T data;
    @Getter
    @Setter
    private boolean prettyPrint;

    private final Map<String, Object> headers = new LinkedHashMap<>();

    public Result(T data) {
        this(SC_OK, data);
    }

    public Result(T data, @NotNull final String contentType) {
        this(SC_OK, contentType, data);
    }

    public Result(int statusCode) {
        this(statusCode, null);
    }

    public Result(int statusCode, T data) {
        this(statusCode, null, null, data);
    }

    public Result(int statusCode, @Nullable String contentType, T data) {
        this(statusCode, contentType, null, data);
    }

    public Result(int statusCode,
                  @Nullable final String contentType, @Nullable final Integer contentLength,
                  @Nullable final T data) {
        this.statusCode = statusCode;
        this.data = data;
        setContentType(contentType);
        setContentLength(contentLength);
    }

    public Result(int statusCode,
                  @NotNull final Map<String, Object> headers,
                  @Nullable final T data) {
        this.statusCode = statusCode;
        for (Map.Entry<String, Object> entry : headers.entrySet()) {
            setHeader(entry.getKey(), entry.getValue());
        }
        this.data = data;
    }

    public void setContentType(@Nullable final String contentType) {
        setHeader(HTTP_CONTENT_TYPE, contentType);
    }

    public void setContentLength(@Nullable final Integer contentLength) {
        setHeader(HTTP_CONTENT_LENGTH, contentLength);
    }

    public void setHeader(@NotNull final String name, @Nullable final Object value) {
        if (value != null) {
            headers.put(name, value);
        } else {
            headers.remove(name);
        }
    }

    public void addHeaders(@NotNull final SlingHttpServletResponse response) {
        for (Map.Entry<String, Object> entry : headers.entrySet()) {
            String name = entry.getKey();
            Object value = entry.getValue();
            if (HTTP_CONTENT_TYPE.equals(name)) {
                // must go through setContentType(), not addHeader(): only that also configures the
                // response Writer's character encoding, and is what response.getContentType() (as
                // inspected by e.g. AEM's link-checker response processing) actually reflects
                response.setContentType(value.toString());
            } else if (value instanceof Date) {
                response.setDateHeader(name, ((Date) value).getTime());
            } else {
                response.addHeader(name, value.toString());
            }
        }
    }
}
