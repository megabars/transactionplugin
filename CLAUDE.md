# Transaction Plugin — CLAUDE.md

## Язык общения
Только русский.

## Архитектура

Два Gradle-модуля:
- `agent/` — Java Agent (fat JAR, Byte Buddy, Java 17)
- `plugin/` — IntelliJ Plugin (Kotlin, IJ Platform 2023.3+)

IPC: агент → TCP localhost:17321 → плагин (newline-delimited JSON, ручная сериализация без Jackson).

## Сборка

```bash
# Всегда использовать clean при изменениях в агенте, иначе старый JAR останется в ресурсах плагина
./gradlew clean :plugin:buildPlugin

# Инкрементальная сборка (только если агент не менялся)
./gradlew :plugin:buildPlugin
```

Артефакт: `plugin/build/distributions/plugin-0.1.0.zip`

## Ключевые решения

**Classloader**: `appendToSystemClassLoaderSearch` — НЕ `appendToBootstrapClassLoaderSearch` (вызывает LinkageError из-за дублирования Byte Buddy).

**Без Jackson в агенте**: ручная JSON-сериализация в `SocketReporter.java` — Jackson недоступен через system classloader в Spring Boot fat JAR.

**Byte Buddy advice**: два отдельных класса для enter/exit (требование Byte Buddy). Аргументы `invokeWithinTransaction(Method, Class<?>, InvocationCallback)` — индексы 0 и 1, не 1 и 2. `doCommit`/`doRollback` — абстрактные методы, Byte Buddy их пропускает; статус транзакции определяется через `@Advice.Thrown` в exit advice.

**Refresh CodeVision**:
```kotlin
project.getService(CodeVisionHost::class.java)?.invalidateProvider(
    CodeVisionHost.LensInvalidateSignal(null, listOf(TransactionCodeVisionProvider.ID))
)
```
`DaemonCodeAnalyzer` не использовать — вызывает задержку.

**Персистентность**: `TransactionStore` реализует `PersistentStateComponent`, сохраняет последнюю транзакцию каждого метода в `transactionStore.xml`.

## Формат хинта над методом

```
✓ COMMITTED  342ms | SQL:5 batch:2 | ↑3 ✎2 ↓1 | REQUIRED
✗ ROLLED BACK  89ms | NullPointerException | REQUIRED
```

## Логирование агента

Все сообщения агента на уровне `FINE` (не выводятся в консоль при стандартном JUL-уровне). Listener Byte Buddy удалён.
