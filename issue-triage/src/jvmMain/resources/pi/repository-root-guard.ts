import type {ExtensionAPI} from "@earendil-works/pi-coding-agent";
import {Buffer} from "node:buffer";
import {realpathSync} from "node:fs";
import {isAbsolute, relative, resolve, sep} from "node:path";

const GUARDED_TOOLS = new Set(["read", "grep", "find", "ls"]);

function isWithinRoot(path: string, root: string): boolean {
    const relativePath = relative(root, path);
    return relativePath === "" ||
        (!isAbsolute(relativePath) && relativePath !== ".." && !relativePath.startsWith(`..${sep}`));
}

export default function (pi: ExtensionAPI) {
    pi.registerFlag("triage-roots-base64", {
        description: "Base64url-encoded JSON array of repository roots available to issue triage tools",
        type: "string",
    });

    let roots: string[] | undefined;
    pi.on("session_start", () => {
        const configuredRoots = pi.getFlag("triage-roots-base64");
        if (typeof configuredRoots !== "string") {
            throw new Error("--triage-roots-base64 is required");
        }
        const decodedRoots = Buffer.from(configuredRoots, "base64url").toString("utf8");
        roots = (JSON.parse(decodedRoots) as string[]).map((root) => realpathSync(root));
        if (roots.length === 0) {
            throw new Error("--triage-roots-base64 must contain at least one path");
        }
    });

    pi.on("tool_call", (event, context) => {
        if (!GUARDED_TOOLS.has(event.toolName)) return;

        const input = event.input as { path?: string };
        const requestedPath = input.path?.replace(/^@/, "") ?? ".";
        let canonicalPath: string;
        try {
            canonicalPath = realpathSync(resolve(context.cwd, requestedPath));
        } catch {
            return { block: true, reason: `Path is unavailable: ${requestedPath}` };
        }
        if (!roots?.some((root) => isWithinRoot(canonicalPath, root))) {
            return { block: true, reason: `Path is outside configured repository roots: ${requestedPath}` };
        }
    });
}
