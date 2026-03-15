# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Язык общения
Только русский.

## Архитектура

Два Gradle-модуля:
- `agent/` — Java Agent (fat JAR, Byte Buddy 1.14.18, Java 17)
- `plugin/` — IntelliJ Plugin (Kotlin, IJ Platform 2023.3+, Gson 2.11.0)

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

Артефакт: `plugin/build/distributions/plugin-0.2.0.zip`

`plugin/build.gradle.kts` копирует собранный agent JAR в `plugin/build/resources/main/agent/` через `processResources`. При установке плагин извлекает JAR из resources если нет файла `plugin/agent/transaction-agent.jar` (dev-path).

**Тестов нет** — ни unit-, ни integration-тестов в проекте не существует. Тестирование только ручное через `runIde`.

## Ключевые файлы

**Agent (Java):**
- `agent/.../AgentMain.java` — `premain`/`agentmain`, парсит `port=PORT`, инициализирует Byte Buddy трансформер
- `agent/.../TransactionInstrumentation.java` — advice-классы: TX, PreparedStatement (execute/addBatch/executeBatch/setXxx), Statement, SessionFactory; включает логику Hibernate stats
- `agent/.../TransactionContext.java` — `ThreadLocal<Deque>` для nested TX; `push()`/`current()`/`pop()`
- `agent/.../SocketReporter.java` — TCP-клиент, ring buffer 1000, reconnect каждые 3 сек, **ручная** JSON-сериализация (Jackson недоступен через system classloader)
- `agent/.../SqlInterceptor.java` — static helpers для JDBC-interception; три ThreadLocal: `PREPARED_SQL`, `PREPARED_PARAMS` (LinkedHashMap), `BATCH_ROW_CAPTURED`/`BATCH_EXEC_DEPTH`
- `agent/.../TransactionRecord.java` — POJO, сериализуется вручную в `SocketReporter`

**Plugin (Kotlin):**
- `plugin/.../store/TransactionStore.kt` — **Application**-level service (не Project): TCP-сервер, ring buffer, persistence, listeners, CodeVision invalidation; парсит JSON через **Gson**
- `plugin/.../run/TransactionJavaProgramPatcher.kt` — `JavaProgramPatcher`: добавляет `-javaagent=...=port=PORT` в Run Config
- `plugin/.../ui/TransactionCodeVisionProvider.kt` — Code Vision lens над `@Transactional` методами
- `plugin/.../ui/TransactionToolWindowFactory.kt` — Tool Window: JBSplitter с таблицей и деталями
- `plugin/.../ui/TransactionDetailPanel.kt` — правая панель: SQL, метрики, кнопка навигации к методу
- `plugin/.../ui/TransactionTableModel.kt` — модель таблицы для `JBTable`
- `plugin/.../model/TransactionRecord.kt` — data class + `inlayHintText` (computed property с `get()`, **не** stored field — иначе Gson через no-arg конструктор вычислит значение из дефолтов и оно не обновится)

## Точки входа плагина (plugin.xml)

5 зарегистрированных расширений:
- `toolWindow` — "Transaction Monitor" (anchor=right, icon=transaction.svg)
- `codeInsight.codeVisionProvider` — TransactionCodeVisionProvider
- `notificationGroup` — "TransactionMonitor" (displayType=BALLOON)
- `applicationService` — TransactionStore
- `java.programPatcher` — TransactionJavaProgramPatcher

## Ключевые решения

**Порт и фоллбэк**: `TransactionStore` биндит `ServerSocket(17321)`; если занят — `ServerSocket(0)` (случайный порт). `TransactionJavaProgramPatcher` читает `TransactionStore.getInstance().port`, поэтому агент всегда получает корректный порт автоматически. `bindServerSocket()` вызывается **синхронно** в `init`-блоке (не в фоновом потоке) — иначе patcher прочитает `port=0` до завершения биндинга.

**Поиск agent JAR** (`TransactionJavaProgramPatcher`): сначала `pluginPath/agent/transaction-agent.jar` (production install), затем извлечение из ресурсов плагина во временный файл (sandbox/dev). Временный файл кэшируется в `companion object` через double-checked locking — не извлекается заново при каждом запуске.

**Spring в агенте — `compileOnly`**: `spring-tx`/`spring-context` не входят в fat JAR агента — загружаются из classpath целевого приложения в рантайме.

**Classloader**: `appendToSystemClassLoaderSearch` — НЕ `appendToBootstrapClassLoaderSearch` (вызывает LinkageError из-за дублирования Byte Buddy). Метод называется `injectIntoSystemClassLoader`.

