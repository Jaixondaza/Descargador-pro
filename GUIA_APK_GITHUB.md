# Guía paso a paso: generar tu APK en GitHub (gratis, sin Android Studio)

Tiempo total: ~15 minutos + 5-12 minutos de espera de la compilación.
Lo más fácil es hacerlo desde un **computador**. Desde el celular se puede, pero subir las carpetas es incómodo.

---

## PASO 0 — Prepara los archivos (ya viene corregido)
Usa el ZIP **DescargadorPro_GitHub_listo.zip** (el nuevo, no el primero).
1. Descárgalo y **extráelo / descomprímelo**.
2. Abre la carpeta extraída y verifica que ves esto **directamente**, sin otra carpeta en medio:
   - `settings.gradle.kts`
   - `build.gradle.kts`
   - carpeta `app`
   - carpeta `.github`  ← **clave**: sin esta carpeta, GitHub no compila nada
   - carpeta `gradle`

> ⚠️ Error #1 más común: subir la carpeta completa en vez de su contenido.
> Si dentro de tu repo queda `descargador_pro/app/...`, la compilación no aparece. Tiene que quedar `app/...` en la raíz del repo.

## PASO 1 — Crea tu cuenta y el repositorio
1. Entra a https://github.com y crea tu cuenta gratuita (o inicia sesión).
2. Arriba a la derecha: **+ → New repository**.
3. Nombre: `descargador-pro`
4. Marca **Private** (privado, es tu app personal).
5. **No** marques "Add a README" ni nada más.
6. Botón **Create repository**.

## PASO 2 — Sube el proyecto
1. En la pantalla del repo nuevo, haz clic en **uploading an existing file**.
2. Desde tu explorador, **selecciona TODO el contenido** de la carpeta extraída del Paso 0 (todos los archivos y carpetas, incluida `.github`) y arrástralo a la ventana de GitHub.
   - En Windows: si no ves `.github`, activa Vista → Mostrar → Elementos ocultos… en realidad `.github` no es oculta en Windows, se ve normal. En Mac sí puede ocultarse: pulsa `Cmd + Shift + .` en Finder para mostrarla.
3. Espera a que todos los archivos terminen de subir (barra al 100%).
4. Abajo: **Commit changes**.

**Verificación rápida:** en la raíz del repo debes ver `app`, `.github`, `settings.gradle.kts`. Si ves una sola carpeta con el nombre del proyecto, bórralo y repite el paso: subiste la carpeta, no el contenido.

## PASO 3 — Lanza la compilación
1. Pestaña **Actions** (arriba, en tu repo).
   - Si GitHub pregunta, acepta habilitar los workflows ("I understand my workflows, go ahead and enable them").
2. A la izquierda: **Construir APK**.
3. A la derecha: **Run workflow** → deja la rama `main` → **Run workflow** (verde).
4. Actualiza la página tras unos segundos: aparecerá una ejecución en amarillo (trabajando).

## PASO 4 — Espera y revisa el resultado
- Espera **5 a 12 minutos**.
- ✅ **Palomita verde** = APK listo, ve al Paso 5.
- ❌ **X roja** = algo falló. Haz clic en la ejecución → clic en el paso en rojo (normalmente "Construir APK") → copia el texto del error (las últimas líneas en rojo) y **pégamelo en el chat**. Te corrijo el archivo exacto y vuelves a subir solo ese archivo (en el repo: clic en el archivo → lápiz ✏️ → pegar el contenido nuevo → Commit).

> Dato ya corregido en esta versión: la librería original del editor (`com.arthenica:ffmpeg-kit-full:6.0-2`) fue retirada de Maven Central en abril de 2025 y rompía cualquier compilación. Ya va reemplazada por el fork mantenido `io.github.maitrungduc1410:ffmpeg-kit-full:8.1.2`, con la misma API.

## PASO 5 — Descarga tu APK
1. Entra a la ejecución con ✅ → baja hasta **Artifacts**.
2. Descarga **DescargadorPro-APK** (es un ZIP).
3. Extráelo: dentro está **`app-debug.apk`** ← ese es tu instalador.

## PASO 6 — Instálalo en tu Android
1. Pásate el `app-debug.apk` al celular (WhatsApp/Drive/USB, lo que quieras).
2. Tócalo para instalar. Android pedirá permiso de **"instalar apps desconocidas"** para la app desde donde lo abres (Archivos/Chrome): actívalo y vuelve.
3. **Instalar** → **Abrir**.
4. Al primer uso dale unos segundos: la app inicializa el motor de descarga (yt-dlp) internamente.
5. Prueba: copia un enlace de un video **tuyo**, pégalo, **Analizar → marca la casilla → Descargar**.

Notas:
- El APK de depuración ("debug") es perfecto para uso personal; no necesitas cuenta de desarrollador ni firmarlo.
- El artefacto de GitHub caduca a los 90 días: guarda el `.apk` en tu Drive.
- La app pesará bastante al instalarse (trae el motor de descarga y el editor de video adentro, sin servidor). Es normal.
