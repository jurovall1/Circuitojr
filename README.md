# Rutas Circuito (Android)

App de rutas tipo circuito, con diseño inspirado en Circuit.
Kotlin + Jetpack Compose + Room + osmdroid (mapa gratis, sin API key).

## Cómo abrirlo
1. Android Studio (Koala o más reciente) > File > Open > carpeta `RutasCircuito`.
2. Esperar el Gradle Sync (genera el wrapper solo) y pulsar Run.

## Excel
Columnas: `Nombre | Dirección | Comuna | Latitud | Longitud | Notas`.
Si falta latitud/longitud, la app geocodifica la dirección. Si falta la comuna, la obtiene de la ubicación. La búsqueda usa "dirección, comuna, Chile". Menú > "Completar comuna y coordenadas" rellena lo que falte.
Al exportar se añaden `Distancia (km)` y `Tiempo (min)` por tramo (se ignoran al re-importar).
Al importar, el archivo queda vinculado y cada cambio se guarda en él.