**Без Jackson в агенте**: ручная JSON-сериализация в `SocketReporter.java` — Jackson недоступен через system classloader в Spring Boot fat JAR. Escape-логика обрабатывает `"`, `\`, `\n`, `\r`, `\t` и unicode < 0x20 (SQL может содержать любые символы). `connectAndStream()` использует peek→flush→poll: запись удаляется из буфера только после успешного `flush()` — при обрыве соединения она остаётся в буфере и отправится после переподключения.

**SqlInterceptor ThreadLocal cleanup**: `getPreparedSql()` вызывает `.remove()` сразу после `.get()` — не случайность. Без этого pooled-потоки увидят SQL от предыдущего PreparedStatement того же потока. Для batch: SQL удаляется в `onBatchExecuteEnter()`, а не в `onAddBatch()` — SQL должен жить через все вызовы `addBatch()`.

**Перехват параметров PreparedStatement**: `SetParameterAdvice` перехватывает все `setXxx(int parameterIndex, value)` методы (кроме `setNull`). Параметры хранятся в `LinkedHashMap<Integer, Object>` — ключ по индексу дедуплицирует двойные вызовы от JDBC proxy. Форматируются как `[1='val', 2='val2']` и добавляются к SQL-строке в `sqlQueries`.

**Proxy double-call**: JDBC connection pool оборачивает PreparedStatement в proxy, из-за чего `setXxx`, `addBatch`, `executeBatch` срабатывают дважды на одном потоке. Решения:
- `setXxx` — `LinkedHashMap.put()` перезаписывает по тому же ключу
- `addBatch` — флаг `BATCH_ROW_CAPTURED` (сбрасывается при следующем `setXxx`)
- `executeBatch` — depth-counter `BATCH_EXEC_DEPTH`: только первый (outermost) вызов считается

**`batchCount`**: количество строк добавленных через `addBatch()` (не количество вызовов `executeBatch()`). Отображается в Transaction Info и всплывающем окне хинта.

**Byte Buddy advice**: два отдельных класса для enter/exit (требование Byte Buddy). Аргументы `invokeWithinTransaction(Method, Class<?>, InvocationCallback)` — индексы 0 и 1, не 1 и 2. `doCommit`/`doRollback` — абстрактные методы, Byte Buddy их пропускает; статус транзакции определяется через `@Advice.Thrown` в exit advice. `JdbcServicesImpl` **не** перехватывается — только `SessionFactoryImpl`; иначе `unavailable=true` срабатывает раньше, чем создаётся реальный `SessionFactory`. `SetParameterAdvice` применяется с `Assigner.Typing.DYNAMIC` чтобы autobox примитивы (int, long, boolean и т.д.) в Object.

**methodKey**: `"className#methodName(ParamType1,ParamType2)"` — включает типы параметров для корректной работы с перегруженными методами. Агент использует `Class.getSimpleName()`, плагин — `canonicalText.substringBefore('<').substringAfterLast('.')` для совпадения.

**Навигация к источнику**: реализована через PSI (`findMethodsByName` + сравнение `parameterTypes`), не через `lineNumber` (поле не заполняется агентом).

**Refresh CodeVision**: перебираем все открытые `TextEditor` и инвалидируем явно по каждому (вариант с `editor=null` ненадёжен в IJ 2023.3):
```kotlin
FileEditorManager.getInstance(project).allEditors
    .filterIsInstance<TextEditor>()
    .forEach { fileEditor ->
        codeVisionHost.invalidateProvider(
            CodeVisionHost.LensInvalidateSignal(fileEditor.editor, listOf(TransactionCodeVisionProvider.ID))
        )
    }
```
`DaemonCodeAnalyzer` не использовать — вызывает задержку.

**Персистентность**: `TransactionStore` реализует `PersistentStateComponent`, сохраняет последнюю транзакцию каждого метода в `transactionStore.xml`.

**Nested TX**: `TransactionContext` поддерживает стек — SQL всегда попадает в innermost активную транзакцию.

**Hibernate stats**: `HibernateStatsCollector` использует reflection чтобы не добавлять compile-time зависимость. Автоматически включает `hibernate.generate_statistics` через `SessionFactoryAdvice`.

## Формат хинта над методом

```
✓ COMMITTED  342ms | batch:3 | REQUIRED
✗ ROLLED BACK  89ms | REQUIRED | NullPointerException
```

## SQL Queries в Tool Window

Каждый SQL-запрос отображается с параметрами на следующей строке:
```
SELECT * FROM users WHERE id = ?
  [1='42']

INSERT INTO orders (user_id, amount) VALUES (?,?)
  [1='42', 2='99.90']
```
Параметры захватываются через `SetParameterAdvice` → `onSetParameter()`. Для batch каждая строка `addBatch()` — отдельная запись. Значения обрезаются до 100 символов.

## Логирование агента

Все сообщения агента на уровне `FINE` (не выводятся в консоль при стандартном JUL-уровне). Listener Byte Buddy удалён.
