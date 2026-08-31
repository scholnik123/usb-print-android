# Backend matrix

Обозначения: `YES` — реализовано; `PARTIAL` — реализовано с указанным ограничением; `NO` — не реализовано; `N/A` — неприменимо; `EXPERIMENTAL` — не включено в безопасный Auto path. Ни один `YES` не означает hardware verification.

| Возможность | PDF Direct | IPP Direct | IPP PWG | PWG USB | PostScript | PCL5 | PCLm | ESC/POS | RAW |
|---|---|---|---|---|---|---|---|---|---|
| PDF input | YES | YES | NO | YES | YES | YES | NO | YES | PARTIAL |
| Image input | NO | NO | NO | YES | YES | YES | NO | YES | NO |
| TXT input | NO | NO | NO | YES | YES | YES | NO | YES | NO |
| Copies | NO | YES | NO | YES | YES | YES | NO | YES | NO |
| Range | NO | PARTIAL | NO | YES | YES | YES | NO | PARTIAL | NO |
| Odd/even | NO | PARTIAL | NO | YES | YES | YES | NO | PARTIAL | NO |
| Reverse | NO | NO | NO | YES | YES | YES | NO | NO | NO |
| Collate | NO | PARTIAL | NO | YES | YES | YES | NO | NO | NO |
| Paper | NO | YES | NO | YES | YES | PARTIAL | NO | NO | NO |
| Custom paper | NO | NO | NO | NO | NO | NO | NO | NO | NO |
| Orientation | NO | YES | NO | YES | YES | YES | NO | NO | NO |
| Margins | NO | NO | NO | YES | YES | YES | NO | NO | NO |
| Scale | NO | NO | NO | YES | YES | YES | NO | NO | NO |
| Position | NO | NO | NO | YES | YES | YES | NO | NO | NO |
| Color | NO | YES | NO | YES | YES | NO | NO | NO | N/A |
| Grayscale | NO | YES | NO | YES | YES | YES | NO | YES | N/A |
| Black only | NO | YES | NO | YES | YES | YES | NO | YES | N/A |
| Duplex | NO | YES | NO | YES | YES | YES | NO | NO | N/A |
| Resolution | NO | YES | NO | YES | YES | YES | NO | PARTIAL | N/A |
| N-up | NO | NO | NO | NO | NO | NO | NO | NO | NO |
| Media source | NO | YES | NO | NO | NO | NO | NO | NO | NO |
| Media type | NO | YES | NO | NO | NO | NO | NO | NO | NO |
| Output bin | NO | YES | NO | NO | NO | NO | NO | NO | NO |
| Quality | NO | YES | NO | PARTIAL | PARTIAL | PARTIAL | NO | NO | N/A |
| Printer status | PARTIAL | YES | NO | PARTIAL | PARTIAL | PARTIAL | NO | PARTIAL | PARTIAL |
| Job status | NO | PARTIAL | NO | NO | NO | NO | NO | NO | NO |
| Cancel job | local transfer only | PARTIAL | NO | local transfer only | local transfer only | local transfer only | NO | local transfer only | local transfer only |
| Hardware verified | NO | NO | NO | NO | NO | NO | NO | NO | NO |

Пояснения:

- PDF Direct — raw USB passthrough только при settings, не требующих изменения PDF.
- IPP Direct — HTTP/1.1 + binary IPP `Print-Job` поверх protocol-4 USB interface. Range/odd/even зависят от `page-ranges-supported`; status/cancel — от job reference и `operations-supported`.
- IPP PWG — ещё не реализован: наличие `image/pwg-raster` в IPP response само по себе не включает backend.
- PWG USB — complete stream покрыт внутренним golden inspector, но внешняя CUPS validation и hardware test отсутствуют.
- PostScript — Level 2 raster subset, не полный PostScript driver.
- PCL5 — только явно заявленный PCL 5-compatible CMD, не PCL XL-only; media codes ограничены.
- PCLm — не реализован и не участвует в Auto.
- RAW — только заранее подготовленный PCL/PS для совпадающего CMD; приложение не преобразует payload.

## Hardware confidence

Hardware verified printers: **0**. В проекте нет выдуманного списка «поддерживаемых брендов». Для появления подтверждённой строки нужны конкретная модель, Android/OTG environment, точные settings/backend/encoder version и явная визуальная оценка бумажного результата.
