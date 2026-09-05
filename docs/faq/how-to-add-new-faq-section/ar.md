---
layout: page
---

# كيفية إضافة موضوع جديد للأسئلة المتكررة

إذا كنت تريد إضافة موضوع جديد للأسئلة الشائعة ، فأنشئ مجلدًا **[أيا كان-واصلة-مفصول-اسم]** بملف en-us **index.md** واحد في [هنا](https : //github.com/foobnix/LibreraReader/tree/master/docs/faq).

سيقوم البرنامج النصي تلقائيًا بتحديث جدول محتويات الأسئلة الشائعة وإضافة ملفات الإعدادات المحلية لجميع اللغات المدعومة.

تنسيق رأس الملف لـ **index.md**:

```
---
layout: page
---

# Topic Name from Here Goes to the FAQ Page
```

يمكنك توضيح مناقشتك مع الصور (JPEG). يجب وضع جميع ملفات الصور المتعلقة بهذا الموضوع في المجلد ، جنبًا إلى جنب مع **index.md**

```
* Image description 1
* Image description 2
* Image description 3

||||
|-|-|-|
|![](../dictionaries-translate-text-online-and-offline/1.webp)|![](../dictionaries-translate-text-online-and-offline/2.webp)|![](../dictionaries-translate-text-online-and-offline/3.webp)|
```
