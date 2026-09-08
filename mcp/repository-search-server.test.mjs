import assert from "node:assert/strict";
import fs from "node:fs/promises";
import os from "node:os";
import path from "node:path";
import test from "node:test";

import { searchRepository, readSource, listDirectory, repoMap } from "./repository-search-server.mjs";

test("directory listing returns exactly one bounded level", async () => {
    const result = await listDirectory(PROJECT_ROOT, { path: "src/main", max_entries: 20 });
    assert.match(result, /^directory: src\/main/);
    assert.match(result, /directory: java\//);
    assert.match(result, /directory: resources\//);
    assert.doesNotMatch(result, /com\/guodi/);
    await assert.rejects(listDirectory(PROJECT_ROOT, { path: "../" }));
});

const PROJECT_ROOT = path.resolve(import.meta.dirname, "..");

test("repo map returns a stable bounded project overview", async () => {
    const result = await repoMap(PROJECT_ROOT, { max_depth: 5, max_entries: 300 });

    assert.match(result, /^repository_root: /);
    assert.match(result, /- src\//);
    assert.match(result, /- mcp\//);
    assert.match(result, /- pom\.xml/);
    assert.match(result, /- RepositoryAnalysisAgentApplication\.java/);
    assert.doesNotMatch(result, /node_modules\//);
    assert.ok(result.split("\n").length <= 303);
});

test("finds AgentRuntime with file path, line number, and context", async () => {
    const result = await searchRepository(PROJECT_ROOT, {
        query: "class AgentRuntime",
        path: "src/main/java"
    });

    assert.match(result, /AgentRuntime\.java:\d+/);
    assert.match(result, /class AgentRuntime/);
});

test("finds multiple RepositoryOverviewService references", async () => {
    const result = await searchRepository(PROJECT_ROOT, {
        query: "RepositoryOverviewService",
        path: "src/main/java"
    });

    assert.match(result, /AgentServiceImpl\.java:\d+/);
    assert.match(result, /RepositoryController\.java:\d+/);
});

test("excludes assistant and build directories and honors a source path", async () => {
    const defaultResult = await searchRepository(PROJECT_ROOT, {
        query: "AgentController"
    });
    assert.doesNotMatch(defaultResult, /\.qwen\//);
    assert.doesNotMatch(defaultResult, /target\//);

    const scopedResult = await searchRepository(PROJECT_ROOT, {
        query: "AgentController",
        path: "src/main/java"
    });
    assert.match(scopedResult, /src\/main\/java\//);
    assert.doesNotMatch(scopedResult, /\.md:\d+/);
});

test("returns a stable no-match response", async () => {
    const result = await searchRepository(PROJECT_ROOT, {
        query: ["__aikb", "missing", "needle__"].join("_")
    });

    assert.equal(result, "No matches found.");
});

test("enforces maxResults and the output character limit", async () => {
    const limited = await searchRepository(PROJECT_ROOT, { query: "import", maxResults: 1 });
    assert.match(limited, /^1\./);
    assert.match(limited, /Results truncated\./);

    const broad = await searchRepository(PROJECT_ROOT, { query: "a", maxResults: 50 });
    assert.ok(broad.length <= 12_000);
});

test("rejects parent traversal and symlink escape", async () => {
    await assert.rejects(
        searchRepository(PROJECT_ROOT, { query: "secret", path: "../" }),
        /escapes the allowed repository root/
    );

    const temporaryRoot = await fs.mkdtemp(path.join(os.tmpdir(), "aikb-search-code-"));
    const outsideRoot = await fs.mkdtemp(path.join(os.tmpdir(), "aikb-search-code-outside-"));
    try {
        await fs.symlink(outsideRoot, path.join(temporaryRoot, "outside"));
        await assert.rejects(
            searchRepository(temporaryRoot, { query: "secret", path: "outside" }),
            /escapes the allowed repository root/
        );
    } finally {
        await fs.rm(temporaryRoot, { recursive: true, force: true });
        await fs.rm(outsideRoot, { recursive: true, force: true });
    }
});

test("reads a confirmed source file with bounded numbered lines", async () => {
    const result = await readSource(PROJECT_ROOT, {
        path: "src/main/java/com/guodi/aikb/RepositoryAnalysisAgentApplication.java",
        start_line: 1,
        max_lines: 8
    });

    assert.match(result, /^source: src\/main\/java\/com\/guodi\/aikb\/RepositoryAnalysisAgentApplication\.java/);
    assert.match(result, /1 \| package com\.guodi\.aikb;/);
    assert.match(result, /Results truncated/);
    await assert.rejects(readSource(PROJECT_ROOT, { path: "../pom.xml" }));
});

test("does not count a trailing file newline as an extra source line", async () => {
    const result = await readSource(PROJECT_ROOT, {
        path: "src/main/java/com/guodi/aikb/ai/agent/runtime/SourceRef.java"
    });

    assert.match(result, /\n30 \| }$/);
    assert.doesNotMatch(result, /\n31 \|/);
});
