# LionCPH — Competitive Programming Helper for CLion

[![License: MIT](https://img.shields.io/badge/License-MIT-blue.svg)](LICENSE)
[![CLion](https://img.shields.io/badge/CLion-2024.2%2B-000000?logo=clion&logoColor=white)](https://www.jetbrains.com/clion/)
[![JetBrains Marketplace](https://img.shields.io/badge/JetBrains%20Marketplace-LionCPH-007ACC)](https://plugins.jetbrains.com/plugin/36278-lioncph)

**LionCPH** brings the speed, modern dark UI, and workflow of VS Code's Competitive Programming Helper (CPH) natively to **JetBrains CLion** (2024.2 – 2026.3+, Classic & Nova) — supercharged with One-Click CLion Debugging, Sanitizers, Live Codeforces Verdicts, Smart Workspace Organization, and zero-config CMake auto-sync.

---

## Features

- **Dual Sticky Timer & Problem Notes:** Pinned top header with a per-problem **Solve Stopwatch** (auto-pauses on `AC`), a **Contest / Virtual / Sheet Countdown** (`2:00h`, `2:30h`, `3:00h`, `5:00h ICPC`, or custom minutes), and auto-saved **Problem Notes** for upsolving.
- **CP Snippets, Float Tolerance & Pre-Submit Guard:** Insert classic C++ templates (`DSU`, `Segment Tree`, `Sieve`, `Dijkstra`, `modPow`, + custom snippets) right at your cursor, toggle **Float ε (`1e-6` / `1e-9`)** comparison, batch-import **`.in`/`.out` testcase folders**, and catch unprotected `freopen`/`endl`/debug prints before submitting.
- **Modern Native Judge Panel:** Sleek dark interface with rounded cards, **Collapse All / Expand All**, auto-collapsing passed testcases, execution time (`ms`) & peak memory (`MB`/`KB`) pills, `Copy` / `Set` helpers, and smooth 2x mouse-wheel scrolling.
- **Fast Parallel Judging, Sanitizers & One-Click Debug TC:** Compiles once with smart compiler discovery (CLion bundled MinGW, MSYS2, `g++` / `clang++`), caches unchanged builds, executes testcases in parallel with an instant **Stop** button, toggles **ASan/UBSan** (or `-D_GLIBCXX_DEBUG` on MinGW), and launches CLion's native Debugger preloaded with any failing testcase via the purple **Debug TC** button.
- **Side-by-Side Colored Visual Diff & Diagnostics:** Detailed diff viewer for Wrong Answer, scrollable Compilation Error panel with Copy, `TLE` / `MLE` detection, `512 KB` output capping, and human-readable crash diagnostics (`SIGSEGV`, `Stack Overflow`, `SIGFPE`, `SIGABRT`).
- **Full Codeforces Hub inside CLion:**
  - **Live Verdicts** (~1.1s polling with smart 503 backoff and optional `CF API Key` / `Secret` for private/group sheets like ICPC Assiut).
  - **Prev / Standings / Next Navigator** with automatic end-of-sheet detection (`Last Problem`) and zero duplicate browser tabs.
  - **In-IDE `CF Subs`** (latest 15 submissions) and **`Contests`** (upcoming rounds with live countdown).
  - **Smart Codeforces Submit:** Matches your CLion/CMake C++ standard (`G++23` / `G++20` / `G++17`), works both with and without [cph-submit](https://chromewebstore.google.com/detail/cph-submit/ekplnobooikgpdbobcciehbhcmlklgnc).
- **Smart Workspace Organizer & C++ Template Editor:** Choose **Flat**, **Single Folder**, or **Smart Hierarchy** (`Groups/<Group>/<Sheet>`, `Contests/<Round>`, `EDU`, `Gym`, `Practice`, AtCoder/CSES), pick **Full** or **Short** file names (`A.cpp`, `4A.cpp`), organize existing root files in one click, and customize your default C++ template (`$name`, `$url`, `$date`, `$time`).
- **Zero-Config Isolated CMake Auto-Sync:** Automatically registers LionCPH-imported `.cpp` files (including nested subfolders) inside `.cph/lioncph_targets.cmake` via a single `OPTIONAL` include line and reloads CMake automatically without touching user files.
- **Competitive Companion & Custom Checker:** Listens on ports `27121` and `10045` (IPv4 & IPv6), supports Python Special Judge checkers, and syncs `ONLINE_JUDGE` / `LOCAL` macros with both the judge compiler and CMake.

---

## Quick Start

1. **Install LionCPH** from [JetBrains Marketplace](https://plugins.jetbrains.com/plugin/36278-lioncph) (`Settings > Plugins > Marketplace > LionCPH`) or via `Install Plugin from Disk...`.
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

The installable plugin archive will be generated at `build/distributions/LionCPH-1.9.4.zip`.

---

## Credits & Acknowledgements

- **Author & Lead Developer:** [Mostafa Musameh](https://github.com/mostafa16musameh)
- **QA & Testing Contributor:** [Tariq Khalid](https://github.com/Tariq4824) (`@Tariq4824`) — Special thanks for his dedicated testing, bug hunting, and valuable feedback that helped make LionCPH faster, cleaner, and more reliable for competitive programmers.

---

## License

This project is licensed under the **MIT License** — see the [LICENSE](LICENSE) file for details.
