# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Язык общения
Только русский.

## Архитектура

Два Gradle-модуля:
- `agent/` — Java Agent (fat JAR, Byte Buddy 1.14.18, Java 11, пакет `com.txplugin.agent`)
- `plugin/` — IntelliJ Plugin (Kotlin 1.9.23, IJ Platform 2023.3 / `intellijPlatform` plugin 2.1.0, Gson 2.11.0, пакет `com.txplugin.plugin`)

Версия плагина задаётся в корневом `build.gradle.kts` (поле `version`), текущая — 0.3.0.

IPC: агент → TCP localhost:17321 → плагин (newline-delimited JSON, ручная сериализация без Jackson).

```
Spring Boot App                     IntelliJ Plugin
──────────────                      ───────────────
@Transactional method               TransactionStore (Application Service)
  ↓ Byte Buddy intercepts             TCP ServerSocket(:17321)
TransactionInstrumentation            ring buffer (1000 records)
  ↓ collects SQL                      PersistentStateComponent (transactionStore.xml)
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

Артефакт: `plugin/build/distributions/plugin-<version>.zip` (версия задаётся в корневом `build.gradle.kts`)

`plugin/build.gradle.kts` копирует собранный agent JAR в `plugin/build/resources/main/agent/` через `processResources`. При установке плагин извлекает JAR из resources если нет файла `plugin/agent/transaction-agent.jar` (dev-path).

**Тесты** — 104 unit-теста в пяти файлах. Запуск: `./gradlew :agent:test :plugin:test`.

Agent (Java):
- `agent/src/test/java/com/txplugin/agent/JsonEscapeTest.java`
- `agent/src/test/java/com/txplugin/agent/SqlInterceptorTest.java`
- `agent/src/test/java/com/txplugin/agent/TransactionContextTest.java`

Plugin (Kotlin):
- `plugin/src/test/kotlin/com/txplugin/plugin/ui/TransactionTableModelTest.kt`
- `plugin/src/test/kotlin/com/txplugin/plugin/model/TransactionRecordModelTest.kt`

```bash
# Запуск одного тестового класса
./gradlew :agent:test --tests "com.txplugin.agent.JsonEscapeTest"
./gradlew :plugin:test --tests "com.txplugin.plugin.ui.TransactionTableModelTest"

# Запуск одного теста по имени метода
./gradlew :agent:test --tests "com.txplugin.agent.SqlInterceptorTest.имяМетода"

