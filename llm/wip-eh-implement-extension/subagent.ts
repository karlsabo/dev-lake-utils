import {spawn} from "node:child_process";
import {existsSync} from "node:fs";
import {basename} from "node:path";
import {CancellationError, cancellationError, throwIfCancelled} from "./cancellation.ts";

export interface SubagentOptions {
	cwd: string;
	model: string;
	thinkingLevel?: string;
	timeoutMs?: number;
	signal?: AbortSignal;
	killGraceMs?: number;
}

const DEFAULT_TIMEOUT_MS = 20 * 60 * 1_000;
const TOOLS = "read,bash,edit,write,grep,find,ls";
export const WORKFLOW_SUBAGENT_ENV = "PI_WIP_EH_IMPLEMENT_SUBAGENT";

export function isWorkflowSubagent(environment: NodeJS.ProcessEnv = process.env): boolean {
	return environment[WORKFLOW_SUBAGENT_ENV] === "1";
}

export function piSubagentArgs(prompt: string, options: SubagentOptions): string[] {
	const thinkingArgs = options.thinkingLevel ? ["--thinking", options.thinkingLevel] : [];
	return [
		"--mode",
		"json",
		"-p",
		"--no-session",
		"--model",
		options.model,
		...thinkingArgs,
		"--tools",
		TOOLS,
		"--",
		prompt,
	];
}

export async function runPiSubagent(
	prompt: string,
	options: SubagentOptions,
	spawnProcess: typeof spawn = spawn,
): Promise<string> {
	throwIfCancelled(options.signal);
	const invocation = piInvocation(piSubagentArgs(prompt, options));

	return new Promise<string>((resolve, reject) => {
		const child = spawnProcess(invocation.command, invocation.args, {
			cwd: options.cwd,
			env: { ...process.env, [WORKFLOW_SUBAGENT_ENV]: "1" },
			shell: false,
			stdio: ["ignore", "pipe", "pipe"],
		});
		let stdoutBuffer = "";
		let stderr = "";
		let finalText = "";
		let modelError = "";
		let settled = false;
		let termination: Error | undefined;
		let timeout: NodeJS.Timeout | undefined;
		let killTimer: NodeJS.Timeout | undefined;

		const processLine = (line: string) => {
			if (!line.trim()) return;
			try {
				const event = JSON.parse(line) as JsonEvent;
				if (event.type !== "message_end" || event.message?.role !== "assistant") return;
				const text = event.message.content
					?.filter((part) => part.type === "text" && typeof part.text === "string")
					.map((part) => part.text)
					.join("\n")
					.trim();
				if (text) finalText = text;
				if (event.message.stopReason === "error") modelError = event.message.errorMessage ?? "Subagent model failed";
			} catch {
				// JSON mode may include non-event diagnostics; stderr and exit status remain authoritative.
			}
		};
		const onStdout = (chunk: Buffer | string) => {
			stdoutBuffer += chunk.toString();
			const lines = stdoutBuffer.split("\n");
			stdoutBuffer = lines.pop() ?? "";
			lines.forEach(processLine);
		};
		const onStderr = (chunk: Buffer | string) => {
			stderr += chunk.toString();
		};
		const cleanup = () => {
			if (timeout) clearTimeout(timeout);
			if (killTimer) clearTimeout(killTimer);
			options.signal?.removeEventListener("abort", onAbort);
			child.stdout.removeListener("data", onStdout);
			child.stderr.removeListener("data", onStderr);
			child.removeListener("error", onError);
			child.removeListener("close", onClose);
		};
		const settle = (error?: Error, value?: string) => {
			if (settled) return;
			settled = true;
			cleanup();
			if (error) reject(error);
			else resolve(value ?? "");
		};
		const terminate = (reason: Error) => {
			if (settled) return;
			if (termination) {
				if (reason instanceof CancellationError) termination = reason;
				return;
			}
			termination = reason;
			child.kill("SIGTERM");
			killTimer = setTimeout(() => {
				if (!settled) child.kill("SIGKILL");
			}, options.killGraceMs ?? 5_000);
			killTimer.unref();
		};
		function onAbort() {
			terminate(options.signal ? cancellationError(options.signal) : new CancellationError());
		}
		function onError(error: Error) {
			settle(termination ?? error);
		}
		function onClose(code: number | null) {
			processLine(stdoutBuffer);
			if (termination) settle(termination);
			else if (code !== 0 || modelError) {
				settle(new Error(modelError || stderr.trim() || `Subagent exited with code ${code}`));
			} else if (!finalText) settle(new Error("Subagent returned no final response"));
			else settle(undefined, finalText);
		}

		child.stdout.on("data", onStdout);
		child.stderr.on("data", onStderr);
		child.on("error", onError);
		child.on("close", onClose);
		options.signal?.addEventListener("abort", onAbort, {once: true});
		timeout = setTimeout(
			() => terminate(new Error("Subagent timed out")),
			options.timeoutMs ?? DEFAULT_TIMEOUT_MS,
		);
		timeout.unref();
		if (options.signal?.aborted) onAbort();
	});
}

interface JsonEvent {
	type?: string;
	message?: {
		role?: string;
		content?: Array<{ type?: string; text?: string }>;
		stopReason?: string;
		errorMessage?: string;
	};
}

function piInvocation(args: string[]): { command: string; args: string[] } {
	const currentScript = process.argv[1];
	if (currentScript && !currentScript.startsWith("/$bunfs/root/") && existsSync(currentScript)) {
		return { command: process.execPath, args: [currentScript, ...args] };
	}
	const executable = basename(process.execPath).toLowerCase();
	return /^(node|bun)(\.exe)?$/.test(executable)
		? { command: "pi", args }
		: { command: process.execPath, args };
}
