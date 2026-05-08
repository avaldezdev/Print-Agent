# Print Agent Android — Contexto inicial del proyecto

## Objetivo
Desarrollar una app Android nativa (APK) que funciona como Print Agent local. La app corre en un dispositivo Android dentro de la red LAN, hace polling a un API REST (Wama POS), detecta trabajos de impresión pendientes y los envía automáticamente a impresoras térmicas ESC/POS conectadas en la misma red vía TCP socket (puerto 9100). Sin navegador, sin popups, sin intermediarios.

## Problema que resuelve
Los mozos de un restaurante toman pedidos desde celulares Android en un sistema web POS (Wama). Al confirmar un pedido, el sistema usa `window.print()` que abre el diálogo del navegador — esto impide la impresión directa a la impresora de cocina. Esta app elimina esa fricción.

## Flujo de operación
```
Mozo (celular) → Wama web (confirma pedido) → API genera print job (status: pending)
                                                         ↓
Impresora cocina ← TCP :9100 ← Print Agent Android ← polling GET /print-jobs?status=pending
                                                         ↓
                                                POST /{uuid}/printed (confirma impresión)
```

## API disponible (ya implementada por Wama)
- **Base URL**: `https://wama.micdepos.com/api/v1/print-jobs`
- **Auth**: Bearer token (`wmk_xxx...`) en header `Authorization`
- **Documentación completa**: ver `docs/API_REFERENCE.md`
- **Colección Postman**: ver `postman/api_collection.json`

### Endpoints principales
| Método | Ruta | Descripción |
|--------|------|-------------|
| GET | `/health` | Validar token y módulo activo |
| GET | `/?status=pending&type=kitchen` | Listar jobs pendientes |
| GET | `/{uuid}` | Detalle de un job |
| POST | `/{uuid}/printed` | Marcar como impreso (idempotente, 409 si ya fue marcado) |
| POST | `/{uuid}/failed` | Marcar como fallido (body: `{"reason": "..."}`) |

### Estructura del job (response del GET)
Cada job incluye:
- `id`: UUID del job (`job_xxx`)
- `order_id`: número de comprobante
- `type`: `kitchen` | `adicion` | `bar` | `receipt`
- `kitchen_station.printer.ip_address` + `port`: IP y puerto de la impresora destino
- `content.header`: nombre del comercio
- `content.subheader`: número de comanda
- `content.items[]`: array de `{qty, name, notes}`
- `content.meta`: mesa, mozo, hora, comanda_id
- `content.footer`: resumen
- `status`: `pending` | `printed` | `failed` | `cancelled`
- `attempt_count`: intentos fallidos

### Lógica de enrutamiento a impresora
```
if (job.kitchen_station?.printer != null) {
    // Usar IP de la estación de cocina
    sendToPrinter(ip: kitchen_station.printer.ip_address, port: kitchen_station.printer.port)
} else {
    // Fallback: mapping local por printer_target (string slug)
    sendToConfiguredPrinter(job.printer_target)
}
```

### Rate limits
- 120 requests/minuto por IP
- Polling recomendado: cada 3-5 segundos (~12-20 req/min)

### Códigos de error
| HTTP | Código | Descripción |
|------|--------|-------------|
| 401 | `missing_token` / `invalid_token` | Auth inválida |
| 403 | `module_disabled` | Módulo no activado |
| 404 | `not_found` | Job no existe |
| 409 | `already_printed` | Job ya fue impreso |
| 429 | — | Rate limit excedido |

## Stack técnico
- **Lenguaje**: Kotlin
- **Build**: Gradle (wrapper incluido, sin Android Studio — se compila con `./gradlew assembleDebug`)
- **HTTP client**: OkHttp o Ktor Client
- **Background**: ForegroundService con notificación persistente
- **Impresión**: Socket TCP directo (`java.net.Socket`) con comandos ESC/POS raw
- **UI mínima**: Configuración de token, IP por defecto, estado del servicio
- **Min SDK**: API 26 (Android 8.0)

## Estructura del proyecto
```
print-agent-android/
├── docs/
│   ├── API_REFERENCE.md          ← Documentación completa del API de Wama
│   ├── ARCHITECTURE.md           ← Diseño de la app (a completar)
│   └── ESC_POS_REFERENCE.md      ← Referencia de comandos ESC/POS (a completar)
├── postman/
│   └── api_collection.json       ← Colección Postman para testing
├── app/                          ← Código fuente Android (a crear)
└── README.md                     ← Overview del proyecto
```

## Comandos ESC/POS necesarios
La app debe generar los siguientes comandos raw para impresoras térmicas de 80mm:
- **ESC @** (0x1B 0x40): Inicializar impresora
- **ESC a n** (0x1B 0x61 n): Alineación (0=izq, 1=centro, 2=der)
- **ESC E n** (0x1B 0x45 n): Bold on/off
- **GS ! n** (0x1D 0x21 n): Tamaño de texto
- **GS V 1** (0x1D 0x56 0x01): Corte de papel
- **LF** (0x0A): Salto de línea

## Requerimientos funcionales
1. Pantalla de configuración: ingresar token API, ver estado de conexión
2. ForegroundService que haga polling cada 3-5 segundos al endpoint de jobs pendientes
3. Al detectar job pendiente: generar comandos ESC/POS → enviar por TCP a la IP/puerto del job
4. Marcar job como `printed` si fue exitoso, o `failed` con razón si falló
5. Notificación persistente mostrando estado (conectado, imprimiendo, error)
6. Manejar reconexión automática si pierde internet
7. Log local de jobs procesados (últimos 50)

## Requerimientos no funcionales
- La app NO necesita publicarse en Play Store — se instala por APK directo
- Debe funcionar en Android 8.0+ (API 26)
- Debe sobrevivir el cierre de la app (ForegroundService)
- Debe manejar correctamente el caso donde la impresora no responde (timeout 5 seg)
- Debe ser genérica: el API base URL y token son configurables, no hardcodeados

## Entorno de desarrollo
- Compilación desde terminal con Gradle wrapper (`./gradlew assembleDebug`)
- Sin Android Studio — se usa VS Code + Claude Code CLI
- Requiere: Java JDK 17 + Android SDK (command line tools)

## Fase actual
Planificación y setup del entorno de desarrollo. Antes de escribir código:
1. Verificar que Java JDK 17 está instalado (`java -version`)
2. Instalar Android SDK command line tools si no están
3. Configurar variables de entorno (ANDROID_HOME, JAVA_HOME)
4. Inicializar proyecto Gradle + Kotlin
5. Compilar un "Hello World" APK para validar el toolchain
