import fs from "node:fs/promises";
import path from "node:path";
import readline from "node:readline";
import { pathToFileURL } from "node:url";

const DEFAULT_MAX_RESULTS = 20;
const MAX_RESULTS = 50;
const MAX_OUTPUT_CHARS = 12_000;
const CONTEXT_LINES = 2;
const TRUNCATED_MESSAGE =
    "Results truncated. Narrow the query or path for more precise results.";

const IGNORED_DIRECTORIES = new Set([
    ".git",
    "target",
    ".idea",
    ".vscode",
    ".claude",
    ".qwen",
    "node_modules",
    ".npm-cache",
    "dist",
    "coverage"
]);

const SEARCHABLE_EXTENSIONS = new Set([
    ".java",
    ".js",
    ".mjs",
    ".ts",
    ".tsx",
    ".py",
    ".sh",
    ".yml",
    ".yaml",
    ".properties",
    ".xml",
    ".sql",
    ".md",
    ".json"
]);

const TOOL_DESCRIPTION = `Search literal text in searchable text files under the allowed repository path.

Use this tool to locate:
- definitions and references
- configuration and metadata
- documentation and structured text
- any known text whose file location is unknown

This tool searches file contents, not file names.

Prefer search_repository to locate relevant code before reading multiple files.
When a relevant subdirectory is known, set path to narrow the search and reduce unrelated matches.
After locating important matches, use read_source to inspect the actual implementation.`;

const SEARCH_REPOSITORY_TOOL = {
    name: "search_repository",
    description: TOOL_DESCRIPTION,
    inputSchema: {
        type: "object",
        properties: {
            query: {
                type: "string",
                description: "Literal text to search for in supported text-file contents."
            },
            path: {
                type: "string",
                description: "Optional path relative to the allowed repository root."
            },
            maxResults: {
                type: "integer",
                minimum: 1,
                maximum: MAX_RESULTS,
                default: DEFAULT_MAX_RESULTS,
                description: "Maximum number of matches to return. Defaults to 20 and cannot exceed 50."
            }
        },
        required: ["query"],
        additionalProperties: false
    }
};

const READ_SOURCE_TOOL = {
    name: "read_source",
    description: "Read a confirmed text file under the repository root with actual line numbers. Read only the range needed, avoid overlapping repeated reads, and use search_repository first when the path is unknown.",
    inputSchema: {
        type: "object",
        properties: {
            path: { type: "string", description: "File path relative to the repository root." },
            start_line: { type: "integer", minimum: 1, default: 1 },
            max_lines: { type: "integer", minimum: 1, maximum: 1000, default: 400 }
        },
        required: ["path"],
        additionalProperties: false
    }
};

const LIST_DIRECTORY_TOOL = {
    name: "list_directory",
    description: "List the immediate children of a directory under the repository root. Use it to inspect layout or confirm paths; it does not recurse or search file contents.",
    inputSchema: {
        type: "object",
        properties: {
            path: { type: "string", description: "Directory path relative to the repository root; defaults to ." },
            max_entries: { type: "integer", minimum: 1, maximum: 300, default: 200 }
        },
        additionalProperties: false
    }
};

const REPO_MAP_TOOL = {
    name: "repo_map",
    description: "Generate a bounded repository overview for application-managed project context initialization.",
    inputSchema: {
        type: "object",
        properties: {
            max_depth: {
                type: "integer",
                minimum: 1,
                maximum: 8,
                default: 5
            },
            max_entries: {
                type: "integer",
                minimum: 1,
                maximum: 1000,
                default: 300
            }
        },
        additionalProperties: false
    }
};

export async function repoMap(repositoryRoot, input = {}) {
    const maxDepth = input.max_depth ?? 5;
    const maxEntries = input.max_entries ?? 300;
    if (!Number.isInteger(maxDepth) || maxDepth < 1 || maxDepth > 8) {
        throw new Error("max_depth must be an integer between 1 and 8.");
    }
    if (!Number.isInteger(maxEntries) || maxEntries < 1 || maxEntries > 1000) {
        throw new Error("max_entries must be an integer between 1 and 1000.");
    }

    const root = await fs.realpath(repositoryRoot);
    const lines = [`repository_root: ${root}`, "overview_content:"];
    let entryCount = 0;
    let truncated = false;

    async function visit(directory, depth) {
        const entries = await fs.readdir(directory, { withFileTypes: true });
        entries.sort((left, right) => {
            if (left.isDirectory() !== right.isDirectory()) {
                return left.isDirectory() ? -1 : 1;
            }
            return left.name.localeCompare(right.name);
        });

        for (const entry of entries) {
            if (entry.isSymbolicLink()
                    || (entry.isDirectory() && IGNORED_DIRECTORIES.has(entry.name))) {
                continue;
            }
            if (entryCount >= maxEntries) {
                truncated = true;
                return;
            }

            const marker = entry.isDirectory() ? "/" : "";
            lines.push(`${"  ".repeat(depth + 1)}- ${entry.name}${marker}`);
            entryCount++;

            // At the boundary, still list that directory's immediate children so
            // deeply nested language package roots expose their entry files.
            if (entry.isDirectory() && depth <= maxDepth) {
                await visit(path.join(directory, entry.name), depth + 1);
            }
            if (truncated) return;
        }
    }

    await visit(root, 0);
    if (truncated) {
        lines.push(`  ... truncated after ${entryCount} entries`);
    }
    return lines.join("\n");
}

