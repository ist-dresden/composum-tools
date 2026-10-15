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

/**
 * The result of a tools request: status code, response headers and data.
 *
 * @param <T> the type of the result data
 */
public class Result<T> {

    /** the HTTP status code of the response */
    @Getter
    @Setter
    private Integer statusCode;
    /** the data to send as response content */
    @Getter
    @Setter
    private T data;
    /** if {@code true} JSON data is written with pretty printing */
    @Getter
    @Setter
    private boolean prettyPrint;

    private final Map<String, Object> headers = new LinkedHashMap<>();

    /**
     * Creates a successful result.
     *
     * @param data the result data
     */
    public Result(T data) {
        this(SC_OK, data);
    }

    /**
     * Creates a successful result with a content type.
     *
     * @param data        the result data
     * @param contentType the content type of the response
     */
    public Result(T data, @NotNull final String contentType) {
        this(SC_OK, contentType, data);
    }

    /**
     * Creates a result without data.
     *
     * @param statusCode the HTTP status code
     */
    public Result(int statusCode) {
        this(statusCode, null);
    }

    /**
     * Creates a result with a status code.
     *
     * @param statusCode the HTTP status code
     * @param data       the result data
     */
    public Result(int statusCode, T data) {
        this(statusCode, null, null, data);
    }

    /**
     * Creates a result with a status code and a content type.
     *
     * @param statusCode  the HTTP status code
     * @param contentType the content type of the response
     * @param data        the result data
     */
    public Result(int statusCode, @Nullable String contentType, T data) {
        this(statusCode, contentType, null, data);
    }

    /**
     * Creates a result with a status code, content type and content length.
     *
     * @param statusCode    the HTTP status code
     * @param contentType   the content type of the response
     * @param contentLength the content length of the response
     * @param data          the result data
     */
    public Result(int statusCode,
                  @Nullable final String contentType, @Nullable final Integer contentLength,
                  @Nullable final T data) {
        this.statusCode = statusCode;
        this.data = data;
        setContentType(contentType);
        setContentLength(contentLength);
    }

    /**
     * Creates a result with a status code and response headers.
     *
     * @param statusCode the HTTP status code
     * @param headers    the response headers
     * @param data       the result data
     */
    public Result(int statusCode,
                  @NotNull final Map<String, Object> headers,
                  @Nullable final T data) {
        this.statusCode = statusCode;
        for (Map.Entry<String, Object> entry : headers.entrySet()) {
            setHeader(entry.getKey(), entry.getValue());
        }
        this.data = data;
    }

    /**
     * Sets or removes the content type header.
     *
     * @param contentType the content type; {@code null} removes the header
     */
    public void setContentType(@Nullable final String contentType) {
        setHeader(HTTP_CONTENT_TYPE, contentType);
    }

    /**
     * Sets or removes the content length header.
     *
     * @param contentLength the content length; {@code null} removes the header
     */
    public void setContentLength(@Nullable final Integer contentLength) {
        setHeader(HTTP_CONTENT_LENGTH, contentLength);
    }

    /**
     * Sets or removes a response header.
     *
     * @param name  the header name
     * @param value the header value; {@code null} removes the header
     */
    public void setHeader(@NotNull final String name, @Nullable final Object value) {
        if (value != null) {
            headers.put(name, value);
        } else {
            headers.remove(name);
        }
    }

    /**
     * Applies the headers of this result to a response.
     *
     * @param response the response to add the headers to
     */
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
