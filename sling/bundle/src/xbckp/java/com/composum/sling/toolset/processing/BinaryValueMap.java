package com.composum.sling.tools.processing;

import com.composum.sling.tools.Common;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.io.InputStream;
import java.util.Set;
import java.util.function.Supplier;

import static com.composum.sling.tools.Common.JCR_DATA;
import static com.composum.sling.tools.Common.JCR_MIME_TYPE;
import static com.composum.sling.tools.Common.JCR_PRIMARY_TYPE;
import static com.composum.sling.tools.Common.NT_RESOURCE;

public class BinaryValueMap extends GenericValueMap {

    public static final Set<String> keys = Set.of(
            JCR_PRIMARY_TYPE,
            JCR_MIME_TYPE,
            JCR_DATA
    );

    public BinaryValueMap(@NotNull final String path) {
        super(path);
    }

    @Override
    protected @NotNull Set<String> keys() {
        return keys;
    }

    protected @Nullable <T> Supplier<T> supplier(@NotNull String name, @NotNull Class<T> type) {
        switch (name) {
            case JCR_PRIMARY_TYPE:
                return type.isAssignableFrom(String.class)? () -> type.cast(NT_RESOURCE) : null;
            case JCR_MIME_TYPE:
                return type.isAssignableFrom(String.class)? () -> type.cast(Common.pathMimeType(path)) : null;
            case JCR_DATA:
                return type.isAssignableFrom(InputStream.class)? () -> type.cast(getBinaryData()) : null;
            default:
                return null;
        }
    }

    protected InputStream getBinaryData() {
        return getClass().getResourceAsStream(path);
    }
}
