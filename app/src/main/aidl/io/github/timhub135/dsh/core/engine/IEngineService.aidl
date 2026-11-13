package io.github.timhub135.dsh.core.engine;

interface IEngineService {
    /**
     * Starts Node + dsh once per process. Returns a Bundle with ParcelFileDescriptors:
     * "sdk_in" (write end of dsh stdin), "sdk_out" (read end of dsh stdout), "bridge" (capability socket).
     * Throws if the engine already ran in this process (Node can only start once per process).
     */
    Bundle start(in Bundle env);
    boolean isRunning();
}
