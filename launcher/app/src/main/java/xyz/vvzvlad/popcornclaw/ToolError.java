package xyz.vvzvlad.popcornclaw;

/**
 * A tool-level failure: surfaced as an MCP result with isError=true, never a JSON-RPC error. Moved out of
 * {@link TvMcpServer} into its own top-level class so {@link Tv} can throw it and TvMcpServer catches it,
 * mirroring the Python port where tv.py raises {@code ToolError} and the MCP server turns it into an error
 * result.
 */
final class ToolError extends Exception {
    ToolError(String m) {
        super(m);
    }
}
