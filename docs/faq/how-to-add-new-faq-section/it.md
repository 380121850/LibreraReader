---
layout: page
---

# Come aggiungere un nuovo argomento FAQ

Se desideri aggiungere un nuovo argomento delle domande frequenti, crea una cartella **[nome-separato-da-trattino]** con un singolo file en-us **index.md** in [qui](https ://github.com/foobnix/LibreraReader/tree/master/docs/faq).

Lo script aggiornerà automaticamente il sommario delle FAQ e aggiungerà i file delle impostazioni locali per tutte le lingue supportate.

Formato dell'intestazione del file per **index.md**:

```
---
layout: page
---

# Topic Name from Here Goes to the FAQ Page
```

Puoi illustrare la tua discussione con immagini (JPEG). Tutti i file di immagini relativi a questo argomento devono essere inseriti nella cartella, accanto a **index.md**

```
* Image description 1
* Image description 2
* Image description 3

||||
|-|-|-|
|![](../dictionaries-translate-text-online-and-offline/1.webp)|![](../dictionaries-translate-text-online-and-offline/2.webp)|![](../dictionaries-translate-text-online-and-offline/3.webp)|
```
