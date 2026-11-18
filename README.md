# LionCPH — Competitive Programming Helper for CLion

[![License: MIT](https://img.shields.io/badge/License-MIT-blue.svg)](LICENSE)
[![CLion](https://img.shields.io/badge/CLion-2026.3%2B-000000?logo=clion&logoColor=white)](https://www.jetbrains.com/clion/)

**LionCPH** brings the speed, clean dark UI, and workflow of VS Code's Competitive Programming Helper (CPH) natively to **JetBrains CLion** — enhanced with zero-config CMake auto-sync, parallel judging, and smart Codeforces submission.

---

## Features

- **Native CPH Judge Panel:** Pixel-faithful dark interface with auto-collapsing passed testcases, green-bordered pass badges, execution time pills (`ms`), `Copy` / `Set` helpers, and instant testcase deletion.
- **Fast Parallel Judging & Smart Compile Cache:** Compiles once with automatic compiler discovery (CLion bundled MinGW, MSYS2, system `g++` / `clang++`), caches unchanged builds, and executes testcases in parallel with an instant **Stop** button.
- **Rich Error & Signal Diagnostics:** Clear compilation error panels, first-line `Diff` hints on Wrong Answer, `TLE` termination, output size capping (`512 KB`) to prevent UI freezes, and human-readable crash diagnostics (`SIGSEGV`, `Stack Overflow`, `SIGFPE`, `SIGABRT`).
- **Competitive Companion Integration:** Listens automatically on ports `27121` and `10045` (IPv4 & IPv6) to import problems and sample testcases directly from Codeforces, AtCoder, CSES, and 100+ online judges.
- **Zero-Config Isolated CMake Auto-Sync:** Automatically registers LionCPH-imported `.cpp` files inside `.cph/lioncph_targets.cmake` (guarded with `if(EXISTS ... AND NOT TARGET ...)`) via a single `OPTIONAL` include line and reloads CMake automatically — without touching any user-created files or breaking existing `file(GLOB ...)` setups.
- **Smart Codeforces Submit:** Automatically detects your CLion/CMake C++ standard (`G++23` / `G++20` / `G++17`), parses `contest`, `problemset`, `gym`, `group`, and `EDU` URLs, shows a pre-submit human verification dialog, and works both **with** the [cph-submit](https://chromewebstore.google.com/detail/cph-submit/ekplnobooikgpdbobcciehbhcmlklgnc) browser extension (auto-paste & auto-submit) and **without** it (copies code & opens the exact direct Submit page).
- **Custom Checker & `ONLINE_JUDGE` Sync:** Built-in Python Special Judge support with file browser and sample generator, plus one-click `ONLINE_JUDGE` / `LOCAL` macro syncing with both the judge compiler and CMake.

---

## Quick Start

1. **Install LionCPH** from **JetBrains Marketplace** (`Settings > Plugins > Marketplace > LionCPH`) or via `Install Plugin from Disk...`.
2. **Install [Competitive Companion](https://chromewebstore.google.com/detail/competitive-companion/cjnmckjndlpiamhfimnnjmnckgghkjbl)** in Chrome/Firefox to import problems with one click.
3. *(Optional)* **Install [CPH Submit](https://chromewebstore.google.com/detail/cph-submit/ekplnobooikgpdbobcciehbhcmlklgnc)** in Chrome/Firefox for one-click auto-paste & auto-submit on Codeforces.
4. Open any `.cpp` file or import a problem, then press **`Ctrl + Alt + B`** (or click **Run All**) to compile and judge!

---

## Building from Source

```bash
git clone https://github.com/mostafa16musameh/LionCPH.git
cd LionCPH
./gradlew clean buildPlugin
```

The installable plugin archive will be generated at `build/distributions/LionCPH-<version>.zip`.

---

## License

This project is licensed under the **MIT License** — see the [LICENSE](LICENSE) file for details.
