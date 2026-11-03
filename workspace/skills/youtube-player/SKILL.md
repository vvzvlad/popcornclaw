# YouTube на приставке

На приставке стоит YouTube-плеер — SmartTube (форк Smart YouTube TV) под пакетом `com.google.android.youtube.tv`. Открывает любую ссылку youtube.com/youtu.be.

## Открыть видео по ссылке

```sh
am start -W -n com.google.android.youtube.tv/com.liskovsoft.amazonsmartyoutubetv.LauncherBridgeActivity -a android.intent.action.VIEW -d "https://www.youtube.com/watch?v=<ID>"
```

- Стандартная activity ShellActivity у пакета НЕ существует — ошибка «Activity class ... does not exist». Правильная точка входа — `LauncherBridgeActivity` (находится через `cmd package query-activities -a android.intent.action.VIEW -d <url>`).
- Работает и с короткими ссылками `https://youtu.be/<ID>`.
- Проверка запуска: `am start -W` вернёт `Complete`. Смотреть, что играет, — `dumpsys activity top | grep -i youtube`.
- Не путать с mpv/TorrServer (сервер tv) — это путь для фильмов/сериалов из торрентов; YouTube-видео включает через SmartTube.

## Если плеер не открыт
- Список пакетов с плеерами: `pm list packages | grep -i -E "youtube|yt"`.
- Найти activity под интент: `cmd package query-activities -a android.intent.action.VIEW -d <url>`.

## Качество видео (4K)
- Настройки SmartTube лежат не в shared_prefs, а в файлах `/data/data/org.smarttube.stable/files/app_prefs/` (video_player_data и др.) — формат `%OB%`-разделённый, без имён ключей.
- В video_player_data сохранённое качество видно как `null,vp9,3840,2160` — то есть 2160p (4K) запомнено. По умолчанию SmartTube играет в максимальном качестве, менять ничего не нужно.
- Проверка, что играет: `dumpsys activity top | grep PlaybackActivity`.
