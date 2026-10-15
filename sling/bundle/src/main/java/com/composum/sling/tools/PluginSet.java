package com.composum.sling.tools;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Collection;
import java.util.Collections;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

/**
 * A set of plugins ranked by their rank and ordered by their key, with at most one plugin per key.
 *
 * @param <T> the plugin type
 */
public abstract class PluginSet<T extends Plugin> {

    private static final Logger LOG = LoggerFactory.getLogger(PluginSet.class);

    /** Default constructor. */
    public PluginSet() {
    }

    /** the attached plugins by their key */
    protected final Map<String, T> pluginMap = new TreeMap<>();

    /** the attached plugins ordered by descending rank and then by key */
    protected final Set<T> pluginSet = new TreeSet<>((o1, o2) -> {
        final int rankOrder = Integer.compare(o2.rank(), o1.rank());
        return rankOrder != 0 ? rankOrder : o1.key().compareTo(o2.key());
    });

    /**
     * Checks if a plugin can be attached.
     *
     * @param service the plugin to check
     * @return {@code true} if the plugin is enabled
     */
    protected abstract boolean isEnabled(@NotNull T service);

    /**
     * Attaches an enabled plugin; a plugin with the same key and a higher rank is kept.
     *
     * @param service the plugin to attach
     */
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
                LOG.info("attached {} to '{}' ({}:{})", service, key, pluginSet.size(), pluginSet);
            }
        }
    }

    /**
     * Detaches the plugin attached with the key of the given plugin.
     *
     * @param service the plugin to detach
     */
    @SuppressWarnings("unused")
    public void detach(@NotNull final T service) {
        synchronized (pluginMap) {
            final T removed = pluginMap.remove(service.key());
            if (removed != null) {
                pluginSet.remove(removed);
                LOG.info("detached {} to '{}' ({}:{})", service, service.key(), pluginSet.size(), pluginSet);
            }
        }
    }

    /**
     * Retrieves a plugin by its key.
     *
     * @param key the plugin key
     * @return the plugin; {@code null} if there is none for the key
     */
    public @Nullable T get(@Nullable final String key) {
        return Optional.ofNullable(key).map(pluginMap::get).orElse(null);
    }

    /**
     * Lists the attached plugins in their order.
     *
     * @return an unmodifiable view of the plugins
     */
    public @NotNull Collection<T> list() {
        return Collections.unmodifiableCollection(pluginSet);
    }
}