export async function listDirectory(repositoryRoot, input = {}) {
    const maxEntries = input.max_entries ?? 200;
    if (!Number.isInteger(maxEntries) || maxEntries < 1 || maxEntries > 300) {
        throw new Error("max_entries must be an integer between 1 and 300.");
    }
    const { root, candidate, stat } = await resolveSearchRoot(repositoryRoot, input.path);
    if (!stat.isDirectory()) {
        throw new Error("path must be a directory.");
    }
    const entries = await fs.readdir(candidate, { withFileTypes: true });
    const visible = entries
        .filter(entry => !entry.isSymbolicLink()
                && !(entry.isDirectory() && IGNORED_DIRECTORIES.has(entry.name)))
        .sort((left, right) => {
            if (left.isDirectory() !== right.isDirectory()) {
                return left.isDirectory() ? -1 : 1;
            }
            return left.name.localeCompare(right.name);
        });
    const directory = path.relative(root, candidate).split(path.sep).join("/") || ".";
    const lines = [`directory: ${directory}`];
    for (const entry of visible.slice(0, maxEntries)) {
        lines.push(`${entry.isDirectory() ? "directory" : "file"}: ${entry.name}${entry.isDirectory() ? "/" : ""}`);
    }
    if (visible.length > maxEntries) lines.push("Results truncated.");
    return lines.join("\n");
}

export async function readSource(repositoryRoot, input = {}) {
    const startLine = input.start_line ?? 1;
    const maxLines = input.max_lines ?? 400;
    if (!Number.isInteger(startLine) || startLine < 1) {
        throw new Error("start_line must be a positive integer.");
    }
    if (!Number.isInteger(maxLines) || maxLines < 1 || maxLines > 1000) {
        throw new Error("max_lines must be an integer between 1 and 1000.");
    }
    const { root, candidate, stat } = await resolveSearchRoot(repositoryRoot, input.path);
    if (!stat.isFile()) throw new Error("path must be a file.");
    if (!SEARCHABLE_EXTENSIONS.has(path.extname(candidate).toLowerCase())) {
        throw new Error("path must point to a supported text source file.");
    }
    const content = await fs.readFile(candidate, "utf8");
    const allLines = content.split(/\r?\n/);
    if (allLines.length > 1 && allLines.at(-1) === "") {
        allLines.pop();
    }
    const selected = allLines.slice(startLine - 1, startLine - 1 + maxLines);
    const relativeFile = path.relative(root, candidate).split(path.sep).join("/");
    const numbered = selected.map((line, index) => `${startLine + index} | ${line}`);
    const truncated = startLine - 1 + selected.length < allLines.length;
    return `source: ${relativeFile}\n${numbered.join("\n")}`
        + (truncated ? "\nResults truncated. Continue with a later start_line." : "");
}

function normalizeMaxResults(value) {
    if (value === undefined || value === null) {
        return DEFAULT_MAX_RESULTS;
    }
    if (!Number.isInteger(value) || value < 1) {
        throw new Error("maxResults must be a positive integer.");
    }
    return Math.min(value, MAX_RESULTS);
}

async function resolveSearchRoot(repositoryRoot, relativePath) {
    const root = await fs.realpath(repositoryRoot);
    const requestedPath = relativePath === undefined || relativePath === null
        || relativePath.trim() === ""
        ? "."
        : relativePath.trim();

    if (path.isAbsolute(requestedPath)) {
        throw new Error("path must be relative to the allowed repository root.");
    }

    const candidate = await fs.realpath(path.resolve(root, requestedPath));
    if (candidate !== root && !candidate.startsWith(root + path.sep)) {
        throw new Error("path escapes the allowed repository root.");
    }

    const stat = await fs.stat(candidate);
    if (!stat.isDirectory() && !stat.isFile()) {
        throw new Error("path must point to a file or directory.");
    }

    return { root, candidate, stat };
}

async function collectSearchableFiles(target, stat) {
    if (stat.isFile()) {
        return SEARCHABLE_EXTENSIONS.has(path.extname(target).toLowerCase())
            ? [target]
            : [];
    }

    const files = [];
    const pending = [target];

    while (pending.length > 0) {
        const directory = pending.pop();
        const entries = await fs.readdir(directory, { withFileTypes: true });
        entries.sort((left, right) => left.name.localeCompare(right.name));

        for (let index = entries.length - 1; index >= 0; index--) {
            const entry = entries[index];
            const entryPath = path.join(directory, entry.name);

            if (entry.isSymbolicLink()) {
                continue;
            }
            if (entry.isDirectory()) {
                if (!IGNORED_DIRECTORIES.has(entry.name)) {
                    pending.push(entryPath);
                }
                continue;
            }
            if (entry.isFile()
                    && SEARCHABLE_EXTENSIONS.has(path.extname(entry.name).toLowerCase())) {
                files.push(entryPath);
            }
        }
    }

    files.sort();
    return files;
}

