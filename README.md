# GCal2LN2Teams

Android-приложение, которое синхронизирует календари **Google Calendar**, **HCL Lotus Notes**
(через Domino REST API) и **Microsoft Teams / Outlook** (через MS Graph). Работает целиком на
телефоне: копирует встречи между системами, переносит правки, переносы и удаления, а о спорных
случаях (удалить оригинал? это одна встреча? правка встречи с участниками?) спрашивает.

- **Установка:** скачайте APK со страницы [Releases](https://github.com/gudim/GCal2LN2Teams/releases)
  и следуйте [mobile/INSTALL.md](mobile/INSTALL.md). Приложение само сообщает о новых версиях.
- **Сборка и устройство кода:** [mobile/README.md](mobile/README.md).
- **Термины:** [GLOSSARY.md](GLOSSARY.md).
- **Решения и их причины:** [docs/adr/](docs/adr/).
- **Эталонные сценарии движка:** [spec/README.md](spec/README.md).

Раньше здесь же была настольная Python-версия с веб-интерфейсом; она удалена, её история
осталась в git.

Лицензия — [LICENSE](LICENSE).
