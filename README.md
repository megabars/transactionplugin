# Transaction Monitor — IntelliJ IDEA Plugin

Real-time Spring Boot transaction monitoring plugin. Shows completed transaction statistics as **inlay hints** above `@Transactional` methods and in a dedicated **Tool Window** — no code changes required in your application.

![Inlay hint example](docs/hint-preview.png)

## Features

- **Inlay hints** above `@Transactional` methods:
  ```
  ✓ 342ms | SQL:5 batch:2 | ↑3 ✎2 ↓1
  ✗ 89ms | NullPointerException
  ```
- **Tool Window** with full transaction history (table with sorting and status filter)
- **Detail Panel** — SQL queries list, entity operation counts, exception stack trace, "Navigate to source" button
- Works with **Java and Kotlin** Spring Boot applications
- No dependencies to add to your project — uses Java Agent attached via Attach API
- Compatible with **IntelliJ IDEA Community and Ultimate 2023.3+**

## What's captured per transaction

| Field | Description |
|---|---|
| Duration | Wall-clock time in ms |
| Status | `COMMITTED` / `ROLLED_BACK` |
| Method | `ClassName.methodName()` |
| Propagation | `REQUIRED`, `REQUIRES_NEW`, `NESTED`, … |
| Isolation | `READ_COMMITTED`, `SERIALIZABLE`, … |
| ReadOnly | From `@Transactional(readOnly=true)` |
| SQL queries | Count + actual SQL strings (up to 50) |
| Batch executions | Number of `executeBatch()` calls |
| Inserts / Updates / Deletes | Entity operation counts via Hibernate Statistics |
| Exception | Type, message, top-10 stack frames on rollback |

## How it works

```
IntelliJ Plugin                       Spring Boot App (JVM)
─────────────────                     ─────────────────────────
SpringBootRunConfig                   AbstractPlatformTransactionManager
Extension detects                  ←─ Java Agent (Byte Buddy) intercepts
process PID                           doCommit / doRollback

AgentAttachService ──Attach API──→   AgentMain.agentmain()
  loadAgent(jar, port=17321)          installs instrumentation

TransactionStore ←──TCP socket──────  SocketReporter
  ServerSocket(17321)                 sends newline-delimited JSON
  ring buffer (1000 records)

TransactionToolWindow   ←─────────── UI refreshed on new records
InlayHintsProvider
```

## Installation

### From release ZIP
1. Download `plugin-0.1.0.zip` from [Releases](../../releases)
2. **Settings → Plugins → ⚙ → Install Plugin from Disk** → select the ZIP
3. Restart IDE

### Build from source
```bash
git clone https://github.com/megabars/transactionplugin
cd transactionplugin
./gradlew :plugin:buildPlugin
# → plugin/build/distributions/plugin-0.1.0.zip
```

**Requirements:** JDK 17+, Gradle 8.8 (wrapper included)

## Usage

1. Open a Spring Boot project in IntelliJ
2. Run the application via a **Run Configuration** (the plugin auto-attaches the agent)
3. Trigger your `@Transactional` methods (via HTTP requests, tests, etc.)
4. Inlay hints appear above methods; open **Transaction Monitor** tool window (right panel) for full history
5. Click any row → detail panel shows SQL queries, entity counts, exception info

> **Note:** For Hibernate entity counts (`insertCount`, `updateCount`, `deleteCount`) the agent
> automatically enables `hibernate.generate_statistics`. This has a small performance overhead —
> disable in production by not using the plugin.

## Project structure

```
transactionplugin/
├── agent/      # Java Agent (fat JAR) — Byte Buddy instrumentation + IPC
└── plugin/     # IntelliJ Plugin — UI, TCP server, Attach API
```

## Requirements

- IntelliJ IDEA 2023.3+ (Community or Ultimate)
- Spring Boot 3.x application (Java 17+)
- JDK with Attach API support (standard in JDK 9+)

## License

MIT
