package com.composum.sling.changes;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.text.SimpleDateFormat;
import java.util.Calendar;

/**
 * One entry of a {@link ChangeSession}'s pending-changes log - a plain, immutable record of a
 * single staged mutation, rendered directly by the template engine (a "functional log entry" in
 * the sense the feature was asked for: nothing here mutates once created). Not JSON-serialized
 * anywhere - the pending-changes panel is server-rendered HTML, like every other detail view in
 * this project.
 */
public class Change {

    private static final String TIME_FORMAT = "HH:mm:ss";

    private final String action;
    private final String path;
    private final String detail;
    private final Calendar time;

    /**
     * @param action a short human label, e.g. "Created", "Deleted", "Moved", "Copied", "Changed"
     * @param path   the affected resource's path
     * @param detail additional detail, e.g. the destination path for a move/copy, or
     *               '{@code name = value}' for a property change - 'null' if there is none
     */
    public Change(@NotNull final String action, @NotNull final String path, @Nullable final String detail) {
        this.action = action;
        this.path = path;
        this.detail = detail;
        this.time = Calendar.getInstance();
    }

    public @NotNull String getAction() {
        return action;
    }

    public @NotNull String getPath() {
        return path;
    }

    public @Nullable String getDetail() {
        return detail;
    }

    public @NotNull String getTime() {
        return new SimpleDateFormat(TIME_FORMAT).format(time.getTime());
    }
}
