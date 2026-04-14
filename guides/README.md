# Nox Scripting Language (NSL) Guides

Welcome to the official documentation for the **Nox Scripting Language (NSL)**. NSL is a safe, statically-typed, and capability-sandboxed language designed to execute within the Nox runtime. 

Whether you have never written a line of code before, or you are a seasoned engineer looking to understand our capability model, this guide has you covered.

## Table of Contents

- [First Steps (For Beginners)](first-steps.md)
  New to programming? Start here! Learn what variables, loops, and functions are, and write your first NSL script.

- [Getting Started (Compiling & Running)](getting-started.md)
  Learn how to install the `nox` CLI, run your scripts, understand permission prompts, and compile code.

- [NSL for Experienced Developers](experienced-devs.md)
  Already know Java, JS, C, or Python? Skip the basics. Learn about UFCS, `yield` streaming, JSON casting, and the zero-trust sandbox.

- [Type System & JSON](type-system.md)
  A deep dive into primitive vs. reference types, building structs, managing arrays, and using the dynamic `json` bridge safely.

- [Functions, Flow & Streaming](functions-and-flow.md)
  Master control structures, error handling, default arguments, and the difference between `yield` and `return`.

- [Standard Library](standard-library.md)
  Reference for built-in namespaces like `File`, `Http`, `Env`, `Math`, `Date`, and `Json`.

- [Security Model & Internals](security-and-internals.md)
  Go behind the curtain. Understand `pMem` and `rMem`, the Permission Bridge, and how the VM guards against malicious resource consumption.

- [Language Reference](reference.md)
  A quick-lookup syntax cheatsheet, reserved words list, and operator precedence table.
