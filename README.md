# Евразия Research

Android WebView-инструмент для динамического исследования `evrasia.rest`.

## v1
- открывает оригинальный сайт;
- сохраняет WebView resource requests;
- внедряет hooks для `fetch` и `XMLHttpRequest`;
- сохраняет request body и text response body для XHR/fetch;
- показывает счётчик записей;
- экспортирует трассу в `evrasia-research-*.json` через Android document picker.

Авторизуйтесь на сайте внутри приложения, пройдите интересующие разделы личного кабинета и экспортируйте JSON.
