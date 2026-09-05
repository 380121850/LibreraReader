---
layout: page
---

# Como adicionar um novo tópico de FAQ

Se você deseja adicionar um novo tópico de FAQ, crie uma pasta **[qualquer-hífen-nome-separado]** com um único arquivo en-us **index.md** em [aqui](https : //github.com/foobnix/LibreraReader/tree/master/docs/faq).

O script atualizará automaticamente o índice das perguntas frequentes e adicionará arquivos de localidade para todos os idiomas suportados.

Formato do cabeçalho do arquivo para **index.md**:

```
---
layout: page
---

# Topic Name from Here Goes to the FAQ Page
```

Você pode ilustrar sua discussão com imagens (JPEG). Todos os arquivos de imagem relacionados a este tópico devem ser colocados na pasta, ao lado de **index.md**

```
* Image description 1
* Image description 2
* Image description 3

||||
|-|-|-|
|![](../dictionaries-translate-text-online-and-offline/1.webp)|![](../dictionaries-translate-text-online-and-offline/2.webp)|![](../dictionaries-translate-text-online-and-offline/3.webp)|
```