# Верификация плагина
./gradlew :plugin:verifyPlugin
```

## Ключевые файлы

**Agent (Java, пакет `com.txplugin.agent`):**
- `agent/src/main/java/com/txplugin/agent/AgentMain.java` — `premain`/`agentmain`, парсит `port=PORT`, инициализирует Byte Buddy трансформер
- `agent/src/main/java/com/txplugin/agent/TransactionInstrumentation.java` — advice-классы: TX (`invokeWithinTransaction`), PreparedStatement (execute/addBatch/executeBatch/setXxx), plain Statement
- `agent/src/main/java/com/txplugin/agent/TransactionContext.java` — `ThreadLocal<Deque>` для nested TX; `push()`/`current()`/`pop()`
- `agent/src/main/java/com/txplugin/agent/SocketReporter.java` — TCP-клиент, ring buffer 1000, reconnect каждые 3 сек, **ручная** JSON-сериализация (Jackson недоступен через system classloader)
- `agent/src/main/java/com/txplugin/agent/SqlInterceptor.java` — static helpers для JDBC-interception; пять ThreadLocal: `PREPARED_SQL`, `PREPARED_PARAMS` (LinkedHashMap), `BATCH_ROW_CAPTURED`, `BATCH_EXEC_DEPTH` (int[], `.remove()` при возврате к 0), `BATCH_PARAMS_LIST`
- `agent/src/main/java/com/txplugin/agent/TransactionRecord.java` — POJO, сериализуется вручную в `SocketReporter`; константа `MAX_SQL_QUERIES = 50` — лимит SQL-запросов на транзакцию

**Plugin (Kotlin, пакет `com.txplugin.plugin`):**
- `plugin/src/main/kotlin/com/txplugin/plugin/store/TransactionStore.kt` — **Application**-level service (не Project): TCP-сервер, ring buffer, persistence, listeners, CodeVision invalidation; парсит JSON через **Gson**
- `plugin/src/main/kotlin/com/txplugin/plugin/settings/TransactionSettings.kt` — `PersistentStateComponent` с пользовательскими настройками (`maxRecords`, `port`, `showCodeVision`); сохраняется в `transactionSettings.xml`
- `plugin/src/main/kotlin/com/txplugin/plugin/settings/TransactionSettingsConfigurable.kt` — UI настроек в **Settings → Tools → Transaction Monitor**
- `plugin/src/main/kotlin/com/txplugin/plugin/run/TransactionJavaProgramPatcher.kt` — `JavaProgramPatcher`: добавляет `-javaagent=...=port=PORT` в Run Config
- `plugin/src/main/kotlin/com/txplugin/plugin/ui/TransactionCodeVisionProvider.kt` — Code Vision lens над `@Transactional` методами
- `plugin/src/main/kotlin/com/txplugin/plugin/ui/TransactionToolWindowFactory.kt` — Tool Window: JBSplitter с таблицей и деталями
- `plugin/src/main/kotlin/com/txplugin/plugin/ui/TransactionDetailPanel.kt` — правая панель: SQL, метрики, кнопка навигации к методу; метод `clear()` сбрасывает все поля в начальное состояние (вызывается из кнопки Clear в тулбаре)
- `plugin/src/main/kotlin/com/txplugin/plugin/ui/TransactionTableModel.kt` — модель таблицы для `JBTable`
- `plugin/src/main/kotlin/com/txplugin/plugin/model/TransactionRecord.kt` — data class + `inlayHintText` (computed property с `get()`, **не** stored field — иначе Gson через no-arg конструктор вычислит значение из дефолтов и оно не обновится). `TransactionStatus` имеет `displayName` (`"COMMITTED"` / `"ROLLED BACK"` с пробелом) — используется в таблице вместо `name` для консистентного отображения с detail panel.

## Точки входа плагина (plugin.xml)

7 зарегистрированных расширений:
- `toolWindow` — "Transaction Monitor" (anchor=right, icon=transaction.svg)
- `codeInsight.codeVisionProvider` — TransactionCodeVisionProvider
- `notificationGroup` — "TransactionMonitor" (displayType=BALLOON)
- `applicationService` — TransactionStore
- `applicationService` — TransactionSettings
- `applicationConfigurable` (parentId=tools) — TransactionSettingsConfigurable
- `java.programPatcher` — TransactionJavaProgramPatcher

## Ключевые решения

**Настройки**: `TransactionSettings` (`PersistentStateComponent`, `transactionSettings.xml`) хранит `maxRecords` (100–10 000, default 1000), `port` (1024–65535, default 17321), `showCodeVision` (bool, default true). `TransactionStore` и `TransactionCodeVisionProvider` читают их напрямую через `TransactionSettings.getInstance()`. Изменение порта вступает в силу после перезапуска IDE. Изменение `showCodeVision` мгновенно инвалидирует Code Vision через `CodeVisionHost`.

**Порт и фоллбэк**: `TransactionStore` биндит `ServerSocket(TransactionSettings.port, 50, InetAddress.getLoopbackAddress())`; если занят — `ServerSocket(0, 50, loopback)` (случайный порт). Bind только на loopback — агент подключается к `127.0.0.1`, внешние соединения невозможны. `TransactionJavaProgramPatcher` читает `TransactionStore.getInstance().port`, поэтому агент всегда получает корректный порт автоматически. `bindServerSocket()` вызывается **синхронно** в `init`-блоке (не в фоновом потоке) — иначе patcher прочитает `port=0` до завершения биндинга. На каждый принятый клиентский сокет устанавливается `soTimeout = 30_000` мс — если агент завис и не шлёт данные, поток-читатель разблокируется через 30 сек. Входящие строки ограничены `MAX_LINE_BYTES = 1 MiB` — проверка выполняется в байтах (`line.toByteArray(UTF_8).size`) после чтения; строки сверх лимита логируются и пропускаются (не обрабатываются, но память уже выделена).

**Проверка версии JVM** (`TransactionJavaProgramPatcher`): агент скомпилирован для Java 11 (class file version 55). Инъекция в JVM < 11 вызывает `UnsupportedClassVersionError` и fatal crash. Поэтому `patchJavaParameters()` проверяет версию JDK через `JavaSdk.getInstance().getVersion(jdk).isAtLeast(JavaSdkVersion.JDK_11)` — если JDK < 11, агент не инжектируется.

**Поиск agent JAR** (`TransactionJavaProgramPatcher`): сначала `pluginPath/agent/transaction-agent.jar` (production install), затем извлечение из ресурсов плагина во временный файл (sandbox/dev). Временный файл кэшируется в `companion object` через double-checked locking — не извлекается заново при каждом запуске.

**Spring в агенте — `compileOnly`**: `spring-tx`/`spring-context` не входят в fat JAR агента — загружаются из classpath целевого приложения в рантайме.

**Шейдинг Byte Buddy**: агент использует Shadow Gradle plugin (`com.github.johnrengelman.shadow`) и relocate `net.bytebuddy → com.txplugin.agent.bytebuddy`. Без этого крупные проекты (Mockito, Hibernate, Spring 3.x) приносят в classpath старую версию BB, она перекрывает агентскую, и `AbstractMethodError` на `AgentBuilder$Transformer.transform()` ломает всю инструментацию молча. После шейдинга классы `com.txplugin.agent.bytebuddy.*` не пересекаются с `net.bytebuddy.*` приложения. Задача `processResources` плагина ссылается на `:agent:shadowJar`, а не `:agent:jar`.

**Classloader**: `appendToSystemClassLoaderSearch` — НЕ `appendToBootstrapClassLoaderSearch` (вызывает LinkageError из-за дублирования Byte Buddy). Метод называется `injectIntoSystemClassLoader`. `JarFile` передаётся в `appendToSystemClassLoaderSearch` и **намеренно не закрывается** — спецификация `Instrumentation` требует, чтобы он жил на протяжении всего classloader-а.

**Без Jackson в агенте**: ручная JSON-сериализация в `SocketReporter.java` — Jackson недоступен через system classloader в Spring Boot fat JAR. Escape-логика обрабатывает `"`, `\`, `\n`, `\r`, `\t`, unicode < 0x20, а также surrogate pairs (символы вне BMP — emoji и некоторые CJK): каждая пара `high+low surrogate` кодируется как два `\uXXXX`-эскейпа, что является валидным JSON. Итерация ведётся по `char` с явным `i += 2` для surrogate pair. `connectAndStream()` использует peek→flush→poll: запись удаляется из буфера только после успешного `flush()` — при обрыве соединения она остаётся в буфере и отправится после переподключения. Poll защищён проверкой идентичности (`peekFirst() == record`) — если `enqueue()` вытеснил запись во время записи в сокет, мы не удаляем следующую (ещё неотправленную).

