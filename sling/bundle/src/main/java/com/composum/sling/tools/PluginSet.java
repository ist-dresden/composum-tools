package com.composum.sling.tools;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

public abstract class PluginSet<T extends Plugin> {

    protected final Map<String, T> pluginMap = new TreeMap<>();
    protected final Set<T> pluginSet = new TreeSet<>((o1, o2) -> Integer.compare(o2.rank(), o1.rank()));

    protected abstract boolean isEnabled(@NotNull T service);

    public void attach(@NotNull final T service) {
        synchronized (pluginMap) {
            if (isEnabled(service)) {
                final String key = service.key();
                final T replaced = pluginMap.put(key, service);
                if (replaced != null) {
                    if (replaced.rank() > service.rank()) {
                        pluginMap.put(key, replaced);
                        return;
                    } else {
                        pluginSet.remove(replaced);
                    }
                }
                pluginSet.add(service);
            }
        }
    }

    @SuppressWarnings("unused")
    public void detach(@NotNull final T service) {
        synchronized (pluginMap) {
            final T removed = pluginMap.remove(service.key());
            if (removed != null) {
                pluginSet.remove(removed);
            }
        }
    }

    public @Nullable T get(@Nullable final String key) {
        return Optional.ofNullable(key).map(pluginMap::get).orElse(null);
    }

    public @NotNull Set<T> set() {
        return pluginSet;
    }
}
