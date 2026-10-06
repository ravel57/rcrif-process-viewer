# RCrif Process Viewer

Переделка части с лейаутами из `RCrifLayoutTool` в отдельный инструмент: схема процедуры
рисуется по `Layout.xml`, а поверх неё показывается фактическое прохождение процесса
по конкретной заявке.

## Что сделано по пунктам

| № | Требование | Где реализовано |
|---|---|---|
| 1 | Чтение процесса из указываемой папки, без git | `service/ProcessLoader.kt`. `GitUnit` и вся зависимость от jgit выброшены |
| 2 | Разделённый экран убран | Вместо двух `CodeArea` — одна схема `ui/ProcessDiagramPane.kt` |
| 3 | Прежняя схема обводок убрана | Диффовая раскраска (`diff.css`, `highlightDiff`, `buildSpansAndNav`) удалена целиком. Новая схема подключается реализацией `ui/ActivityOutlineScheme.kt` — есть пустая заготовка `CustomActivityOutlineScheme` |
| 4 | Кружок со счётчиком прохождений | `ProcessDiagramPane.buildCounterNode`, правый верхний угол блока |
| 5 | Зелёная обводка у пройденных | `PassedActivityOutlineScheme` |
| 6 | Прохождение определяется по БД | `db/ActivityPassRepository.kt` |
| 7 | Двойной клик по кружку → дата-документы | `ui/DataDocumentsWindow.kt` |
| 8 | Поле номера заявки рядом с выбором папки | `RCrifProcessViewer.buildTopBar` |
| 9 | Вместо дерева изменений — список процедур | `RCrifProcessViewer.buildProceduresPanel`, данные из `ProcessLoader.procedures()` |

## Ожидаемая структура папки процесса

```
<корень>/
  MainFlow/Layout.xml
  MainFlow/<Активность>/Properties.xml
  Procedures/<Процедура>/Layout.xml
  Procedures/<Процедура>/<Активность>/Properties.xml
```

Процедурой считается `MainFlow` и любая папка внутри `Procedures`, в которой есть `Layout.xml`.
Активности сопоставляются с элементами схемы по `ReferenceName` из `Properties.xml`
(если атрибута нет — по имени папки).

## Что осталось подключить

1. **БД (п.6).** Сейчас работает `StubActivityPassRepository` — детерминированная заглушка,
   чтобы UI был живым без базы. Как только будет известна схема, в
   `db/JdbcActivityPassRepository.kt` нужно подставить SQL в `SQL_PASS_COUNTS` и
   `SQL_DATA_DOCUMENT`, добавить драйвер в `build.gradle.kts` и заменить одну строку
   в `RCrifProcessViewer`:

   ```kotlin
   private val repository: ActivityPassRepository = JdbcActivityPassRepository(jdbcUrl, user, password)
   ```

   Контракт: `loadPassCounts(номер заявки, процедура, список активностей)` возвращает
   `ReferenceName -> число прохождений`. Ноль или отсутствие ключа = активность не проходили.

2. **Схема обводок (п.3).** Правила ещё не описаны. Когда будут — реализовать
   `CustomActivityOutlineScheme.applyTo` и присвоить
   `diagram.outlineScheme = CustomActivityOutlineScheme()`; перерисовка произойдёт сама.

3. **Значения дата-документов (п.7).** Список документов настоящий — читается из
   `ReferredDocuments` в свойствах активности. Колонки «Значение» и «Обновлён» —
   заглушки из репозитория.

## Запуск

```
./gradlew run
```

Выбранная папка и последний номер заявки запоминаются между запусками
(`~/.config/rcrif-process-viewer/config.properties`, на Windows — `%APPDATA%`).
