# API Print Jobs (v1)

API REST que expone trabajos de impresion pendientes para que una app
movil (Android, iOS, escritorio) los consuma e imprima en una termica
ESC/POS de la LAN sin pasar por el dialogo `window.print()` del browser.

- **Base URL**: `https://wama.micdepos.com/api/v1/print-jobs`
- **Auth**: Bearer token por business
- **Modulo requerido**: `print_jobs_api` activo en el comercio
- **Estado**: estable dentro de v1 (cualquier cambio breaking pasa a v2)

## Indice

1. [Activacion del modulo](#activacion)
2. [Generacion de tokens](#tokens)
3. [Autenticacion](#autenticacion)
4. [Endpoints](#endpoints)
5. [Modelo de datos](#modelo)
6. [Codigos de error](#errores)
7. [Rate limit](#rate-limit)
8. [Recetas de uso (curl)](#recetas)
9. [Ejemplo flujo Android](#android)

---

## <a name="activacion"></a>1. Activacion del modulo

El admin del comercio activa el modulo desde:

```
Configuracion empresa -> Modulos -> "API Impresion Movil"
```

Sin este checkbox activo, todos los endpoints devuelven `403 module_disabled`.

## <a name="tokens"></a>2. Generacion de tokens

Una vez activo el modulo, aparece en sidebar:

```
Configuracion -> Tokens API
```

Permite crear/revocar tokens por dispositivo. **El token en claro se
muestra UNA SOLA VEZ al crear**. Si se pierde, hay que crear uno nuevo.

Formato del token: `wmk_` + 32 caracteres hexadecimales, ejemplo:

```
wmk_3a8b9c1d2e4f5067a8b9c0d1e2f3a4b5
```

## <a name="autenticacion"></a>3. Autenticacion

Pasar el token en el header `Authorization`:

```
Authorization: Bearer wmk_3a8b9c1d2e4f5067a8b9c0d1e2f3a4b5
```

Como fallback se acepta tambien `X-Api-Key`:

```
X-Api-Key: wmk_3a8b9c1d2e4f5067a8b9c0d1e2f3a4b5
```

Header opcional para identificar el dispositivo en los logs:

```
X-Device-Hint: tablet-cocina-01
```

## <a name="endpoints"></a>4. Endpoints

### 4.1 GET `/api/v1/print-jobs`

Lista de jobs filtrables.

| Query param   | Tipo   | Default   | Descripcion |
|---------------|--------|-----------|-------------|
| `status`      | string | `pending` | `pending` \| `printed` \| `failed` \| `cancelled` |
| `type`        | string | (todos)   | `kitchen` \| `adicion` \| `bar` \| `receipt` |
| `location_id` | int    | (todas)   | Filtrar por sucursal |
| `limit`       | int    | `50`      | 1..200 |
| `since`       | ISO8601| (sin filtro) | Solo jobs creados despues de esta fecha |

**Respuesta 200**:

```json
{
  "jobs": [
    {
      "id": "job_3a8b9c1d-e4f5-6789-0abc-def012345678",
      "order_id": "COMP000004",
      "comanda_id": 142,
      "created_at": "2026-05-07T14:30:00+00:00",
      "type": "kitchen",
      "printer_target": "cocina_principal",
      "kitchen_station": {
        "id": 3,
        "name": "Cocina Principal",
        "printer": {
          "id": 2,
          "name": "Termica Cocina",
          "connection_type": "network",
          "ip_address": "192.168.1.50",
          "port": "9100",
          "path": null,
          "capability_profile": "default"
        }
      },
      "status": "pending",
      "attempt_count": 0,
      "content": {
        "header": "MERCADO ALDO",
        "subheader": "COMANDA #1",
        "items": [
          { "qty": 1, "name": "Hamburguesa clasica", "notes": "sin cebolla" },
          { "qty": 2, "name": "Papas fritas", "notes": "" }
        ],
        "meta": {
          "mesa": "5",
          "mozo": "Juan Perez",
          "hora": "14:30",
          "comanda_id": 142,
          "numero": 1,
          "es_adicion": false,
          "kitchen_station": {
            "id": 3,
            "name": "Cocina Principal",
            "printer": {
              "id": 2,
              "name": "Termica Cocina",
              "connection_type": "network",
              "ip_address": "192.168.1.50",
              "port": "9100",
              "path": null,
              "capability_profile": "default"
            }
          }
        },
        "footer": "Mesa 5 - Mozo: Juan Perez - 14:30"
      },
      "printed_at": null
    }
  ],
  "meta": {
    "count": 1,
    "limit": 50,
    "status": "pending"
  }
}
```

> **kitchen_station** se llena desde el modulo de **Estaciones de cocina** de WAMA
> (`/modules/kitchen-stations`). Cada estacion apunta a un `printer` con su
> `connection_type`, `ip_address`, `port`, `path`, y `capability_profile`.
> La app movil usa este bloque para decidir a qué impresora enviar el ticket.
>
> Si el job no esta asociado a una estacion, `kitchen_station` es `null` y la
> app deberia caer al `printer_target` legacy (string identificador, ej:
> `"cocina_principal"`).

### 4.2 GET `/api/v1/print-jobs/{uuid}`

Detalle de un job individual.

- **200**: detalle (mismo schema que arriba, sin envoltorio `jobs[]`)
- **404**: `not_found`

### 4.3 POST `/api/v1/print-jobs/{uuid}/printed`

Marca el job como impreso. Llamar **inmediatamente despues** de imprimir
con exito en la termica.

**Idempotente**: la primera llamada devuelve 200, las siguientes 409.

- **200**: marca exitosa, devuelve `printed_at`
- **409 already_printed**: ya estaba impreso (otra app/dispositivo se adelanto)
- **404 not_found**: no existe / no es del business del token

### 4.4 POST `/api/v1/print-jobs/{uuid}/failed`

Marca el job como fallido (incrementa `attempt_count`). Body opcional:

```json
{ "reason": "Printer offline" }
```

- **200**: marca exitosa
- **409 already_printed**: no se puede marcar como failed un job ya impreso
- **404 not_found**

### 4.5 GET `/api/v1/print-jobs/health`

Healthcheck rapido. Confirma que el token es valido y el modulo esta
activo. La app Android deberia llamarlo al arrancar.

**200**:

```json
{
  "ok": true,
  "business_id": 7,
  "token_prefix": "wmk_3a8b9c1d",
  "time": "2026-05-07T14:30:00+00:00"
}
```

## <a name="modelo"></a>5. Modelo de datos

```jsonc
{
  "id":              "job_<uuid>",         // string, identificador externo
  "order_id":        "COMP000004",          // invoice_no de la transaccion (puede ser null)
  "comanda_id":      142,                   // FK a comanda interna (puede ser null)
  "created_at":      "ISO8601",             // cuando se creo el job
  "type":            "kitchen|adicion|bar|receipt",
  "printer_target":  "cocina_principal",    // slug legible (legacy, fallback)
  "kitchen_station": {                       // OBJETO o null — la fuente de verdad para enrutar el print
    "id":   3,
    "name": "Cocina Principal",
    "printer": {                             // o null si la station no tiene printer asignada
      "id":                 2,
      "name":               "Termica Cocina",
      "connection_type":    "network|windows|linux",
      "ip_address":         "192.168.1.50",
      "port":               "9100",
      "path":               null,
      "capability_profile": "default|simple|SP2000|TEP-200M|P822D"
    }
  },
  "status":          "pending|printed|failed|cancelled",
  "attempt_count":   0,                      // cantidad de marks failed
  "content":         { ... },                // payload renderizable, ver schema por type
  "printed_at":      "ISO8601|null"
}
```

### Logica de enrutamiento sugerida (Android)

```
if (job.kitchen_station?.printer) {
    // Caso normal: usar la impresora de la estacion
    sendToPrinter(
      ip:       job.kitchen_station.printer.ip_address,
      port:     job.kitchen_station.printer.port,
      profile:  job.kitchen_station.printer.capability_profile
    )
} else {
    // Fallback: usar mapping local por printer_target
    sendToConfiguredPrinter(job.printer_target)
}
```

### Schema de `content` por tipo

#### `kitchen` y `adicion`

```jsonc
{
  "header":    "MERCADO ALDO",            // nombre del comercio
  "subheader": "COMANDA #1",               // o "ADICION #2" si es_adicion
  "items": [
    { "qty": 1, "name": "Producto", "notes": "" }
  ],
  "meta": {
    "mesa":       "5",
    "mozo":       "Juan Perez",
    "hora":       "14:30",
    "comanda_id": 142,
    "numero":     1,
    "es_adicion": false
  },
  "footer": "Mesa 5 - Mozo: Juan Perez - 14:30"
}
```

#### `receipt`

Reservado para v2. En v1 NO se generan jobs de tipo receipt automaticamente.

## <a name="errores"></a>6. Codigos de error

Todos los errores siguen el siguiente formato:

```json
{
  "error": {
    "code": "string_codigo",
    "message": "Descripcion human-readable"
  }
}
```

| HTTP | code                  | Descripcion |
|------|-----------------------|-------------|
| 401  | `missing_token`       | No se envio Authorization ni X-Api-Key |
| 401  | `invalid_token`       | Token inexistente o revocado |
| 401  | `insufficient_scope`  | Token no tiene scope `print_jobs` |
| 403  | `module_disabled`     | Modulo `print_jobs_api` no activo en el business |
| 404  | `not_found`           | Job no existe (o no pertenece al business del token) |
| 409  | `already_printed`     | El job ya fue marcado como impreso |
| 422  | `validation_failed`   | Parametros invalidos (incluye `fields` con detalle) |
| 429  | (Laravel default)     | Rate limit excedido |

## <a name="rate-limit"></a>7. Rate limit

- **120 requests/minuto por IP** (configurable por business via `rate_limit_per_minute`)
- Polling sano: 1 request cada 3-5 segundos por dispositivo (~12-20 req/min)
- Multiples dispositivos por comercio: comparten el rate limit

## <a name="recetas"></a>8. Recetas (curl)

### Listar pendientes

```bash
curl -H "Authorization: Bearer wmk_3a8b9c1d2e4f5067a8b9c0d1e2f3a4b5" \
     "https://wama.micdepos.com/api/v1/print-jobs?status=pending&type=kitchen"
```

### Marcar impreso

```bash
curl -X POST \
     -H "Authorization: Bearer wmk_3a8b9c1d2e4f5067a8b9c0d1e2f3a4b5" \
     -H "X-Device-Hint: tablet-cocina-01" \
     "https://wama.micdepos.com/api/v1/print-jobs/3a8b9c1d-e4f5-6789-0abc-def012345678/printed"
```

### Marcar fallo

```bash
curl -X POST \
     -H "Authorization: Bearer wmk_3a8b9c1d2e4f5067a8b9c0d1e2f3a4b5" \
     -H "Content-Type: application/json" \
     -d '{"reason":"Printer offline"}' \
     "https://wama.micdepos.com/api/v1/print-jobs/3a8b9c1d-e4f5-6789-0abc-def012345678/failed"
```

### Health

```bash
curl -H "Authorization: Bearer wmk_3a8b9c1d2e4f5067a8b9c0d1e2f3a4b5" \
     "https://wama.micdepos.com/api/v1/print-jobs/health"
```

## <a name="android"></a>9. Flujo Android tipico

```
+--------+                                          +--------+
| Mozo   |  Suspender/Cocina (POS web)              | WAMA   |
+--------+ ---------------------------------------> +--------+
                                                         |
                                       INSERT print_job  |
                                                         v
+--------+   GET /print-jobs?status=pending          +--------+
| Andrd  | <--------------------------------------- | WAMA   |
+--------+                                          +--------+
    |
    | Imprime ESC/POS en termica LAN
    v
+--------+   POST /print-jobs/{id}/printed          +--------+
| Andrd  | ---------------------------------------> | WAMA   |
+--------+                                          +--------+
                                                         |
                                            UPDATE       |
                                            printed_at   v
```

## <a name="seguridad"></a>10. Seguridad

- HTTPS obligatorio en produccion
- Tokens hasheados con SHA-256 (nunca se guarda en claro)
- Rate limit por business
- Multi-tenant scope estricto: imposible cruzar businesses con un token
- Revocacion inmediata desde la UI
- Logs de uso (last_used_at, last_ip) por token
- TTL de jobs pending: configurable por business (default 60 min)

## <a name="changelog"></a>11. Changelog

| Version | Fecha      | Cambios |
|---------|------------|---------|
| 1.0.0   | 2026-05-07 | Release inicial. Soporta tipos kitchen + adicion. |
