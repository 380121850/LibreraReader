---
layout: page
---

# Как добавить новую тему FAQ

Если вы хотите добавить новую тему часто задаваемых вопросов, создайте папку **[имя-дефис-разделитель]** с одним файлом en-us **index.md** в [здесь](https://github.com/foobnix/LibreraReader/tree/master/docs/faq).

Сценарий автоматически обновит оглавление FAQ и добавит файлы локали для всех поддерживаемых языков.

Формат заголовка файла для **index.md**:

```
---
layout: page
---

# Topic Name from Here Goes to the FAQ Page
```

Вы можете проиллюстрировать свое обсуждение с помощью картинок (JPEG). Все файлы изображений, относящиеся к этой теме, должны быть помещены в папку рядом с **index.md**

```
* Image description 1
* Image description 2
* Image description 3

||||
|-|-|-|
|![](../dictionaries-translate-text-online-and-offline/1.webp)|![](../dictionaries-translate-text-online-and-offline/2.webp)|![](../dictionaries-translate-text-online-and-offline/3.webp)|
```
