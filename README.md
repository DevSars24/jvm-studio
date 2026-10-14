# JVM Studio — Live Task & Plugin Dashboard

[![Java](https://img.shields.io/badge/Java-21%20LTS-orange.svg)](https://www.oracle.com/java/)
[![JavaFX](https://img.shields.io/badge/JavaFX-21-blue.svg)](https://openjfx.io/)
[![Build](https://img.shields.io/badge/Build-Maven%20Wrapper-green.svg)](https://maven.apache.org/)
[![Database](https://img.shields.io/badge/Database-SQLite%203-lightgrey.svg)](https://sqlite.org/)

**JVM Studio** is a high-performance Java 21 desktop application designed to demonstrate and explore core Java runtime features through real, interactive behavior:
- **Dynamic Plugin Loading**: Hot-load and isolate external JAR tasks at runtime using `URLClassLoader` and `ServiceLoader`.
- **Reflection & Runtime Annotations**: Inspect classes, methods, and `@TaskInfo` metadata dynamically.
- **Concurrent Task Orchestration**: Multi-threaded execution engine supporting cooperative pause, resume, cancellation, and fixed-delay scheduling.
- **Real-Time JVM Monitoring**: Live telemetry from standard Java JMX Management Beans (`MemoryMXBean`, `ThreadMXBean`, `GarbageCollectorMXBean`).
- **Java vs C++ Educational Learning Lab**: Deep interactive comparisons analyzing dynamic loading, reflection, memory management, and portability.

---

## Architecture Overview

```
jvm-studio/
├── pom.xml                     # Multi-module root build descriptor
├── mvnw / mvnw.cmd             # Maven Wrapper scripts
├── plugin-api/                 # Shared plugin contract (zero external dependencies)
│   └── src/main/java/com/jvmstudio/plugin/api/
│       ├── TaskPlugin.java             # Core plugin execution contract
│       ├── TaskContext.java            # Cooperative pause/cancellation/progress context
│       ├── TaskInfo.java               # Runtime metadata annotation
│       ├── TaskParameterDescriptor.java# Dynamic UI parameter generation
│       └── PluginConstants.java        # API version bounds (v1.0)
├── sample-plugin/              # Working sample plugin JAR (Word Frequency Counter)
│   └── src/main/java/com/jvmstudio/plugin/sample/
│       └── WordFrequencyCounterPlugin.java
└── desktop-app/                # Main JavaFX desktop application
    ├── src/main/java/com/jvmstudio/
    │   ├── core/
    │   │   ├── db/             # SQLite persistence (History, Configs, Plugins, Settings)
    │   │   ├── engine/         # Bounded ThreadPoolExecutor, Scheduler, Overlap Policy
    │   │   ├── jvm/            # JMX MXBean telemetry sampler
    │   │   ├── model/          # Immutable records and state models
    │   │   ├── plugins/        # Dynamic URLClassLoader manager & reflection inspector
    │   │   └── tasks/          # Real built-in tasks (Backup, Analyzer, SHA-256, Memory Demo)
    │   └── ui/                 # Programmatic JavaFX dark dashboard
    └── src/main/resources/
        └── style.css           # High-end dark theme styling
```

---

## Prerequisites

- **Java Development Kit (JDK)**: Java 21 (LTS) or higher.
  Verify with:
  ```bash
  java -version
  ```
- **Operating System**: Windows 10/11, macOS, or Linux.
- **Maven**: Pre-configured via the included Maven Wrapper (`./mvnw` or `.\mvnw.cmd`). No standalone Maven installation is required.

---

## Quick Start & Execution Instructions

### 1. Build and Run Automated Tests
```powershell
# Windows PowerShell
.\mvnw.cmd clean test

# Linux / macOS
./mvnw clean test
```

### 2. Package All Modules (including Sample Plugin JAR)
```powershell
# Windows PowerShell
.\mvnw.cmd clean package

# Linux / macOS
./mvnw clean package
```
This builds:
- `desktop-app/target/desktop-app-1.0.0.jar`
- `sample-plugin/target/sample-plugin-1.0.0.jar` (Ready to load dynamically)

### 3. Launch the Application

#### Option A: Run via JavaFX Maven Plugin (Recommended for Development)
```powershell
# Windows PowerShell
.\mvnw.cmd javafx:run -pl desktop-app

# Linux / macOS
./mvnw javafx:run -pl desktop-app
```

#### Option B: Run Standalone JAR
```powershell
# Windows PowerShell
java --module-path "$env:USERPROFILE\.m2\repository\org\openjfx" --add-modules javafx.controls,javafx.graphics -jar desktop-app\target\desktop-app-1.0.0.jar
```

---

## Built-In Tasks & Behaviors

1. **File & Folder Backup Engine**:
   - Recursively traverses directories with chunked 64 KB buffer progress.
   - Detects and prevents recursive loops when the destination is nested inside the source folder.
   - Configurable collision policies: `OVERWRITE`, `SKIP`, and `RENAME_TIMESTAMP` (e.g. `file_backup_20261014_091500.txt`).
2. **Disk & Folder Storage Analyzer**:
   - Safe traversal handling symlinks and access-denied directories.
   - Computes total byte volume, directory counts, top largest files, and file extension distribution.
3. **SHA-256 Cryptographic Verifier**:
   - Computes cryptographic SHA-256 hashes using standard Java `MessageDigest`.
   - Streaming hash calculation with cooperative cancellation support.
   - Table view with interactive clipboard copy.
4. **JVM Heap Allocation & GC Demo**:
   - Safely allocates bounded memory chunks (e.g. 25 MB blocks).
   - Demonstrates GC roots, object reachability, and reference clearing.
   - Guarded against `-Xmx` heap exhaustion.
   - Explains that `System.gc()` is an observational request, not a synchronous guarantee.

---

## Dynamic Plugin System & Authoring Guide

### How to Load the Sample Plugin JAR
1. Launch JVM Studio.
2. In the sidebar, select **Dynamic Plugins**.
3. Read the security notice regarding arbitrary bytecode execution.
4. Click **+ Load Plugin JAR**.
5. Select `sample-plugin/target/sample-plugin-1.0.0.jar`.
6. The plugin is inspected via reflection, registered, and a new card **"Text & Code Word Counter"** appears immediately on the **Tasks** screen!

### Authoring a Custom Plugin
Create a standard Maven project and declare `plugin-api` with `provided` scope so API classes are not duplicated:

```xml
<dependency>
    <groupId>com.jvmstudio</groupId>
    <artifactId>plugin-api</artifactId>
    <version>1.0.0</version>
    <scope>provided</scope>
</dependency>
```

Implement `TaskPlugin` and annotate your class with `@TaskInfo`:

```java
package com.myorg.plugin;

import com.jvmstudio.plugin.api.*;
import java.util.*;

@TaskInfo(
    id = "custom-processor",
    name = "Custom Data Processor",
    version = "1.0.0",
    author = "Engineering Team",
    description = "Processes datasets with progress checkpoints.",
    apiVersion = "1.0",
    category = "Data Processing"
)
public class CustomProcessorPlugin implements TaskPlugin {

    @Override
    public List<TaskParameterDescriptor> getParameterDescriptors() {
        return List.of(
            TaskParameterDescriptor.ofRequired("inputPath", "Input Path", TaskParameterType.FILE, "", "Path to dataset")
        );
    }

    @Override
    public TaskExecutionResult execute(TaskContext context, Map<String, Object> parameters) throws Exception {
        context.log("Processing started...");
        for (int i = 0; i < 100; i++) {
            // Cooperative pause & cancellation checkpoint:
            context.checkPauseAndCancel();

            Thread.sleep(50);
            context.updateProgress(i + 1, 100);
            context.updateMessage("Processed item " + (i + 1));
        }
        return TaskExecutionResult.success(100, 1024, "Processed 100 items", Collections.emptyMap(), 5000);
    }
}
```

Register your implementation in `META-INF/services/com.jvmstudio.plugin.api.TaskPlugin`.

---

## Java vs C++ Learning Lab: Architectural Comparison

| Dimension | Java 21 (JVM HotSpot) | C++20 / C++23 |
| :--- | :--- | :--- |
| **Dynamic Loading** | `URLClassLoader` loads standardized bytecode `.class` into Metaspace at runtime; isolates modules. | `dlopen()` / `LoadLibrary()` loads native `.so` / `.dll`. Fragile ABI; requires `extern "C"` to avoid C++ name mangling issues. |
| **Reflection & Metadata** | First-class runtime reflection (`java.lang.reflect`, `@Retention(RUNTIME)`). Full class structure inspected dynamically. | Minimal RTTI (`typeid`, `dynamic_cast`). No standard member enumeration or annotation reflection without macros or generators (Qt MOC). |
| **Memory Management** | Automatic Generational GC (G1, ZGC). Prevents use-after-free & double-free. Does **not** replace resource cleanup (`AutoCloseable`). | Deterministic RAII, destructors, and smart pointers (`std::unique_ptr`). Immediate deterministic cleanup without GC pauses. |
| **Concurrency** | Built-in `java.util.concurrent`, ThreadPoolExecutor, formalized Java Memory Model (JSR-133), Virtual Threads. | `std::thread`, `std::jthread`, `std::mutex`. No built-in standard thread pool; requires Boost or custom queues. |
| **Portability** | Bytecode executes identically across any compatible JVM. JavaFX runtime requires target platform native libraries. | Compiles to native CPU assembly (x86_64, ARM64). Zero VM overhead, but separate binaries must be built for each OS. |

---

## Five-Minute Live Demo Script

1. **Minute 1: Concurrency & Responsiveness (Overview & Tasks)**
   - Open **Tasks & Orchestrator**.
   - Start the **Disk & Folder Storage Analyzer** on a folder.
   - While running, click **Pause**; observe status change to `PAUSED` and worker thread idling.
   - Click **Resume**; observe execution resume and finish. Notice the UI remained 100% responsive.
2. **Minute 2: Dynamic Plugin Loading without Restart (Plugins Screen)**
   - Open **Dynamic Plugins**.
   - Note the security architecture warning regarding arbitrary bytecode execution.
   - Click **+ Load Plugin JAR** and select `sample-plugin/target/sample-plugin-1.0.0.jar`.
   - Click **Inspect Reflection Metadata** to view declared methods, annotations, and classloader hierarchy.
   - Navigate to **Tasks**: observe the new **"Text & Code Word Counter"** card loaded dynamically!
3. **Minute 3: Memory & GC Observations (JVM Monitor)**
   - Open **JVM Monitor & GC**.
   - View live heap usage and thread count charts streaming every second.
   - Click **+ Allocate 20 MB Chunk** 3 times; watch the heap chart jump by ~60 MB.
   - Click **Release References**; explain that dereferencing objects removes GC roots.
   - Click **Request GC (System.gc())**; observe the collection counter increment and heap curve decline.
4. **Minute 4: Audit Persistence & CSV Export (Execution History)**
   - Open **Execution History**.
   - Filter by status (`COMPLETED`, `CANCELLED`).
   - Double-click a row to open the full log viewer and summary modal.
   - Click **Export to CSV**; save and verify standard RFC-4180 escaped CSV output.
5. **Minute 5: Java vs C++ Learning Lab**
   - Open **Java vs C++ Lab**.
   - Review the 5 modules connecting the live application state ("What you observed") to core language design tradeoffs.

---

## Native Packaging Notice

To package JVM Studio as an OS-native desktop bundle (`.msi` / `.exe` on Windows, `.dmg` on macOS, `.deb` / `.rpm` on Linux):
- Use the standard JDK tool `jpackage`:
  ```bash
  jpackage --input target/ --name JVMStudio --main-jar desktop-app-1.0.0.jar --main-class com.jvmstudio.ui.MainApp --type msi
  ```
- **Note**: Native installers must be produced on their respective host operating systems because `jpackage` bundles the host platform's native JDK runtime and OS-specific executable format.
