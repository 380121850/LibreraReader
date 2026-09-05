{% assign lang = '' %}
{% assign full = 'English' %}
{% assign parent = '/' %}
{% assign is_home = false %}

{% assign bare_url = page.url | remove_first: '/howread' %}
{% if bare_url == 'index.html' %}{% assign bare_url = '/index.html' %}{% endif %}
{% if bare_url == '' or bare_url == 'howread/' %}{% assign bare_url = '/' %}{% endif %}

{% if page.url contains 'ar.html' %}{% assign lang = 'ar' %}{% assign full = 'العربية' %}{% endif %}
{% if page.url contains 'de.html' %}{% assign lang = 'de' %}{% assign full = 'Deutsch' %}{% endif %}
{% if page.url contains 'es.html' %}{% assign lang = 'es' %}{% assign full = 'Español' %}{% endif %}
{% if page.url contains 'fr.html' %}{% assign lang = 'fr' %}{% assign full = 'Français' %}{% endif %}
{% if page.url contains 'it.html' %}{% assign lang = 'it' %}{% assign full = 'Italiano' %}{% endif %}
{% if page.url contains 'uk.html' %}{% assign lang = 'uk' %}{% assign full = 'Українська' %}{% endif %}
{% if page.url contains 'zh.html' %}{% assign lang = 'zh' %}{% assign full = '中文' %}{% endif %}
{% if page.url contains 'pt.html' %}{% assign lang = 'pt' %}{% assign full = 'Portugal' %}{% endif %}
{% if bare_url == '/en.html' %}{% assign lang = 'en' %}{% assign full = 'English' %}{% endif %}

{% comment %} Chinese is the primary language: the root homepage (/index.html) is Chinese {% endcomment %}
{% if bare_url == '/' or bare_url == '/index.html' %}{% assign lang = 'zh' %}{% assign full = '中文' %}{% assign is_home = true %}{% endif %}

{% assign mylast = page.dir | split: "/" | last | append: "/" %}
{% assign parent = page.dir | remove: mylast %}
