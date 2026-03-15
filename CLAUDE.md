# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Язык общения
Только русский.

## Архитектура

Два Gradle-модуля:
- `agent/` — Java Agent (fat JAR, Byte Buddy, Java 17)
- `plugin/` — IntelliJ Plugin (Kotlin, IJ Platform 2023.3+)

IPC: агент → TCP localhost:17321 → плагин (newline-delimited JSON, ручная сериализация без Jackson).

```
Spring Boot App                     IntelliJ Plugin
──────────────                      ───────────────
@Transactional method               TransactionStore (Application Service)
  ↓ Byte Buddy intercepts             TCP ServerSocket(:17321)
TransactionInstrumentation            ring buffer (1000 records)
  ↓ collects SQL, Hibernate stats     PersistentStateComponent (transactionStore.xml)
TransactionContext (ThreadLocal)      ↓
  ↓ supports nested TX              TransactionCodeVisionProvider (Code Vision lens)
SocketReporter (TCP client)         TransactionToolWindowFactory (Table + Detail panel)
  ↓ NDJSON                          TransactionJavaProgramPatcher (-javaagent injection)
```

## Сборка и запуск

```bash
# Всегда использовать clean при изменениях в агенте, иначе старый JAR останется в ресурсах плагина
./gradlew clean :plugin:buildPlugin

# Инкрементальная сборка (только если агент не менялся)
./gradlew :plugin:buildPlugin

# Запуск sandbox IDE для ручного тестирования
./gradlew :plugin:runIde
```

Артефакт: `plugin/build/distributions/plugin-0.1.0.zip`

`plugin/build.gradle.kts` копирует собранный agent JAR в `plugin/build/resources/main/agent/` через `processResources`. При установке плагин извлекает JAR из resources если нет файла `plugin/agent/transaction-agent.jar` (dev-path).

**Тестов нет** — ни unit-, ни integration-тестов в проекте не существует. Тестирование только ручное через `runIde`.

## Ключевые файлы

**Agent (Java):**
- `agent/.../AgentMain.java` — `premain`/`agentmain`, парсит `port=PORT`, инициализирует Byte Buddy трансформер
- `agent/.../TransactionInstrumentation.java` — 6 пар advice-классов: TX, PreparedStatement, Statement, batch, SessionFactory
- `agent/.../TransactionContext.java` — `ThreadLocal<Deque>` для nested TX; `push()`/`current()`/`pop()`
- `agent/.../SocketReporter.java` — TCP-клиент, ring buffer 1000, reconnect каждые 3 сек, **ручная** JSON-сериализация (Jackson недоступен через system classloader)
- `agent/.../SqlInterceptor.java` — static helpers для JDBC-interception; хранит SQL PreparedStatement через ThreadLocal
- `agent/.../HibernateStatsCollector.java` — читает insert/update/delete счётчики через reflection
- `agent/.../TransactionRecord.java` — POJO, сериализуется вручную в `SocketReporter`

**Plugin (Kotlin):**
- `plugin/.../store/TransactionStore.kt` — **Application**-level service (не Project): TCP-сервер, ring buffer, persistence, listeners, CodeVision invalidation; парсит JSON через **Gson**
- `plugin/.../run/TransactionJavaProgramPatcher.kt` — `JavaProgramPatcher`: добавляет `-javaagent=...=port=PORT` в Run Config
- `plugin/.../ui/TransactionCodeVisionProvider.kt` — Code Vision lens над `@Transactional` методами
- `plugin/.../ui/TransactionToolWindowFactory.kt` — Tool Window: JBSplitter с таблицей и деталями
- `plugin/.../ui/TransactionDetailPanel.kt` — правая панель: SQL, метрики, кнопка навигации к методу
- `plugin/.../ui/TransactionTableModel.kt` — модель таблицы для `JBTable`
- `plugin/.../model/TransactionRecord.kt` — data class + вычисляемое `inlayHintText`

## Ключевые решения

**Classloader**: `appendToSystemClassLoaderSearch` — НЕ `appendToBootstrapClassLoaderSearch` (вызывает LinkageError из-за дублирования Byte Buddy). Метод называется `injectIntoSystemClassLoader`.

**Без Jackson в агенте**: ручная JSON-сериализация в `SocketReporter.java` — Jackson недоступен через system classloader в Spring Boot fat JAR.

**Byte Buddy advice**: два отдельных класса для enter/exit (требование Byte Buddy). Аргументы `invokeWithinTransaction(Method, Class<?>, InvocationCallback)` — индексы 0 и 1, не 1 и 2. `doCommit`/`doRollback` — абстрактные методы, Byte Buddy их пропускает; статус транзакции определяется через `@Advice.Thrown` в exit advice. `JdbcServicesImpl` **не** перехватывается — только `SessionFactoryImpl`; иначе `unavailable=true` срабатывает раньше, чем создаётся реальный `SessionFactory`.

**methodKey**: `"className#methodName(ParamType1,ParamType2)"` — включает типы параметров для корректной работы с перегруженными методами. Агент использует `Class.getSimpleName()`, плагин — `canonicalText.substringBefore('<').substringAfterLast('.')` для совпадения.

**Навигация к источнику**: реализована через PSI (`findMethodsByName` + сравнение `parameterTypes`), не через `lineNumber` (поле не заполняется агентом).

**Refresh CodeVision**:
```kotlin
project.getService(CodeVisionHost::class.java)?.invalidateProvider(
    CodeVisionHost.LensInvalidateSignal(null, listOf(TransactionCodeVisionProvider.ID))
)
```
`DaemonCodeAnalyzer` не использовать — вызывает задержку.

**Персистентность**: `TransactionStore` реализует `PersistentStateComponent`, сохраняет последнюю транзакцию каждого метода в `transactionStore.xml`.

**Nested TX**: `TransactionContext` поддерживает стек — SQL всегда попадает в innermost активную транзакцию.

**Hibernate stats**: `HibernateStatsCollector` использует reflection чтобы не добавлять compile-time зависимость. Автоматически включает `hibernate.generate_statistics` через `SessionFactoryAdvice`.

## Формат хинта над методом

```
✓ COMMITTED  342ms | SQL:5 batch:2 | ↑3 ✎2 ↓1 | REQUIRED
✗ ROLLED BACK  89ms | NullPointerException | REQUIRED
```

## Логирование агента

Все сообщения агента на уровне `FINE` (не выводятся в консоль при стандартном JUL-уровне). Listener Byte Buddy удалён.