**SqlInterceptor ThreadLocal cleanup**: `getPreparedSql()` вызывает `.remove()` сразу после `.get()` — не случайность. Без этого pooled-потоки увидят SQL от предыдущего PreparedStatement того же потока. Для batch: SQL удаляется в `onBatchExecuteEnter()`, а не в `onAddBatch()` — SQL должен жить через все вызовы `addBatch()`.

**Перехват параметров PreparedStatement**: `SetParameterAdvice` перехватывает все `setXxx(int parameterIndex, value)` методы (кроме `setNull`). Параметры хранятся в `LinkedHashMap<Integer, Object>` — ключ по индексу дедуплицирует двойные вызовы от JDBC proxy. Форматируются как `[1='val', 2='val2']` и добавляются к SQL-строке в `sqlQueries`.

**Proxy double-call**: JDBC connection pool оборачивает PreparedStatement в proxy, из-за чего `setXxx`, `addBatch`, `executeBatch`, `execute` срабатывают несколько раз на одном потоке. Решения:
- `setXxx` — `LinkedHashMap.put()` перезаписывает по тому же ключу
- `addBatch` — флаг `BATCH_ROW_CAPTURED` (сбрасывается при следующем `setXxx`)
- `executeBatch` — depth-counter `BATCH_EXEC_DEPTH`: только первый (outermost) вызов считается
- `execute`/`executeUpdate`/`executeQuery` — `getPreparedSql()` удаляет SQL из ThreadLocal при первом вызове; повторные вызовы получают `null` и игнорируются в `onPreparedExecute` (ранний `return`). Счётчик `sqlQueryCount` инкрементируется только когда `sql != null`.

**`batchCount`**: количество строк добавленных через `addBatch()` (не количество вызовов `executeBatch()`). Отображается в Transaction Info и всплывающем окне хинта. `sqlQueryCount` инкрементируется один раз в `onBatchExecuteEnter()` (не в `onAddBatch()`) — один `executeBatch()` = один SQL-оператор.

**Byte Buddy advice**: два отдельных класса для enter/exit (требование Byte Buddy). Аргументы `invokeWithinTransaction(Method, Class<?>, InvocationCallback)` — индексы 0 и 1, не 1 и 2. `doCommit`/`doRollback` — абстрактные методы, Byte Buddy их пропускает; статус транзакции определяется через `@Advice.Thrown` в exit advice. `SetParameterAdvice` применяется с `Assigner.Typing.DYNAMIC` чтобы autobox примитивы (int, long, boolean и т.д.) в Object.

