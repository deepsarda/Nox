import * as os from "node:os";
import * as fs from "node:fs";
import * as path from "node:path";
import * as https from "node:https";
import { execFile, exec } from "node:child_process";
import { promisify } from "node:util";

const execFileAsync = promisify(execFile);

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
			const extensionVersion =
				context.extension.packageJSON.version ?? "unknown";
			let lspVersion = "not installed";
			try {
				const serverPath = await ensureLspBinary(context);
				const { stdout } = await execFileAsync(serverPath, ["--version"]);
				lspVersion = stdout.trim();
			} catch (err) {
				lspVersion = `error: ${(err as Error).message}`;
			}
			vscode.window.showInformationMessage(
				`Nox VS Code extension ${extensionVersion} — ${lspVersion}`,
			);
		}),
	);

	context.subscriptions.push(
		vscode.commands.registerCommand("nox.downloadServer", async () => {
			try {
				await ensureLspBinary(context, true);
				vscode.commands.executeCommand("nox.restartServer");
			} catch (e) {
				vscode.window.showErrorMessage(`Failed to update Nox LSP: ${e instanceof Error ? e.message : String(e)}`);
			}
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

interface GithubAsset {
	name: string;
	browser_download_url: string;
	size: number;
}

interface GithubRelease {
	tag_name: string;
	assets: GithubAsset[];
}

function getJson(url: string): Promise<any> {
	return new Promise((resolve, reject) => {
		https.get(url, { headers: { "User-Agent": "Nox-VSCode" } }, (res) => {
			if (res.statusCode !== 200) {
				reject(new Error(`GitHub API returned status ${res.statusCode}`));
				return;
			}
			let data = "";
			res.on("data", chunk => data += chunk);
			res.on("end", () => {
				try {
					resolve(JSON.parse(data));
				} catch (err) {
					reject(err);
				}
			});
		}).on("error", reject);
	});
}

function downloadFile(url: string, destPath: string): Promise<void> {
	return new Promise((resolve, reject) => {
		const file = fs.createWriteStream(destPath);
		const request = (targetUrl: string) => {
			https.get(targetUrl, { headers: { "User-Agent": "Nox-VSCode" } }, (res) => {
				if (res.statusCode && res.statusCode >= 300 && res.statusCode < 400 && res.headers.location) {
					request(res.headers.location);
					return;
				}
				if (res.statusCode !== 200) {
					reject(new Error(`Failed to download: status ${res.statusCode}`));
					return;
				}
				res.pipe(file);
				file.on("finish", () => {
					file.close();
					resolve();
				});
			}).on("error", (err) => {
				fs.unlink(destPath, () => {});
				reject(err);
			});
		};
		request(url);
	});
}

function extractArchive(archivePath: string, targetDir: string, osType: string, version: string, asset: string): Promise<void> {
	return new Promise((resolve, reject) => {
		const binDir = path.join(targetDir, "bin");
		if (osType === "win32") {
			const tempExtract = path.join(targetDir, "temp_extract");
			const extractCmd = `powershell.exe -NoProfile -Command "Expand-Archive -Path '${archivePath}' -DestinationPath '${tempExtract}' -Force"`;
			exec(extractCmd, (err) => {
				if (err) {
					reject(err);
					return;
				}
				try {
					const extractedBin = path.join(tempExtract, `nox-${version}-${asset}`, "bin");
					if (!fs.existsSync(binDir)) {
						fs.mkdirSync(binDir, { recursive: true });
					}
					const files = fs.readdirSync(extractedBin);
					for (const file of files) {
						fs.copyFileSync(path.join(extractedBin, file), path.join(binDir, file));
					}
					fs.rmSync(tempExtract, { recursive: true, force: true });
					resolve();
				} catch (e) {
					reject(e);
				}
			});
		} else {
			const extractCmd = `tar -xzf "${archivePath}" -C "${targetDir}"`;
			exec(extractCmd, (err) => {
				if (err) {
					reject(err);
					return;
				}
				try {
					const extractedBin = path.join(targetDir, `nox-${version}-${asset}`, "bin");
					if (!fs.existsSync(binDir)) {
						fs.mkdirSync(binDir, { recursive: true });
					}
					const files = fs.readdirSync(extractedBin);
					for (const file of files) {
						const srcFile = path.join(extractedBin, file);
						const destFile = path.join(binDir, file);
						fs.copyFileSync(srcFile, destFile);
						fs.chmodSync(destFile, 0o755);
					}
					fs.rmSync(path.join(targetDir, `nox-${version}-${asset}`), { recursive: true, force: true });
					resolve();
				} catch (e) {
					reject(e);
				}
			});
		}
	});
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

	const noxHome = path.join(os.homedir(), ".nox");
	const noxBinDir = path.join(noxHome, "bin");
	const platform = os.platform();
	const binaryName = platform === "win32" ? "nox-lsp.exe" : "nox-lsp";
	const binaryPath = path.join(noxBinDir, binaryName);
	const versionPath = path.join(noxHome, "version");

	// If binary already exists and we are not forcing download, return it
	if (fs.existsSync(binaryPath) && !forceDownload) {
		return binaryPath;
	}

	let assetSuffix = "";
	if (platform === "darwin") {
		assetSuffix = "macos-arm64";
	} else if (platform === "linux") {
		assetSuffix = "linux-x64";
	} else if (platform === "win32") {
		assetSuffix = "windows-x64";
	} else {
		throw new Error(`Unsupported OS platform: ${platform}`);
	}

	await vscode.window.withProgress({
		location: vscode.ProgressLocation.Notification,
		title: forceDownload ? "Updating Nox LSP..." : "Downloading Nox LSP...",
		cancellable: false
	}, async (progress) => {
		progress.report({ message: "Checking for latest release..." });

		let releases: GithubRelease[];
		try {
			releases = await getJson("https://api.github.com/repos/deepsarda/Nox/releases");
		} catch (err) {
			if (fs.existsSync(binaryPath)) {
				vscode.window.showWarningMessage(`Could not check for latest Nox release (offline). Using existing local version.`);
				return;
			}
			throw new Error(`Offline and no local Nox LSP installation found: ${(err as Error).message}`);
		}

		// Filter for core releases starting with v, excluding vscode and intellij releases
		const coreReleases = releases.filter(r =>
			r.tag_name.startsWith("v") &&
			!r.tag_name.startsWith("vscode-") &&
			!r.tag_name.startsWith("intellij-")
		);

		if (coreReleases.length === 0) {
			throw new Error("No core Nox releases found on GitHub.");
		}

		const latestRelease = coreReleases[0];
		const latestTag = latestRelease.tag_name;
		const version = latestTag.replace(/^v/, "");

		const targetAsset = latestRelease.assets.find(a => a.name.includes(assetSuffix));
		if (!targetAsset) {
			throw new Error(`No asset found for platform ${assetSuffix} in release ${latestTag}`);
		}

		// Check if we already have this exact version installed
		if (fs.existsSync(binaryPath) && fs.existsSync(versionPath) && !forceDownload) {
			const currentInstalledVersion = fs.readFileSync(versionPath, "utf8").trim();
			if (currentInstalledVersion === latestTag) {
				return;
			}
		}

		progress.report({ message: `Downloading Nox v${version}...` });
		
		if (!fs.existsSync(noxHome)) {
			fs.mkdirSync(noxHome, { recursive: true });
		}

		const archiveName = targetAsset.name;
		const archivePath = path.join(noxHome, archiveName);

		await downloadFile(targetAsset.browser_download_url, archivePath);

		progress.report({ message: "Extracting package..." });
		await extractArchive(archivePath, noxHome, platform, version, assetSuffix);

		// Clean up downloaded archive
		if (fs.existsSync(archivePath)) {
			fs.unlinkSync(archivePath);
		}

		fs.writeFileSync(versionPath, latestTag, "utf8");
		vscode.window.showInformationMessage(`Successfully installed Nox v${version}`);
	});

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
