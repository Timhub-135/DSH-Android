package io.github.jackwener.dsh.core.platform;

/** Runs inside Shizuku's shell-uid user service process. */
interface IShellService {
    void destroy() = 16777114;
    /** Runs `sh -c cmd`; returns "<exitCode>\n<combined output>". */
    String exec(String cmd, long timeoutMs) = 1;
    /** Creates the agent's virtual display (flags by Android version, keyboard kept on it); returns displayId or -1 (see lastDisplayError). */
    int createDisplay(int width, int height, int dpi) = 2;
    void releaseDisplay(int displayId) = 3;
    /** Latest frame of the virtual display as JPEG, scaled to at most maxWidth px wide (0 = full size); null if none. */
    byte[] captureDisplay(int displayId, int maxWidth) = 4;
    /** 1 when the latest frame is entirely black (protected window), 0 when not, -1 without a frame. */
    int frameBlank(int displayId) = 5;
    /** Tasks on every display, most recent first, one "taskId\tdisplayId\tpackage\ttopActivity\tstandard\tvisible" line each; null when unavailable. */
    String tasks() = 6;
    /** Moves a root task to another display (IActivityTaskManager.moveRootTaskToDisplay, `am display move-stack` as fallback). */
    boolean moveTask(int taskId, int displayId) = 7;
    /** appops PLAY_AUDIO ignore for pkg (the previous mode is kept on disk), or restores it. */
    boolean setAudioMuted(String pkg, boolean muted) = 8;
    /** Gives every app still muted by the ledger its previous PLAY_AUDIO mode back. */
    void restoreAudio() = 9;
    /** Why the last createDisplay returned -1 ("<Exception>: <message>" of the last flag set tried, earlier ones after it); null after a success. */
    String lastDisplayError() = 10;
    /** Re-acquires the agent display's wake lock with ACQUIRE_CAUSES_WAKEUP (it went OFF); false when it has none or it failed. */
    boolean wakeDisplay(int displayId) = 18;

    // ---- Android/data and Android/obb for FileTools (ShellFiles): java.io in this process, no shell strings.
    // Failures throw IllegalStateException / IllegalArgumentException with "<Exception>: <message>".
    /** "d|f\tsize\tmtimeMs\tpath" (a folder: total size of its files, then "\tfileCount"), or null when missing. */
    String fsStat(String path) = 11;
    /** Rows as fsStat (folders size 0), folders first; the last line is "#total N", or "#more" when a recursive walk hit the limit. */
    String fsList(String path, boolean recursive, int limit) = 12;
    /** Opens a file: mode "r", "w" (truncate) or "wa" (append); writes create the parent folders. */
    ParcelFileDescriptor fsOpen(String path, String mode) = 13;
    String fsMkdirs(String path) = 14;
    /** One file_move item ("moved a → b", "skip …", "failed …"); [to] ending in '/' means into that folder. */
    String fsMove(String from, String to, boolean copy) = 15;
    String fsDelete(String path) = 16;
    /** Matching file rows (sorted, at most limit), then "#total N" and optionally "#note …". */
    String fsSearch(String dir, String nameGlob, String content, boolean regex, int limit, String sort) = 17;
}
