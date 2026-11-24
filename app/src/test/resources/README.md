# IEC 61937 test data / Тестовые данные IEC 61937

## English

The fixtures of the `TrueHdMatPacker` and `DtsHdIecPacker` packers are ported from
[edde746/plezy](https://github.com/edde746/plezy) (commit `3faa1227`, `android/app/src/test/resources/`, GPL-3.0)
together with the tests and are byte-for-byte identical to the original. The references (`*_iec61937_golden.bin`) are the output of the FFmpeg
`spdif` muxer, the tests compare the packing with it byte for byte.

| File | Source |
| :--- | :--- |
| `truehd_access_units.bin`, `truehd_iec61937_golden.bin` | `surround_5_1_truehd.mka` (plezy test assets, `android/app/src/androidTest/assets/ffmpeg/`): `ffmpeg -i surround_5_1_truehd.mka -c:a copy -f truehd …` and `-f spdif …` |
| `truehd_441_access_units.bin` | TrueHD blocks of the same size declaring the 44.1 kHz family rate: checks that the packer refuses the carrier |
| `dtshd_ma_access_units.bin`, `dtshd_ma_iec61937_golden.bin` | Synthesized by `dtshd_fixture_generator.py` (a DTS core from FFmpeg `dca` sines + hand-assembled EXSS), commands in the script header |

FFmpeg cannot encode DTS-HD MA, so the HD part is assembled by a script: the packer looks only at the sync words and
the EXSS sizes, so this is enough to verify the IEC 61937 format.

## Русский

Фикстуры упаковщиков `TrueHdMatPacker` и `DtsHdIecPacker` перенесены из
[edde746/plezy](https://github.com/edde746/plezy) (коммит `3faa1227`, `android/app/src/test/resources/`, GPL-3.0)
вместе с тестами и побайтно совпадают с оригиналом. Эталоны (`*_iec61937_golden.bin`) — вывод мультиплексора
`spdif` из FFmpeg, тесты сравнивают с ним упаковку байт в байт.

| Файл | Откуда |
| :--- | :--- |
| `truehd_access_units.bin`, `truehd_iec61937_golden.bin` | `surround_5_1_truehd.mka` (тестовые ассеты plezy, `android/app/src/androidTest/assets/ffmpeg/`): `ffmpeg -i surround_5_1_truehd.mka -c:a copy -f truehd …` и `-f spdif …` |
| `truehd_441_access_units.bin` | Блоки TrueHD того же размера, объявляющие частоту семейства 44.1 кГц: проверка, что упаковщик отказывается от несущей |
| `dtshd_ma_access_units.bin`, `dtshd_ma_iec61937_golden.bin` | Синтезированы `dtshd_fixture_generator.py` (ядро DTS из синусов FFmpeg `dca` + собранные вручную EXSS), команды в шапке скрипта |

FFmpeg не умеет кодировать DTS-HD MA, поэтому HD-часть собрана скриптом: упаковщик смотрит только синхрослова и
размеры EXSS, так что этого достаточно для проверки формата IEC 61937.
