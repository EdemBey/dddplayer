# Intent API: реализация

Пользовательское описание с примерами есть в [README.md](../README.md#intent-api-integration)
и [README_RU.md](../README_RU.md#intent-api-интеграция). Этот документ описывает, как ключи разбираются в коде.
При изменении ключей обновляйте все три документа.

## Точка входа

`PlayerActivity` (`exported`, повторный Intent приходит в `onNewIntent`) принимает `ACTION_VIEW`
для `rtsp`, `http`, `https`, `ftp`, `content`, `file`. Подходят MIME-типы `video/*`, `application/sdp`,
HLS (`application/vnd.apple.mpegurl`, `application/x-mpegURL`), `application/dash+xml`, а также
видео-расширения в пути без MIME-типа (см. `AndroidManifest.xml`). Для не-сетевых URI сначала запрашивается разрешение
`READ_MEDIA_VIDEO` (API 33+) или `READ_EXTERNAL_STORAGE`.

`handleIntent` → `IntentUtils.parseIntent(context, intent)` → `Pair<List<MediaItem>, startIndex>`
→ `PlayerViewModel.loadMedia`. Флаг `return_result` читается отдельно.

## Разбор (`utils/IntentUtils`)

Если есть `video_list`, работает **режим плейлиста**, иначе при наличии `intent.data` разбирается
**одиночный файл**. Если нет ни того ни другого, ничего не открывается (в debug-сборке — тестовое видео).
Запуск из лаунчера (`ACTION_MAIN`) переводит на стартовый экран (`FileBrowserActivity`).

Внутренние ключи стартового экрана (`IntentUtils.EXTRA_HISTORY_KIND/SOURCE/NAME`, `EXTRA_FORCE_M3U`) с префиксом
`top.rootu.dddplayer.` внешним приложениям не нужны: по ним плеер ведет историю «Продолжить просмотр»
и принудительно проверяет ссылку плейлиста на M3U.

### Одиночный файл

| Ключ | Тип | Поле `MediaItem` |
|:---|:---|:---|
| `title` / `android.intent.extra.TITLE` | String | `title` (иначе имя файла из `OpenableColumns.DISPLAY_NAME` или последний сегмент пути) |
| `position` | Int / Long / String | `startPositionMs` (`getLongExtraCompat`) |
| `thumbnail` | String | `posterUri` |
| `headers` | String[] / ArrayList<String> | `headers` (пары имя-значение) |
| `subs`, `subs.name`, `subs.filename` | Uri[] (или ArrayList), String[] | `subtitles`. Если не переданы, для `file://` ищутся файлы рядом с видео (`SubtitleFinder`) |
| `ddd_quality` / `quality` | String (JSON) или Bundle | Альтернативные URL одного видео: `[{"name":"1080p","url":"..."}]` или `{"1080p":"..."}`. Кнопка качества показывается, если вариантов больше одного |

### Плейлист

| Ключ | Тип | Примечание |
|:---|:---|:---|
| `video_list` | Parcelable[] Uri / ArrayList<Uri> / ArrayList<String> | Обязателен |
| `video_list.name` | String[] | Названия (иначе `filename`, иначе сегмент пути) |
| `video_list.filename` | String[] | Имена файлов |
| `video_list.thumbnail` | String[] | Постеры, пустая строка — без постера |
| `video_list.subtitles` | ArrayList<Bundle> | В каждом Bundle: `uris` (Uri[]) и `names` (String[]) |
| `headers` | String[] | Общие для всех элементов |
| `start_index` | Int | Запасной стартовый индекс (если `intent.data` не совпала ни с одним элементом), приводится к допустимому диапазону |
| `position` | Int / Long / String | Применяется только к элементу, URI которого совпадает с `intent.data` |

Стартовый элемент — тот, чей URI совпадает с `intent.data` (основной способ). `start_index` используется, только если
`intent.data` не задана или не найдена в списке; без обоих стартует первый элемент.

`ddd_quality` / `quality` в режиме `video_list` применяется только к стартовому элементу: качества относятся к текущей
серии, а не ко всему плейлисту.

Запасной транспорт для приложений, которые пробрасывают только HTTP-заголовки (например, Lampa Android):
`X-DDD-Quality: [{"name":"1080p","url":"..."}]`. Заголовок разбирается как качества и удаляется из headers, чтобы не
уходить на сервер видео.

### Качества для остальных серий плейлиста

`X-DDD-Quality-Sources` — JSON-объект «индекс элемента в `video_list`» → «URL, отдающий список источников»:

```json
{"0":"https://site/api/player/based?source=44%3Atoken","1":"https://site/api/player/based?source=44%3Atoken2"}
```

Заголовок тоже удаляется из headers. Когда пользователь переходит на серию, у которой нет `ddd_quality`, плеер
запрашивает дескриптор (один раз на серию, с кэшем), собирает список качеств по тому же правилу, что и плагин
(приоритет провайдеров, один пункт на реальный файл, `Auto` = исходная ссылка), и показывает меню качества.

Значение может быть и готовым списком качеств вместо адреса — так передаются уже разрешённые плейлисты
(например, HDrezka), где запрашивать нечего:

```json
{"1":[{"name":"720p","url":"https://.../720.mp4"},{"name":"1080p","url":"https://.../1080.mp4"}]}
```

Такой список сразу становится `urlQualities` серии, сетевого запроса нет.

### Озвучки отдельными потоками

`X-DDD-Voiceover-Path: /voiceovers` — озвучки серии отдает адрес самой серии с этим путем, query остается прежним:
для `https://site/play.m3u8?q=1080&url=...` это `https://site/voiceovers?q=1080&url=...`. Нужен, когда озвучка
вшита в видео и у каждой озвучки свой поток: переключить ее дорожкой нельзя. Заголовок один на весь плейлист и не
зависит от индексов, поэтому работает и когда Lampa Android передает одну серию вместо списка. Путь должен
начинаться с `/`, адрес строится только для http/https.

Заголовок удаляется из headers. Когда серия начинает играть, плеер один раз запрашивает адрес
(`MediaItem.voiceoverSourceUrl`, `UrlQualityLoader.loadVoiceovers`) и хранит ответ в элементе (`MediaItem.voiceovers`):

```json
{"voiceovers":[
  {"id":"lostfilm","name":"LostFilm","selected":true,"url":"https://.../stream.m3u8",
   "qualities":[{"name":"1080p","url":"https://.../1080.m3u8"},{"name":"720p","url":"https://.../720.m3u8"}]}
]}
```

- `name` и `url` (http/https) обязательны, `url` — поток озвучки в качестве по умолчанию;
- `selected` — озвучка, которую играет ссылка серии из `video_list`;
- `qualities` — качества этой озвучки, они заменяют `urlQualities` серии при переключении;
- `id` — имя озвучки, не меняющееся от серии к серии (по умолчанию равно `name`).

Если озвучек больше одной, кнопка аудио открывает их список вместо списка дорожек
(`PlayerFragment.showAudioTrackMenu`). `PlayerViewModel.setVoiceover` подменяет ссылку серии с той же позиции и
оставляет прежнее качество, если оно есть у новой озвучки (сравнение по `name`), иначе играет `url` озвучки и в меню
качества появляется пункт `Auto`. Выбранный `id` запоминается до загрузки нового плейлиста: в следующих сериях эта
озвучка включается сама, как только придет их список. До этого серия секунду-две играет со своей озвучкой по
умолчанию и затем перезагружается с той же позиции.

### Продолжение просмотра для серий плейлиста

- `X-DDD-Item-Keys` — `{"0":"rezka:10877:56:1:1", ...}`: стабильный ключ серии (`MediaItem.resumeKey`). Настройки и
  позиция хранятся по `key:<ключ>`, а не по URL — нужно, когда ссылки подписаны и меняются при каждом запросе.
- `X-DDD-Positions` — `{"1":734000}`: позиция в мс, известная приложению (`MediaItem.knownPositionMs`). Используется,
  если у плеера нет своей записи для серии.

Оба заголовка удаляются из headers.

### Подмена звука на участке видео

- `X-DDD-Audio-Overlay` — `{"0":{"music":"https://.../ending.m4a","segments":"https://.../skip-times?malId=1&episode=2"}}`:
  «индекс элемента в `video_list`» → музыка и адрес участков (`MediaItem.endingOverlay`). Плеер сам добавляет к адресу
  `duration=<секунды>`, разбирает ответ `{"data":[{"type":"ed","startTime":..,"endTime":..}]}` и на эндинге глушит
  видео, проигрывая музыку синхронно с позицией, паузой и перемоткой. Участки короче 87 секунд и участки кроме `ed`
  игнорируются, замена не длиннее 90 секунд. Заголовок удаляется из headers.

### Пропуск опенинга и эндинга

- `X-DDD-Segments` — `{"0":[{"type":"op","startTime":0,"endTime":89},{"type":"ed","startTime":1273,"endTime":1372}],"1":"https://..."}`:
  «индекс элемента в `video_list`» → готовые сегменты (секунды) или адрес (`MediaItem.skipSegments` / `skipSegmentsUrl`).
  По адресу плеер скачивает сегменты, когда известна длительность серии; `episodeLength=0` в адресе заменяется
  на длительность в целых секундах (так AniSkip подбирает тайминги), а `duration=0` — на длительность в секундах
  с долями до 3 знаков, например `1512.085` (kopenkin `/api/player/skip-times` требует длительность с точностью
  ±2 с, без нее отвечает 400). Ответ: `{"data":[...]}` в формате выше, ответ AniSkip v2
  `{"results":[{"interval":{"startTime":..,"endTime":..},"skipType":"op"}]}` или сразу массив.
  Учитываются `op`/`ed` (и `mixed-op`/`mixed-ed`), остальное игнорируется (`SkipSegmentsLogic`).
- Пока позиция внутри сегмента (кроме последней секунды), видна кнопка «Пропустить опенинг/эндинг»; при скрытой панели
  ее нажимает OK на пульте. На полосе прокрутки сегменты отмечены. Настройка `auto_skip_segments` (выключена
  по умолчанию) перематывает каждый сегмент сразу, один раз за серию. Остальное настраивается в категории «Сегменты»:
  показ и цвет полос (`skip_segments_marks`, `skip_segments_color`) и время показа кнопки (`skip_segments_button_seconds`;
  OK пропускает весь сегмент и после того, как кнопка скрылась). Сегменты также становятся главами (`SkipSegmentsLogic.mergeChapters`):
  «Опенинг/Эндинг» на старте и «Эпизод» на конце, поэтому кнопки следующей/предыдущей главы, меню глав и метки
  на полосе работают с ними. Заголовок удаляется из headers.

Массивы строк читает `getSmartStringArray`: подходят `String[]`, `ArrayList<String>`, `CharSequence[]`
и `ArrayList<CharSequence>`.

## Позиция от внешнего приложения

`startPositionMs > 0` у стартового элемента:
- при `external_position_mode = APPLY` (по умолчанию) плеер сразу стартует с этой позиции;
- при `= ASK` плеер стартует с 0 и показывает диалог продолжения с этой позицией.

Позиция из Intent приоритетнее сохраненной в плеере. См. [PLAYLISTS.md](PLAYLISTS.md#продолжение-просмотра-handleresumelogic).

## Результат (`return_result = true`)

`PlayerActivity.finish()` вызывает `setResult(RESULT_OK, Intent("top.rootu.dddplayer.intent.result.VIEW"))`:

| Ключ | Тип | Значение |
|:---|:---|:---|
| `data` | Uri | URI текущего (последнего) элемента |
| `position` | Long | Позиция в мс. При завершении видео равна длительности |
| `duration` | Long | Длительность в мс |
| `end_by` | String | `completion` (видео закончилось само; при `return_result` активити закрывается) или `user` |
