---
layout: page
---

# Cómo agregar un nuevo tema de preguntas frecuentes

Si desea agregar un nuevo tema de preguntas frecuentes, cree una carpeta **[cualquier nombre-separado por guión]** con un solo archivo en-us **index.md** en [aquí](https : //github.com/foobnix/LibreraReader/tree/master/docs/faq).

El script actualizará automáticamente la tabla de contenido de preguntas frecuentes y agregará archivos de configuración regional para todos los idiomas admitidos.

Formato de encabezado de archivo para **index.md**:

```
---
layout: page
---

# Topic Name from Here Goes to the FAQ Page
```

Puede ilustrar su discusión con imágenes (JPEG). Todos los archivos de imagen relacionados con este tema deben colocarse en la carpeta, junto con **index.md**

```
* Image description 1
* Image description 2
* Image description 3

||||
|-|-|-|
|![](../dictionaries-translate-text-online-and-offline/1.webp)|![](../dictionaries-translate-text-online-and-offline/2.webp)|![](../dictionaries-translate-text-online-and-offline/3.webp)|
```