**Propagation и слияние SQL**: в exit advice `isNewTransaction` включает `REQUIRES_NEW`, `NESTED`, `NOT_SUPPORTED`, `NEVER` — все они получают отдельный `TransactionRecord`. `REQUIRED`, `SUPPORTS`, `MANDATORY` при наличии parent-контекста сливают SQL в родителя и отдельную запись не отправляют. `NOT_SUPPORTED` и `NEVER` **обязаны** быть в `isNewTransaction` — иначе их SQL (выполняющийся вне транзакции) ошибочно атрибутируется внешней TX.

**Определение статуса транзакции**: если `thrown == null` → COMMITTED; если `thrown != null` → проверяем `noRollbackFor`/`noRollbackForClassName` из `@Transactional` (сохранены в `TransactionContext`) через `committedDespiteException()` — если исключение входит в список исключений из отката, статус COMMITTED, иначе ROLLED_BACK. Это корректно обрабатывает `@Transactional(noRollbackFor = SomeException.class)`.

**Stack trace исключения**: `buildStackTrace()` рекурсивно обходит цепочку `getCause()` (до 5 уровней, защита от циклических ссылок через `cause != t`), каждый уровень предваряется `Caused by:`. Это отображает реальную первопричину вместо верхнего wrapper-исключения.

**Фильтр типов в AgentBuilder**: тайп-матчер исключает абстрактные классы: для `Connection` — `.not(nameContains("AbstractConnection"))` (согласовано с `applyAdvice`), для `Statement` — `.not(nameContains("Abstract"))`. Без этого абстрактные классы попадали бы под инструментацию, создавая лишний overhead.

**Code Vision клик**: при клике на хинт открывается только popup с деталями транзакции — Tool Window больше не открывается принудительно.

**methodKey**: `"className#methodName(ParamType1,ParamType2)"` — включает типы параметров для корректной работы с перегруженными методами. Агент использует `Class.getSimpleName()`, плагин — `canonicalText.substringBefore('<').substringAfterLast('.')` для совпадения.

**Навигация к источнику**: реализована через PSI (`findMethodsByName` + сравнение `parameterTypes`), не через `lineNumber` (поле не заполняется агентом).

**Refresh CodeVision**: перебираем все открытые `TextEditor` и инвалидируем явно по каждому (вариант с `editor=null` ненадёжен в IJ 2023.3). `.toList()` создаёт snapshot перед итерацией:
```kotlin
FileEditorManager.getInstance(project).allEditors.toList()
    .filterIsInstance<TextEditor>()
    .forEach { fileEditor ->
        codeVisionHost.invalidateProvider(
            CodeVisionHost.LensInvalidateSignal(fileEditor.editor, listOf(TransactionCodeVisionProvider.ID))
        )
    }
```
`DaemonCodeAnalyzer` не использовать — вызывает задержку.

**Персистентность**: `TransactionStore` реализует `PersistentStateComponent`, сохраняет последнюю транзакцию каждого метода в `transactionStore.xml`.

**Nested TX**: `TransactionContext` поддерживает стек — SQL всегда попадает в innermost активную транзакцию. При pop inner-метода: если propagation входит в `isNewTransaction` (`REQUIRES_NEW`, `NESTED`, `NOT_SUPPORTED`, `NEVER`) — отправляем отдельную запись; иначе — мёржим в parent и возвращаемся без отправки.

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

Batch-запрос отображается одной consolidated-записью:
```
INSERT INTO users (id, name) VALUES (?, ?)  [batch: 3 rows]
  [1='1', 2='Alice']
  [1='2', 2='Bob']
  [1='3', 2='Charlie']
```

При превышении `MAX_BATCH_ROWS = 1000` параметры усекаются, и заголовок содержит пометку:
```
INSERT INTO users (id, name) VALUES (?, ?)  [batch: 1500 rows, params for first 1000 shown]
  [1='1', 2='Alice']
  ...
```

Параметры захватываются через `SetParameterAdvice` → `onSetParameter()`. Для batch: `onAddBatch()` накапливает параметры в `BATCH_PARAMS_LIST` (ThreadLocal), `onBatchExecuteEnter()` при depth==1 собирает одну запись через `buildBatchEntry(sql, ctx.batchCount, paramsList)` и добавляет в `ctx.sqlQueries`. Реальное число строк берётся из `ctx.batchCount`, а не из `paramsList.size()` (который ограничен `MAX_BATCH_ROWS`). Значения обрезаются до 100 символов.

## Логирование агента

Все сообщения агента на уровне `FINE` (не выводятся в консоль при стандартном JUL-уровне). Listener Byte Buddy удалён.
