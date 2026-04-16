import * as os from "node:os";
import * as fs from "node:fs";
import * as path from "node:path";

import {
	LanguageClient,
	type LanguageClientOptions,
	type ServerOptions,
	TransportKind,
} from "vscode-languageclient/node";
import * as vscode from "vscode";

let client: LanguageClient;
let statusBarItem: vscode.StatusBarItem;

export async function activate(context: vscode.ExtensionContext) {
	statusBarItem = vscode.window.createStatusBarItem(
		vscode.StatusBarAlignment.Right,
		100,
	);
	statusBarItem.text = "$(sync~spin) Nox LSP: Starting...";
	statusBarItem.show();
	context.subscriptions.push(statusBarItem);

	const disassembleProvider = new (class
		implements vscode.TextDocumentContentProvider
	{
		private _onDidChange = new vscode.EventEmitter<vscode.Uri>();
		get onDidChange(): vscode.Event<vscode.Uri> {
			return this._onDidChange.event;
		}

		async provideTextDocumentContent(uri: vscode.Uri): Promise<string> {
			return `// Disassembly for ${uri.query}\n// Run nox disassemble to view actual output.`;
		}
	})();
	context.subscriptions.push(
		vscode.workspace.registerTextDocumentContentProvider(
			"nox-disasm",
			disassembleProvider,
		),
	);

	context.subscriptions.push(
		vscode.commands.registerCommand("nox.run", async () => {
			const editor = vscode.window.activeTextEditor;
			if (!editor) return;
			const filePath = editor.document.fileName;
			const terminal = vscode.window.createTerminal(`Nox Run`);
			terminal.show();
			terminal.sendText(`nox run "${filePath}"`);
		}),
	);

	context.subscriptions.push(
		vscode.commands.registerCommand("nox.disassemble", async () => {
			const editor = vscode.window.activeTextEditor;
			if (!editor) return;
			const uri = vscode.Uri.parse(
				`nox-disasm:Disassembly?${editor.document.fileName}`,
			);
			const doc = await vscode.workspace.openTextDocument(uri);
			await vscode.window.showTextDocument(doc, {
				preview: true,
				viewColumn: vscode.ViewColumn.Beside,
			});
		}),
	);

	context.subscriptions.push(
		vscode.commands.registerCommand("nox.format", async () => {
			vscode.commands.executeCommand("editor.action.formatDocument");
		}),
	);

	context.subscriptions.push(
		vscode.commands.registerCommand("nox.restartServer", async () => {
			if (client) {
				statusBarItem.text = "$(sync~spin) Nox LSP: Restarting...";
				await client.stop();
				await client.start();
				statusBarItem.text = "$(check) Nox LSP: Running";
			}
		}),
	);

	context.subscriptions.push(
		vscode.commands.registerCommand("nox.version", async () => {
			vscode.window.showInformationMessage("Nox Compiler & LSP Version 0.1.0");
		}),
	);

	context.subscriptions.push(
		vscode.commands.registerCommand("nox.downloadServer", async () => {
			await ensureLspBinary(context, true);
			vscode.commands.executeCommand("nox.restartServer");
		}),
	);

	context.subscriptions.push(
		vscode.workspace.onDidChangeConfiguration((e) => {
			if (e.affectsConfiguration("nox.lsp.path")) {
				vscode.commands.executeCommand("nox.restartServer");
			}
		})
	);

	try {
		const serverPath = await ensureLspBinary(context);
		await startLanguageClient(serverPath, context);
		statusBarItem.text = "$(check) Nox LSP: Running";
	} catch (e) {
		statusBarItem.text = "$(error) Nox LSP: Error";
		vscode.window.showErrorMessage(`Failed to start Nox LSP: ${e instanceof Error ? e.message : String(e)}`);
	}
}

async function ensureLspBinary(
	context: vscode.ExtensionContext,
	forceDownload: boolean = false,
): Promise<string> {
	const config = vscode.workspace.getConfiguration("nox");
	const customPath = config.get<string>("lsp.path");
	if (customPath) {
		return customPath;
	}

	const platform = os.platform();
	const arch = os.arch();
	let binaryName = `nox-lsp-${platform}-${arch}`;
	if (platform === "win32") binaryName += ".exe";

	const globalStorage = context.globalStorageUri.fsPath;
	if (!fs.existsSync(globalStorage)) {
		fs.mkdirSync(globalStorage, { recursive: true });
	}

	const binaryPath = path.join(globalStorage, binaryName);

	if (!fs.existsSync(binaryPath) || forceDownload) {
		vscode.window.showInformationMessage(
			`Downloading Nox LSP for ${platform}-${arch}...`,
		);

		// Mock download logic
		fs.writeFileSync(binaryPath, "#!/bin/sh\necho 'Mock Nox LSP'");
		if (platform !== "win32") {
			fs.chmodSync(binaryPath, 0o755);
		}

		vscode.window.showInformationMessage("Download complete!");
	}

	return binaryPath;
}

async function startLanguageClient(
	serverPath: string,
	_context: vscode.ExtensionContext,
) {
	const serverOptions: ServerOptions = {
		run: { command: serverPath, transport: TransportKind.stdio },
		debug: { command: serverPath, transport: TransportKind.stdio },
	};

	const clientOptions: LanguageClientOptions = {
		documentSelector: [{ scheme: "file", language: "nox" }],
	};

	client = new LanguageClient(
		"noxLanguageServer",
		"Nox Language Server",
		serverOptions,
		clientOptions,
	);

	await client.start();
}

export function deactivate(): Thenable<void> | undefined {
	if (!client) {
		return undefined;
	}
	return client.stop();
}