function formatMatch(relativeFile, lines, lineIndex, resultNumber) {
    const firstLine = Math.max(0, lineIndex - CONTEXT_LINES);
    const lastLine = Math.min(lines.length - 1, lineIndex + CONTEXT_LINES);
    const context = [];

    for (let index = firstLine; index <= lastLine; index++) {
        context.push(`${index + 1} | ${lines[index]}`);
    }

    return `${resultNumber}. ${relativeFile}:${lineIndex + 1}\n\n${context.join("\n")}`;
}

export async function searchRepository(repositoryRoot, input = {}) {
    const query = typeof input.query === "string" ? input.query : "";
    if (query.length === 0) {
        throw new Error("query must not be empty.");
    }

    const maxResults = normalizeMaxResults(input.maxResults);
    const { root, candidate, stat } = await resolveSearchRoot(repositoryRoot, input.path);
    const files = await collectSearchableFiles(candidate, stat);
    const blocks = [];
    let outputLength = 0;
    let truncated = false;

    search:
    for (const file of files) {
        const content = await fs.readFile(file, "utf8");
        const lines = content.split(/\r?\n/);
        const relativeFile = path.relative(root, file).split(path.sep).join("/");

        for (let lineIndex = 0; lineIndex < lines.length; lineIndex++) {
            if (!lines[lineIndex].includes(query)) {
                continue;
            }
            if (blocks.length >= maxResults) {
                truncated = true;
                break search;
            }

            const block = formatMatch(relativeFile, lines, lineIndex, blocks.length + 1);
            const separatorLength = blocks.length === 0 ? 0 : 2;
            const reservedLength = TRUNCATED_MESSAGE.length + 2;

            if (outputLength + separatorLength + block.length + reservedLength
                    > MAX_OUTPUT_CHARS) {
                truncated = true;
                break search;
            }

            blocks.push(block);
            outputLength += separatorLength + block.length;
        }
    }

    if (blocks.length === 0 && !truncated) {
        return "No matches found.";
    }

    let result = blocks.join("\n\n");
    if (truncated) {
        result += `${result.length === 0 ? "" : "\n\n"}${TRUNCATED_MESSAGE}`;
    }
    return result;
}

function writeMessage(message) {
    process.stdout.write(`${JSON.stringify(message)}\n`);
}

function writeResult(id, result) {
    writeMessage({ jsonrpc: "2.0", id, result });
}

function writeError(id, code, message) {
    writeMessage({ jsonrpc: "2.0", id, error: { code, message } });
}

async function handleMessage(repositoryRoot, message) {
    if (message.method === "initialize") {
        writeResult(message.id, {
            protocolVersion: "2024-11-05",
            capabilities: { tools: { listChanged: false } },
            serverInfo: { name: "aikb-repository-search", version: "1.0.0" }
        });
        return;
    }

    if (message.method === "ping") {
        writeResult(message.id, {});
        return;
    }

    if (message.method === "tools/list") {
        writeResult(message.id, { tools: [SEARCH_REPOSITORY_TOOL, READ_SOURCE_TOOL, LIST_DIRECTORY_TOOL, REPO_MAP_TOOL] });
        return;
    }

    if (message.method === "tools/call") {
        if (![SEARCH_REPOSITORY_TOOL.name, READ_SOURCE_TOOL.name, LIST_DIRECTORY_TOOL.name, REPO_MAP_TOOL.name].includes(message.params?.name)) {
            writeResult(message.id, {
                content: [{ type: "text", text: "Unknown tool." }],
                isError: true
            });
            return;
        }

        try {
            const operation = message.params.name === READ_SOURCE_TOOL.name
                ? readSource
                : message.params.name === LIST_DIRECTORY_TOOL.name
                    ? listDirectory
                : message.params.name === REPO_MAP_TOOL.name
                    ? repoMap
                    : searchRepository;
            const text = await operation(repositoryRoot, message.params.arguments ?? {});
            writeResult(message.id, { content: [{ type: "text", text }] });
        } catch (error) {
            writeResult(message.id, {
                content: [{ type: "text", text: error.message }],
                isError: true
            });
        }
        return;
    }

    if (message.id !== undefined) {
        writeError(message.id, -32601, `Method not found: ${message.method}`);
    }
}

async function runServer() {
    const repositoryRoot = process.argv[2] ?? process.cwd();
    await fs.realpath(repositoryRoot);

    const input = readline.createInterface({ input: process.stdin, crlfDelay: Infinity });
    for await (const line of input) {
        if (line.trim() === "") {
            continue;
        }
        try {
            await handleMessage(repositoryRoot, JSON.parse(line));
        } catch (error) {
            process.stderr.write(`Invalid MCP message: ${error.message}\n`);
        }
    }
}

const launchedDirectly = process.argv[1]
    && import.meta.url === pathToFileURL(path.resolve(process.argv[1])).href;

if (launchedDirectly) {
    runServer().catch((error) => {
        process.stderr.write(`Repository MCP server failed: ${error.message}\n`);
        process.exitCode = 1;
    });
}
