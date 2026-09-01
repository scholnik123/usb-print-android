# Print capabilities

## Основное правило

Неизвестная capability не считается поддержанной. UI получает не исходный `PrinterCapabilities`, а `EffectivePrintCapabilities` — пересечение реально полученных данных принтера с возможностями конкретного encoder/backend.

```text
USB descriptors ─┐
IEEE-1284 CMD ───┼→ PrinterCapabilities + source/confidence
IPP attributes ──┘                 │
                                   ▼
                     BackendCapabilityDescriptor
                                   │ intersection
                                   ▼
                     EffectivePrintCapabilities
                                   │
                      UI → validator → backend
```

`AUTO` означает «не отправлять принтеру принудительное значение» и не является заявлением поддержки формата, цвета или ориентации.

## Source priority и confidence

| Source | Приоритет | Что реально реализовано |
|---|---:|---|
| `USER_OVERRIDE` | 5 | Только opt-in experimental override конкретного устройства |
| `IPP` | 4 | Реальный `Get-Printer-Attributes` через IPP-over-USB |
| `IEEE1284` | 3 | Device ID, CMD и производные языки печати |
| `USB_DESCRIPTOR` | 2 | Interface class/subclass/protocol и endpoints |
| `KNOWN_PROFILE` | 1 | Versioned hardware-test profiles сохраняются локально; automatic capability promotion намеренно ещё не включён |
| `BACKEND_DEFAULT` | 0 | Явно подписанный безопасный fallback только для legacy raster backend |

Confidence: `CONFIRMED`, `DERIVED`, `DEFAULT`, `EXPERIMENTAL`. Значение IPP получает `IPP/CONFIRMED` только если соответствующий attribute действительно присутствовал в response. Отсутствующий attribute не создаётся автоматически.

## IPP-over-USB discovery

Устройство признаётся IPP-over-USB только при одновременном выполнении условий:

- USB interface class 7;
- subclass 1;
- protocol 4;
- на каждом выбранном interface есть Bulk OUT и Bulk IN;
- найдено минимум два эквивалентных protocol-4 interface.

VID/PID не используется как доказательство IPP. Обычный Printer Class bulk OUT не считается IPP endpoint. В composite device legacy backend использует обычный printer interface, IPP backend — отдельный protocol-4 interface. В IPP-only device legacy/raw fallback запрещён, чтобы PDF не ушёл без HTTP framing.

## Запрашиваемые IPP attributes

`Get-Printer-Attributes` запрашивает ограниченный явный список:

- printer name/info/make-and-model/state/state-reasons/accepting-jobs;
- IPP versions и operations;
- document formats;
- media supported/ready/default;
- media-col supported/ready/database/default;
- media source/type и output bin supported/default;
- resolution, sides, color, copies, quality, orientation и page-ranges;
- job-creation attributes, job-hold, compression;
- URF/PWG raster attributes, если устройство их возвращает.

Неответившие attributes допустимы и остаются неизвестными.

## Реальный mapping

- `media-supported`, `media-ready`, `media-default` и известные `media-size` из `media-col` → стандартные `PaperSize`;
- `media-col` margins (hundredths of millimetre) → `HardwareMarginsMm`;
- rangeOfInteger `x-dimension/y-dimension` → `CustomPaperRangeMicrons` с явным conversion `hundredths mm × 10 = microns`;
- `printer-resolution-supported` → `PrinterResolution(xDpi,yDpi)`, включая 600×1200;
- `pwg-raster-document-resolution-supported` → подтверждённые PWG Raster resolutions;
- `pwg-raster-document-type-supported` → точные raster encodings; текущий encoder принимает только `black_1`, `sgray_8` и `srgb_8`;
- `print-color-mode-supported`/`color-supported` → подтверждённые color modes;
- `sides-supported` → simplex/long-edge/short-edge;
- `copies-supported` → подтверждённый диапазон;
- `media-source`, `media-type`, `output-bin` → `PrinterKeywordOption(rawKeyword, localizedDisplayName)`;
- document formats → доступные языки local backend;
- operations/job-creation attributes → разрешённые IPP операции и Job Template attributes.
- `media-col-supported` → точные collection members, которые разрешено вернуть в `Print-Job`; наличие диапазона само по себе не делает custom media выбираемым.

Raw keyword не теряется: UI показывает русское имя, а `Print-Job` отправляет исходное значение принтера. Неизвестный keyword безопасно отображается как есть.

## IPP Direct effective settings

IPP Direct доступен только для PDF с известным Content-Length, подтверждёнными `Print-Job` и `application/pdf`, состоянием accepting-jobs не равным false и настройками без локальной модификации PDF layout.

В job отправляется только attribute, имя которого присутствует в `job-creation-attributes-supported`:

- copies;
- media;
- media-col с custom `media-size`, если отдельно подтверждены top-level attribute и member;
- sides;
- print-color-mode;
- printer-resolution (включая asymmetric X/Y);
- orientation-requested;
- print-quality;
- page-ranges;
- multiple-document-handling;
- media-source;
- media-type;
- output-bin.

Operation attributes (charset, natural language, printer URI, user/job name, document format, fidelity) формируются отдельно. Unsupported настройки не посылаются «на удачу».

Если response содержит `job-id`/`job-uri` и operations подтверждают `Get-Job-Attributes`, приложение читает `job-state`, reasons, impressions и sheets completed во время короткого bounded polling. При запросе пользователя и подтверждённой операции `Cancel-Job` отправляется cancel. Без IPP остаётся честный legacy статус «Задание передано принтеру».

