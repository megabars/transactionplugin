# Transaction Monitor — IntelliJ IDEA Plugin

Real-time Spring Boot transaction monitoring plugin. Shows completed transaction statistics as **inlay hints** above `@Transactional` methods and in a dedicated **Tool Window** — no code changes required in your application.

## Features

- **Inlay hints** above `@Transactional` methods:
  ```
  ✓ COMMITTED  342ms | batch:3 | REQUIRED
  ✗ ROLLED BACK  89ms | REQUIRED | NullPointerException
  ```
- **Click on hint** — popup with transaction summary (method, status, duration, propagation, batch rows, exception)
- **Tool Window** with full transaction history (table with sorting and status filter)
- **Detail Panel** — SQL queries with bound parameters, batch row count, exception stack trace, "Navigate to source" button
- Works with **Java and Kotlin** Spring Boot applications
- No dependencies to add to your project — uses Java Agent attached via `-javaagent`
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
| SQL queries | Actual SQL strings with bound parameters (up to 50) |
| Batch rows | Number of rows sent via `addBatch()` |
| Exception | Type, message, top-10 stack frames on rollback |

### SQL with parameters

Each captured query shows its bound parameter values:
```
SELECT * FROM users WHERE id = ?
  [1='42']

INSERT INTO orders (user_id, amount) VALUES (?,?)
  [1='42', 2='99.90']
```

Batch operations are shown as a single consolidated entry:
```
INSERT INTO users (id, name) VALUES (?, ?)  [batch: 3 rows]
  [1='1', 2='Alice']
  [1='2', 2='Bob']
  [1='3', 2='Charlie']
```

## How it works

```
IntelliJ Plugin                       Spring Boot App (JVM)
─────────────────                     ─────────────────────────
TransactionJavaProgramPatcher         TransactionAspectSupport
injects -javaagent into              ← Java Agent (Byte Buddy) intercepts
Run Configuration                      invokeWithinTransaction

                                      PreparedStatement intercepts:
                                        prepareStatement → captures SQL
                                        setXxx           → captures params
                                        addBatch         → accumulates batch rows
                                        executeBatch     → emits consolidated batch entry
                                        execute/executeQuery/executeUpdate

TransactionStore ←──TCP :17321──────  SocketReporter
  ServerSocket(17321)                 sends newline-delimited JSON
  ring buffer (1000 records)

TransactionToolWindow   ←─────────── UI refreshed on new records
TransactionCodeVisionProvider
```

## Installation

### From release ZIP
1. Download `plugin-0.3.0.zip` from [Releases](../../releases)
2. **Settings → Plugins → ⚙ → Install Plugin from Disk** → select the ZIP
3. Restart IDE

### Build from source
```bash
git clone https://github.com/megabars/transactionplugin
cd transactionplugin
./gradlew clean :plugin:buildPlugin
# → plugin/build/distributions/plugin-0.3.0.zip
```

**Requirements:** JDK 11+, Gradle 8.8 (wrapper included)

## Usage

1. Open a Spring Boot project in IntelliJ
2. Run the application via a **Run Configuration** (the plugin auto-injects the agent via `-javaagent`)
3. Trigger your `@Transactional` methods (via HTTP requests, tests, etc.)
4. Inlay hints appear above methods; open **Transaction Monitor** tool window (right panel) for full history
5. Click any row → detail panel shows SQL queries with parameters, batch row count, exception info
6. Click the inlay hint → popup with a quick summary of the last transaction

## Project structure

```
transactionplugin/
├── agent/      # Java Agent (fat JAR) — Byte Buddy instrumentation + IPC
└── plugin/     # IntelliJ Plugin — UI, TCP server, javaagent injection
```

## Requirements

- IntelliJ IDEA 2023.3+ (Community or Ultimate)
- Spring Boot 2.x or 3.x application
- JDK 11+ for the monitored application (JDK 17+ for Spring Boot 3.x)

## License

MIT
