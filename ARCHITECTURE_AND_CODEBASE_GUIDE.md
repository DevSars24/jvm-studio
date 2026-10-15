# JVM Studio — Architecture, Runtime Internals & Codebase Guide

## 1. Executive Summary & Philosophy

**JVM Studio** is a high-performance, modular Java 21 systems engineering workspace and educational laboratory designed to dissect and demystify the inner mechanics of the **Oracle HotSpot Java Virtual Machine (JVM)**.

Rather than treating the JVM as an opaque black box, JVM Studio provides hands-on, live architectural inspection across all major VM subsystems:
1. **Dynamic ClassLoader Hierarchy & Security Sandboxing**: Parent-first delegation from Bootstrap to isolated child `URLClassLoader` plugin instances.
2. **Generational Memory Management & GC Root Traversal**: Thread-Local Allocation Buffers (TLABs), Eden / Survivor tenuring thresholds, and pause-time diagnostics.
3. **Adaptive HotSpot Multi-Tier JIT Compilation**: Tier 0 Interpreter through Tier 4 C2 Server JIT optimizations (inlining, escape analysis, loop vectorization).
4. **Bounded Thread Concurrency & Carrier Scheduling**: Fixed ThreadPoolExecutor with rejection policies, zero memory-leak task queues, and virtual thread readiness.
5. **ACID Write-Ahead Log (WAL) Audit Persistence**: Embedded SQLite tracking execution lifecycles, memory spikes, and error traces.
6. **Decoupled Architecture & Real-Time Telemetry Web Dashboard**: Embedded non-blocking HTTP server delivering sub-millisecond JSON metrics, live HTML5 canvas spline graphs, and responsive mobile-ready interfaces.

---

## 2. Multi-Module Project Architecture

The codebase is organized into clean, decoupled Maven modules adhering strictly to separation of concerns:

```
c:\Users\DELL\Desktop\java
├── pom.xml                                      # Parent Multi-Module POM (Java 21, Maven 3.9+)
├── plugin-api/                                  # Pure Java SPI Contracts (Zero dependencies)
│   ├── pom.xml
│   └── src/main/java/com/jvmstudio/api/
│       ├── TaskDefinition.java                  # Immutable metadata contract for tasks
│       ├── TaskContext.java                     # Execution hooks, cancel tokens, progress callbacks
│       └── TaskPlugin.java                      # Service Provider Interface (SPI) for dynamic loading
├── sample-plugin/                               # Hot-Pluggable URLClassLoader Extension
│   ├── pom.xml
│   └── src/main/java/com/jvmstudio/plugin/
│       └── WordFrequencyTask.java               # Dynamic plugin loaded at runtime from JAR
├── desktop-app/                                 # Core Systems Engine, JavaFX UI & Embedded Web Server
│   ├── pom.xml
│   └── src/main/java/com/jvmstudio/
│       ├── core/
│       │   ├── AppContext.java                  # Central dependency injection & lifecycle registry
│       │   ├── db/DatabaseManager.java          # SQLite WAL schema migration & transactional repository
│       │   ├── engine/TaskEngine.java           # Bounded ExecutorService, future tracking & cancellations
│       │   ├── jvm/JvmMonitorService.java       # Scheduled JMX / ManagementFactory telemetry sampler
│       │   ├── plugins/PluginManager.java       # Child URLClassLoader isolator & SPI discovery
│       │   └── tasks/                           # Core built-in task implementations
│       │       ├── FileBackupTask.java          # Chunked I/O file backup engine
│       │       ├── FolderAnalyzerTask.java      # Disk volume & extension analyzer
│       │       ├── Sha256VerifierTask.java      # Streaming SHA-256 cryptographic verifier
│       │       └── MemoryAllocationDemoTask.java# Generational heap allocation & GC lab task
│       ├── ui/
│       │   └── MainApp.java                     # JavaFX desktop client with embedded web server bootstrapping
│       └── web/
│           └── WebDashboardServer.java          # Embedded JDK HttpServer serving API, assets & dashboard
├── assets/                                      # High-resolution architectural diagram assets
│   ├── jvm-architecture.jpg                    # Figure 1.1: Complete HotSpot JVM Architecture
│   ├── hotspot-jit-pipeline.jpg                # Figure 1.2: HotSpot Tiered Compilation Architecture
│   └── jvm-gc-flow.jpg                         # Figure 1.3: Generational GC & Memory Lifecycle Flow
└── index.html                                   # Pure White Studio Landing Page & Real-Time Lab
```

