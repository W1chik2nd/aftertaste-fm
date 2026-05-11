#!/usr/bin/env node
import { spawn } from "node:child_process";

const tasks = [
  {
    name: "adapter",
    command: "npm",
    args: ["--prefix", "apps/netease-adapter", "run", "dev"],
    color: "\x1b[36m"
  },
  {
    name: "server",
    command: "npm",
    args: ["run", "dev:server"],
    color: "\x1b[33m"
  },
  {
    name: "web",
    command: "npm",
    args: ["--prefix", "apps/web", "run", "dev"],
    color: "\x1b[35m"
  }
];

const reset = "\x1b[0m";
const children = new Set();
let shuttingDown = false;

for (const task of tasks) {
  start(task);
}

process.on("SIGINT", shutdown);
process.on("SIGTERM", shutdown);

function start(task) {
  const child = spawn(task.command, task.args, {
    cwd: process.cwd(),
    env: process.env,
    stdio: ["inherit", "pipe", "pipe"]
  });
  children.add(child);

  child.stdout.on("data", (chunk) => write(task, chunk));
  child.stderr.on("data", (chunk) => write(task, chunk));

  child.on("exit", (code, signal) => {
    children.delete(child);
    if (!shuttingDown && code !== 0) {
      console.error(`${task.color}[${task.name}] exited with ${signal ?? code}${reset}`);
      shutdown();
    }
  });
}

function write(task, chunk) {
  const text = chunk.toString();
  for (const line of text.split(/\r?\n/)) {
    if (!line) continue;
    process.stdout.write(`${task.color}[${task.name}]${reset} ${line}\n`);
  }
}

function shutdown() {
  if (shuttingDown) return;
  shuttingDown = true;
  for (const child of children) {
    child.kill("SIGTERM");
  }
  setTimeout(() => {
    for (const child of children) {
      child.kill("SIGKILL");
    }
    process.exit(0);
  }, 1500).unref();
}
