"use strict";

const fs = require("node:fs");
const { spawnSync } = require("node:child_process");

const TEST_GLOB = "src/test/js/**/*.test.cjs";

function fail(message, exitCode = 1) {
    process.stderr.write(`run-tests: ${message}\n`);
    process.exit(exitCode);
}

const testPaths = fs.globSync(TEST_GLOB)
    .map(path => path.replace(/\\/g, "/"))
    .sort();

if (testPaths.length === 0) {
    fail(`no test files discovered for ${TEST_GLOB}`);
}

for (const testPath of testPaths) {
    const source = fs.readFileSync(testPath, "utf8");
    for (const forbiddenMarker of [".skip", ".todo", ".only"]) {
        if (source.includes(forbiddenMarker)) {
            fail(`${testPath} contains forbidden ${forbiddenMarker}`);
        }
    }
}

process.stdout.write(`DISCOVERED_TEST_PATHS\n${testPaths.join("\n")}\n`);

const child = spawnSync(
    process.execPath,
    ["--test", "--test-reporter=tap", ...testPaths],
    { encoding: "utf8" }
);

if (child.stdout) process.stdout.write(child.stdout);
if (child.stderr) process.stderr.write(child.stderr);
if (child.error) fail(`unable to run Node test process: ${child.error.message}`);
if (child.status !== 0) fail(`Node test process exited with status ${child.status}`, child.status || 1);

const skippedMatches = [...child.stdout.matchAll(/^#\s*skipped\s+(\d+)\s*$/gmi)];
if (skippedMatches.length === 0) {
    fail("Node TAP summary did not include a skipped count");
}

const skippedCount = Number(skippedMatches.at(-1)[1]);
if (skippedCount > 0) {
    fail(`Node TAP summary reported ${skippedCount} skipped test(s)`);
}