## IPP PWG effective settings

IPP PWG доступен в development branch только при одновременно подтверждённых условиях:

- найден корректный IPP-over-USB interface;
- `printer-is-accepting-jobs` не равен false;
- `Print-Job` присутствует в `operations-supported`;
- `image/pwg-raster` присутствует в `document-format-supported`;
- документ относится к PDF, image или UTF-8 text;
- пересечение reported resolution с encoder subset содержит symmetric 300 или 600 DPI;
- reported PWG raster type либо общий confirmed color mode допускает реально выдаваемый `black_1`, `sgray_8` или `srgb_8` path.

Page range, odd/even, reverse order, copies, collate, paper, orientation, margins, fit/fill/actual/custom scale, positioning, color mode и resolution применяются software-side до/при создании PWG payload. Поэтому `copies`, `page-ranges`, standard `media`, `orientation-requested`, `print-color-mode`, `printer-resolution`, `sides` и `multiple-document-handling` не отправляются повторно в IPP и не могут примениться дважды. Для custom paper точный размер дополнительно передаётся как `media-col`, потому что он описывает реально загруженный носитель, а не повторяет software copies/layout.

## Confirmed custom paper

Custom width/height доступен только для IPP Direct и IPP PWG, когда одновременно выполнены все условия:

- IPP вернул range-of-integer для `media-size/x-dimension` и `y-dimension` в `media-col-database`, `media-col-ready` или `media-col-default`;
- `job-creation-attributes-supported` содержит `media-col`;
- `media-col-supported` содержит `media-size`;
- выбранный backend объявляет `supportsCustomPaper = true`.

UI принимает миллиметры или дюймы, но сразу переводит значение в положительный `Long` microns с decimal arithmetic и округлением до ближайшего micron. Validator не допускает одновременный standard paper, неизвестный range, выход за min/max, размер не больше суммы user/hardware margins или небезопасные raster dimensions. Для `AUTO` orientation поля должны помещаться в обе возможные ориентации; IPP PWG проверяется с фактическим symmetric 300/600 DPI и общим `RasterDimensionLimits/RasterMemoryPolicy` budget.

`Print-Job` получает nested `media-size` с `x-dimension/y-dimension` в целых hundredths of millimetre. `media-left/top/right/bottom-margin`, `media-source` и `media-type` добавляются в collection только если каждый member присутствует в `media-col-supported`; иначе подтверждённые top-level source/type сохраняют прежний pass-through. Неизвестные members и придуманный standard media keyword не отправляются. В PWG header остаются точные numeric PageSize/raster dimensions, а `cupsPageSizeName` для custom media пуст.

## Software N-up

На development branch IPP PWG, PWG USB, PostScript Raster и PCL 5 Raster принимают уже составленный физический лист. Порядок строго фиксирован:

```text
page selection → reverse order → collated/uncollated copies → chunks of 1/2/4 → physical raster sheet → backend
```

Для 2-up книжный лист использует сетку 1×2, альбомный — 2×1; 4-up использует 2×2. `AUTO` сравнивает полезный fit книжного и альбомного листа, а опциональный поворот каждой логической страницы на 90° применяется только когда увеличивает её fit в слоте. Интервал 0–20 мм, рамки, positioning и существующие fit/fill/actual/custom scale входят в общий `NUpLayoutEngine` result.

Preview рисует первый физический лист из того же набора `NUpSheet/NUpSlot`, что потребляют encoders; отдельной preview-математики нет. Direct PDF/IPP, ESC/POS и RAW не получают составленный лист и сохраняют `supportsNUp = false`.

Только `media-source`, `media-type` и `output-bin` проходят как Job Template attributes, причём лишь если их имена были получены в `job-creation-attributes-supported`, а exact keyword прошёл повторную validation.

PWG создаётся один раз в unique file каталога `cache/ipp-pwg-spool`. Размер ограничен 512 MiB. После завершения generation известный exact file length используется в HTTP Content-Length; затем файл streaming-читается в единственный Print-Job. Spool удаляется после success, protocol/HTTP error и cancel. Оставшиеся после аварийного завершения файлы удаляются при создании application container.

После начала передачи нет retry/fallback на другой backend: executor выполняет только выбранный backend, чтобы не создать duplicate physical job.

## Legacy capabilities

IEEE-1284 `CMD` продолжает выбирать только реализованные PDF/PWG/PostScript/PCL5/ESC-POS/RAW paths. PCL XL-only не включает PCL5. Для legacy raster при неизвестной бумаге/DPI может показываться подписанный `BACKEND_DEFAULT` (A4/300), но он не переименовывается в printer-confirmed capability.

## UI и DataStore

Динамические IPP меню source/type/bin появляются у IPP Direct и IPP PWG только при полученных options. Exact raw keyword, asymmetric resolution, N-up spacing/border/auto-rotate и optional custom width/height microns сохраняются в DataStore codec; отсутствующие поля старых локальных presets получают безопасные defaults и не вызывают migration crash.

Hidden stale IPP keyword очищается при сохранении настроек backend, который не предоставляет соответствующий option. Validator повторно проверяет raw keyword, paper, resolution, color, duplex, copies и pages перед созданием job.

## Пока не реализовано

- `Create-Job` + `Send-Document` path;
- IPP PWG external CUPS/ipptool validation и physical printer verification;
- полноценный долговременный job monitor после закрытия foreground service;
- PCLm;
- automatic promotion of stored hardware evidence into selectable printer capabilities;
- per-value provenance внутри одного mixed set (например, `AUTO` и confirmed orientations).
