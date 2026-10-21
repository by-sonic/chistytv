# Откуда данные

База `data/packages.json` собрана из реальных списков пакетов с телевизоров и отчётов пользователей.
Пакеты, назначение которых не подтверждено хотя бы одним списком с устройства, в базу не включены.

## Яндекс ТВ
- Хабр: отключение рекламы на Hartens с Яндекс ТВ — https://habr.com/ru/articles/1043666/
- 4PDA, тема Яндекс ТВ (руководство ADS_Darks и обсуждение) —
  https://4pda.to/forum/index.php?showtopic=1000256&view=findpost&p=124122067 ,
  https://4pda.to/forum/index.php?showtopic=1000256&view=findpost&p=135413290 ,
  https://4pda.to/forum/index.php?showtopic=1000256&st=25560 (чёрный экран после services.platform),
  https://4pda.to/forum/index.php?showtopic=1000256&st=25480 (com.yandex.tv.live и входы)
- 4PDA, сервисное меню CVTE — https://4pda.to/forum/index.php?showtopic=1042253&view=findpost&p=114157954

## Салют ТВ / SberBox
- 4PDA, официальный список безопасных к отключению пакетов SberBox —
  https://4pda.to/forum/index.php?showtopic=1005242&view=findpost&p=114136161
- 4PDA, Sber SBX — https://4pda.to/forum/index.php?showtopic=1044662&st=20 , &st=980
- Документация Сбера по ADB — https://developers.sber.ru/docs/ru/va/native/testing/adb

## Xiaomi, Haier, TCL, Hisense, Google TV
- CleanYourTV (MIT), списки по моделям — https://github.com/Gathin23/cleanyourtv/tree/main/examples
  и разбор ловушек — https://github.com/Gathin23/cleanyourtv/blob/main/docs/TROUBLESHOOTING.md
- Хабр, Xiaomi на Android 11 — https://habr.com/ru/articles/1044486/
- Universal Android Debloater NG (описания и риски пакетов) —
  https://github.com/Universal-Debloater-Alliance/universal-android-debloater-next-generation

## Приложения
- SmartTube — https://github.com/yuliskov/SmartTube (пакеты `org.smarttube.stable` / `org.smarttube.beta`)
- Happ для Android TV — https://www.happ.su/main/faq/android-tv.md , API импорта на ТВ —
  https://www.happ.su/main/dev-docs/android-tv-api.md
- LTvLauncher — https://github.com/leanbitlab-org/LtvLauncher
- Google platform-tools — https://developer.android.com/tools/releases/platform-tools
- Онлайн-кинотеатры — карточки приложений в RuStore TV и Google Play

Нашли ошибку или у вас другой телевизор? Пришлите вывод `adb shell pm list packages` и модель в issue —
так база и растёт.
