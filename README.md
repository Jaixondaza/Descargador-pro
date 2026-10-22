# Descargador Pro — tu app Android todo en uno

App propia para Android que junta en un solo sitio:

1. **Descargar videos** de YouTube, TikTok, Facebook y cientos de sitios más (motor **yt-dlp**, el mismo que usan las herramientas serias de escritorio).
2. **Elegir la calidad real**: Mejor disponible, **4K 2160p, 1440p, 1080p, 720p**, 480p o **solo audio MP3**.
3. **Editor integrado** para mejorar y adaptar tus videos antes de subirlos: nitidez, color, menos ruido, audio normalizado, y formatos **9:16 (TikTok/Reels/Shorts), 16:9 (YouTube) y 1:1**.
4. **Recibir enlaces compartidos**: desde TikTok/YouTube/Facebook toca *Compartir → Descargador Pro* y el enlace llega pegado.

> ⚠️ **La verdad sobre la calidad:** 4K/1080p solo existen si el video original fue subido en esa calidad. Si el original es 720p, la app entrega 720p (el Editor puede reescalar a 1080p, pero eso no crea detalle nuevo).
>
> ⚠️ **La verdad sobre derechos de autor:** ninguna mejora de imagen, recorte o cambio de formato elimina los derechos de autor de un video de otra persona, y las plataformas detectan contenido reutilizado aunque lo edites. La forma segura de re-subir sin reclamos es: **tus propios videos, material con licencia/permiso, o contenido transformativo real** (tu voz y comentario, análisis, reacción, contexto nuevo) acreditando la fuente. Por eso la app te pide confirmar que el video es tuyo o tienes permiso. Úsala así y te ahorras strikes y canales cerrados.

---

## Cómo obtener tu APK (elige una)

### Opción A — Sin instalar nada en PC (recomendada): GitHub
1. Crea un repo en [github.com](https://github.com) (puede ser privado) y sube **toda esta carpeta**.
2. En el repo: pestaña **Actions → Construir APK → Run workflow**.
3. En ~5-10 min descarga el artefacto **DescargadorPro-APK**, pásalo a tu celular e instálalo (permite «instalar apps desconocidas» para tu navegador/archivos).

### Opción B — Android Studio en tu PC
1. Abre esta carpeta en Android Studio.
2. Espera a que sincronice Gradle (descarga las dependencias solo la primera vez).
   - Si falta el `gradle-wrapper.jar`, en una terminal dentro de la carpeta ejecuta: `gradle wrapper --gradle-version 8.7`
3. **Build → Build App Bundle(s)/APK(s) → Build APK(s)** y pásalo a tu celular.

> En esta sesión **no se compiló el APK aquí** porque este entorno no tiene Java ni el SDK de Android; el proyecto quedó validado en estructura y XML. La primera compilación real es la de la Opción A o B — si Gradle reporta un error de versión de alguna librería, se ajusta esa línea en `app/build.gradle.kts` (las versiones están comentadas ahí).

## Cómo se usa
1. Copia el enlace del video (o compártelo hacia la app).
2. Pégalo → **Analizar** (verás título y las alturas reales disponibles).
3. Elige calidad → marca la casilla de derechos → **Descargar**.
4. El video queda en la carpeta **DescargadorPro** de la app («Mis videos»).
5. Para mejorarlo/adaptarlo: **Abrir Editor** → elige el video → preset → **Procesar** → **Compartir** para subirlo donde quieras.

## Limitaciones honestas
- Videos **privados, con restricción de edad o que exigen iniciar sesión** (mucho Facebook) pueden fallar: yt-dlp necesitaría tus cookies de sesión, eso no va incluido.
- TikTok: yt-dlp suele obtener la versión sin marca de agua cuando el sitio la ofrece; no está garantizado en todos los videos.
- La app es pesada al instalar (trae yt-dlp + FFmpeg dentro, ~80-120 MB). Es el precio de que todo funcione **en el celular, sin servidor**.
- No es para Play Store: Google no acepta apps de descarga de YouTube. Es **tu APK personal**, se instala directo.

## Estructura
```
app/src/main/java/com/descargadorpro/app/
  MainActivity.kt    -> pegar / analizar / descargar (yt-dlp, selector de formato)
  EditorActivity.kt  -> presets FFmpeg de mejora y de formato
.github/workflows/build-apk.yml -> compila el APK en GitHub gratis
```

## Ideas para la v2
- Cola de descargas con notificación y pausa/reanudar
- Descargar una playlist completa o todos los videos de un perfil propio
- Añadir tu intro, tu logo y subtítulos automáticos en el Editor
- Programar: descargar → mejorar → dejar listo para subir, en un solo toque
