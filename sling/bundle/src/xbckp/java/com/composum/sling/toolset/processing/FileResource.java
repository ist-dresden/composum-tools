package com.composum.sling.tools.processing;

import org.apache.sling.api.resource.Resource;
import org.apache.sling.api.resource.ResourceResolver;
import org.apache.sling.api.resource.ValueMap;
import org.apache.sling.api.wrappers.ValueMapDecorator;
import org.jetbrains.annotations.NotNull;

import java.util.Map;

import static com.composum.sling.tools.Common.JCR_CONTENT;
import static com.composum.sling.tools.Common.JCR_PRIMARY_TYPE;
import static com.composum.sling.tools.Common.NT_FILE;

public class FileResource extends GenericResource {

    public static final ValueMap FILE_PROPERTIES = new ValueMapDecorator(Map.of(
            JCR_PRIMARY_TYPE, NT_FILE
    ));

    public FileResource(@NotNull Resource parent, @NotNull String name) {
        super(parent, name, FILE_PROPERTIES);
        addFileContent();
    }

    public FileResource(@NotNull ResourceResolver resolver, @NotNull String path) {
        super(resolver, path, FILE_PROPERTIES);
        addFileContent();
    }

    protected void addFileContent() {
        addChildren(new GenericResource(this, JCR_CONTENT, new BinaryValueMap(getPath())));
    }
}
