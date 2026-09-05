import {spawn} from "node:child_process";
import {existsSync} from "node:fs";
import {basename} from "node:path";

export interface SubagentOptions {
	cwd: string;
	model: string;
	thinkingLevel?: string;
	timeoutMs?: number;
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

export async function runPiSubagent(prompt: string, options: SubagentOptions): Promise<string> {
	const invocation = piInvocation(piSubagentArgs(prompt, options));

	return new Promise<string>((resolve, reject) => {
		const child = spawn(invocation.command, invocation.args, {
			cwd: options.cwd,
			env: { ...process.env, [WORKFLOW_SUBAGENT_ENV]: "1" },
			shell: false,
			stdio: ["ignore", "pipe", "pipe"],
		});
		let stdoutBuffer = "";
		let stderr = "";
		let finalText = "";
		let modelError = "";
		let timedOut = false;

		const timeout = setTimeout(() => {
			timedOut = true;
			child.kill("SIGTERM");
			setTimeout(() => child.kill("SIGKILL"), 5_000).unref();
		}, options.timeoutMs ?? DEFAULT_TIMEOUT_MS);

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

		child.stdout.on("data", (chunk) => {
			stdoutBuffer += chunk.toString();
			const lines = stdoutBuffer.split("\n");
			stdoutBuffer = lines.pop() ?? "";
			lines.forEach(processLine);
		});
		child.stderr.on("data", (chunk) => {
			stderr += chunk.toString();
		});
		child.on("error", (error) => {
			clearTimeout(timeout);
			reject(error);
		});
		child.on("close", (code) => {
			clearTimeout(timeout);
			processLine(stdoutBuffer);
			if (timedOut) {
				reject(new Error("Subagent timed out"));
			} else if (code !== 0 || modelError) {
				reject(new Error(modelError || stderr.trim() || `Subagent exited with code ${code}`));
			} else if (!finalText) {
				reject(new Error("Subagent returned no final response"));
			} else {
				resolve(finalText);
			}
		});
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
