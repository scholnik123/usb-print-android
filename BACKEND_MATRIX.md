# Backend matrix

Обозначения: `YES` — реализовано; `PARTIAL` — реализовано с указанным ограничением; `NO` — не реализовано; `N/A` — неприменимо; `EXPERIMENTAL` — не включено в безопасный Auto path. Ни один `YES` не означает hardware verification.

| Возможность | PDF Direct | IPP Direct | IPP PWG | PWG USB | PostScript | PCL5 | PCLm | ESC/POS | RAW |
|---|---|---|---|---|---|---|---|---|---|
| PDF input | YES | YES | YES | YES | YES | YES | NO | YES | PARTIAL |
| Image input | NO | NO | YES | YES | YES | YES | NO | YES | NO |
| TXT input | NO | NO | YES | YES | YES | YES | NO | YES | NO |
| Copies | NO | YES | YES | YES | YES | YES | NO | YES | NO |
| Range | NO | PARTIAL | YES | YES | YES | YES | NO | PARTIAL | NO |
| Odd/even | NO | PARTIAL | YES | YES | YES | YES | NO | PARTIAL | NO |
| Reverse | NO | NO | YES | YES | YES | YES | NO | NO | NO |
| Collate | NO | PARTIAL | YES | YES | YES | YES | NO | NO | NO |
| Paper | NO | YES | YES | YES | YES | PARTIAL | NO | NO | NO |
| Custom paper | NO | YES | YES | NO | NO | NO | NO | NO | NO |
| Orientation | NO | YES | YES | YES | YES | YES | NO | NO | NO |
| Margins | NO | NO | YES | YES | YES | YES | NO | NO | NO |
| Scale | NO | NO | YES | YES | YES | YES | NO | NO | NO |
| Position | NO | NO | YES | YES | YES | YES | NO | NO | NO |
| Color | NO | YES | PARTIAL | YES | YES | NO | NO | NO | N/A |
| Grayscale | NO | YES | PARTIAL | YES | YES | YES | NO | YES | N/A |
| Black only | NO | YES | PARTIAL | YES | YES | YES | NO | YES | N/A |
| Duplex | NO | YES | YES | YES | YES | YES | NO | NO | N/A |
| Resolution | NO | YES | PARTIAL | YES | YES | YES | NO | PARTIAL | N/A |
| N-up | NO | NO | YES | YES | YES | YES | NO | NO | NO |
| Media source | NO | YES | YES | NO | NO | NO | NO | NO | NO |
| Media type | NO | YES | YES | NO | NO | NO | NO | NO | NO |
| Output bin | NO | YES | YES | NO | NO | NO | NO | NO | NO |
| Quality | NO | YES | PARTIAL | PARTIAL | PARTIAL | PARTIAL | NO | NO | N/A |
| Printer status | PARTIAL | YES | YES | PARTIAL | PARTIAL | PARTIAL | NO | PARTIAL | PARTIAL |
| Job status | NO | PARTIAL | PARTIAL | NO | NO | NO | NO | NO | NO |
| Cancel job | local transfer only | PARTIAL | PARTIAL | local transfer only | local transfer only | local transfer only | NO | local transfer only | local transfer only |
| Hardware verified | NO | NO | NO | NO | NO | NO | NO | NO | NO |

Пояснения:

- PDF Direct — raw USB passthrough только при settings, не требующих изменения PDF.
- IPP Direct — HTTP/1.1 + binary IPP `Print-Job` поверх protocol-4 USB interface. Range/odd/even зависят от `page-ranges-supported`; status/cancel — от job reference и `operations-supported`.
- IPP PWG — development backend: локально кодирует physical pages и отправляет один exact-length `image/pwg-raster` Print-Job. Auto требует подтверждённые Print-Job, MIME, совместимые 300/600 DPI и один из реально кодируемых типов `black_1`, `sgray_8`, `srgb_8`. Color/resolution отмечены PARTIAL из-за этого намеренно узкого encoder subset.
- IPP PWG spool ограничен 512 MiB, хранится только в app cache и удаляется после success/error/cancel; copies/page ranges не дублируются IPP attributes.
- N-up `YES` означает software composition 1/2/4 до encoder: диапазон/порядок/копии → физические листы, с интервалом, рамками и автоповоротом. Preview использует ту же layout-модель. Это не означает hardware verification.
- Custom paper `YES` означает только реализованный условный path: UI появляется при подтверждённом IPP range и одновременно reported writable `media-col` + `media-size`. Размер хранится в microns; IPP получает exact `x-dimension/y-dimension`, а IPP PWG также проверяет raster budget. Неизвестный range не получает fallback и не означает hardware verification.
- PWG USB — complete stream покрыт внутренним golden inspector, но внешняя CUPS validation и hardware test отсутствуют.
- PostScript — Level 2 raster subset, не полный PostScript driver.
- PCL5 — только явно заявленный PCL 5-compatible CMD, не PCL XL-only; media codes ограничены.
- PCLm — не реализован и не участвует в Auto.
- RAW — только заранее подготовленный PCL/PS для совпадающего CMD; приложение не преобразует payload.

## Hardware confidence

Hardware verified printers: **0**. В проекте нет выдуманного списка «поддерживаемых брендов». Для появления подтверждённой строки нужны конкретная модель, Android/OTG environment, точные settings/backend/encoder version и явная визуальная оценка бумажного результата.