---

## 3. Subsystem Deep-Dives

### 3.1 ClassLoader Subsystem & Plugin Sandboxing
* **Loading Order**: Bootstrap ClassLoader &rarr; Platform ClassLoader &rarr; Application ClassLoader &rarr; Plugin `URLClassLoader`.
* **Delegation Model**: Core classes use parent-first delegation to guarantee security. Plugin classes are isolated inside individual `URLClassLoader` instances, allowing third-party JARs (such as `sample-plugin-1.0.0.jar`) to be registered, unloaded, and updated dynamically at runtime without restarting the JVM.
* **3-Phase Verification**:
  1. *Verification*: Enforces the `0xCAFEBABE` bytecode header, operand stack bounds, and type safety.
  2. *Preparation*: Allocates static fields in Metaspace and populates default null/zero values.
  3. *Resolution*: Translates symbolic constant pool references into direct pointers.

### 3.2 Generational Memory Management & Real-Time Allocation Lab
* **Young Generation**:
  - **Eden Space**: Fast, lock-free allocations using Thread-Local Allocation Buffers (TLABs).
  - **Survivor Spaces (S0 / S1)**: Copying collectors ping-pong surviving instances, incrementing the object tenuring age.
* **Old Generation**:
  - Objects that exceed the tenuring threshold (default: 15) are promoted to Tenured space.
  - Major GCs sweep and compact long-lived objects.
* **Metaspace**:
  - Native host RAM allocation for class metadata, Constant Pools, method bytecodes, and vtables.
* **Interactive Lab**: Users can allocate real 20MB heap byte arrays, release references to invalidate GC roots, and dispatch `System.gc()` to observe physical garbage collection in real-time.

### 3.3 Adaptive HotSpot Multi-Tier JIT Engine
HotSpot balances zero startup latency with peak C++ native speed using tiered compilation:
* **Tier 0 (Template Interpreter)**: Starts executing bytecode immediately. Injects profiling hooks to track invocation counters and loop backedge frequencies.
* **Tiers 1–3 (C1 Client Compiler)**: Fast native compilation with Method Data Object (MDO) branch profiling.
* **Tier 4 (C2 Server Compiler)**: Heavy optimizing compiler. Performs speculative devirtualization, aggressive method inlining, loop unrolling, SIMD vectorization, and On-Stack Replacement (OSR).

### 3.4 Bounded Concurrency & Task Scheduling
* **Engine**: `TaskEngine` utilizes an 8-worker thread pool backed by an `ArrayBlockingQueue` with bounded capacity of 200 tasks.
* **Rejection Policy**: `ThreadPoolExecutor.AbortPolicy` gracefully notifies callers when queue saturates, preventing out-of-memory cascading failures.
* **Thread Safety**: Atomic state transitions (`READY` &rarr; `RUNNING` &rarr; `SUCCESS` / `FAILED` / `CANCELLED`) with lock-free task execution tracking.

### 3.5 Embedded SQLite Persistence with Write-Ahead Logging (WAL)
* Database located at `~/.jvmstudio/jvmstudio.db`.
* Configured with `PRAGMA journal_mode=WAL` and `PRAGMA synchronous=NORMAL` for maximum concurrent read/write throughput without file locks.
* Records execution IDs, start/finish timestamps, duration in milliseconds, items processed, error stack traces, and audit summaries.

---

## 4. Key Files & Responsibilities

| File Path | Primary Responsibility |
|:---|:---|
| [`desktop-app/.../web/WebDashboardServer.java`](file:///c:/Users/DELL/Desktop/java/desktop-app/src/main/java/com/jvmstudio/web/WebDashboardServer.java) | Embedded JDK `HttpServer` serving `/api/metrics`, `/api/tasks`, `/api/history`, `/assets/*`, and the root landing page. |
| [`desktop-app/.../engine/TaskEngine.java`](file:///c:/Users/DELL/Desktop/java/desktop-app/src/main/java/com/jvmstudio/core/engine/TaskEngine.java) | Thread pool executor managing task execution lifecycles, progress updates, and thread cancellation tokens. |
| [`desktop-app/.../plugins/PluginManager.java`](file:///c:/Users/DELL/Desktop/java/desktop-app/src/main/java/com/jvmstudio/core/plugins/PluginManager.java) | Scans JARs, initializes child `URLClassLoader`s, and binds `TaskPlugin` SPI implementations. |
| [`desktop-app/.../jvm/JvmMonitorService.java`](file:///c:/Users/DELL/Desktop/java/desktop-app/src/main/java/com/jvmstudio/core/jvm/JvmMonitorService.java) | Samples `MemoryMXBean`, `ThreadMXBean`, and `GarbageCollectorMXBean` at 1Hz frequency. |
| [`desktop-app/.../db/DatabaseManager.java`](file:///c:/Users/DELL/Desktop/java/desktop-app/src/main/java/com/jvmstudio/core/db/DatabaseManager.java) | Initializes SQLite schema, executes audit writes, and loads historical task runs. |
| [`index.html`](file:///c:/Users/DELL/Desktop/java/index.html) | Decoupled White Studio landing page featuring the Zero-Dependency Canvas Spline telemetry graph and mobile responsive UI. |

---

## 5. White Studio Landing Page & Design System

The web frontend is engineered with a **pure white aesthetic**:
* **Backgrounds**: `--bg-page: #f8fafc;`, `--bg-surface: #ffffff;`, `--bg-elevated: #f1f5f9;`.
* **Borders & Shadows**: Crisp slate borders (`#e2e8f0`) with subtle multi-layer elevation (`rgba(15, 23, 42, 0.05)`).
* **Typography**: Modern Google Fonts pairing — **Plus Jakarta Sans** for clear, legible headings and UI elements, and **JetBrains Mono** for memory metrics and bytecode representations.
* **No Tacky UI Badges**: Clean, human-crafted layout free of AI-generated labels.

### Zero-Dependency Native Canvas Spline Renderer
To eliminate CDN failure points, adblocker suppression, or offline network issues:
* **Built-in HTML5 Canvas Engine**: Does not require external Chart.js scripts.
* **Retina High-DPI Auto-Scaling**: Scales with `window.devicePixelRatio` for crystal clarity on 4K monitors and mobile screens.
* **Smooth Catmull-Rom / Bezier Spline**: Quadratic curve interpolation across data samples.
* **Pulsating Live Head Indicator**: Glowing animated bead with dynamic tooltip tracking live memory in real-time.
* **Dynamic Range Gridlines**: Auto-adjusts horizontal MB steps based on current memory usage.

### Complete Mobile Responsiveness
* **Fluid Layouts**: Media queries tailored for `<= 900px`, `<= 768px`, and `<= 480px`.
* **Touch-Friendly Hamburger Navigation**: Collapsible drawer menu enabling 1-tap jump to any architectural section.
* **Stacked KPI Grid**: Quad KPI cards gracefully adapt from 4 columns to 2 columns on tablets and 1 column on phones.
* **Horizontal Touch-Scroll Tables**: Tables wrapped in `-webkit-overflow-scrolling: touch;` containers to prevent viewport disruption.
* **Adaptive Showcase Layouts**: Image and explanation columns stack vertically on mobile with responsive image auto-scaling.

---

## 6. Verification & Runbook Commands

### Build Entire Multi-Module Project
```powershell
.\mvnw.cmd clean package -DskipTests=false
```

### Launch Desktop Application with Embedded Web Server
```powershell
.\mvnw.cmd javafx:run -pl desktop-app
```

### Access Web Dashboard
Open any browser to:
```
http://localhost:8080/
```

### Validate Asset Endpoints
```powershell
Invoke-WebRequest -Uri "http://localhost:8080/assets/jvm-architecture.jpg" -UseBasicParsing
Invoke-WebRequest -Uri "http://localhost:8080/assets/hotspot-jit-pipeline.jpg" -UseBasicParsing
Invoke-WebRequest -Uri "http://localhost:8080/assets/jvm-gc-flow.jpg" -UseBasicParsing
```
