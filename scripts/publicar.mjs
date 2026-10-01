#!/usr/bin/env node
// Publica la versión actual del agente en https://descargas.wama.com.py/print-agent
//
//   node scripts/publicar.mjs --notas "Primera novedad" --notas "Segunda novedad"
//   node scripts/publicar.mjs --dry-run            (compila y muestra qué haría, sin publicar)
//
// Publica la versión que YA está en app/build.gradle.kts (versionName): el número se sube
// mientras se desarrolla, y este script se corre recién cuando esa versión está probada.
//
// Pasos: verifica repo limpio → compila el APK firmado → crea el release en
// avaldezdev/wama-print-agent-releases (APK con versión + PrintAgent.apk fijo) → etiqueta
// el commit fuente. La página de descargas y el aviso dentro de la app leen ese release,
// así que no hay nada más que actualizar.

import { execFileSync } from 'node:child_process'
import { copyFileSync, existsSync, mkdirSync, readFileSync, writeFileSync } from 'node:fs'
import { dirname, join } from 'node:path'
import { fileURLToPath } from 'node:url'

const RELEASES_REPO = 'avaldezdev/wama-print-agent-releases'
const PAGE = 'https://descargas.wama.com.py/print-agent'

const root = join(dirname(fileURLToPath(import.meta.url)), '..')
const args = process.argv.slice(2)
const dryRun = args.includes('--dry-run')
const notas = args.flatMap((a, i) => (a === '--notas' && args[i + 1] ? [args[i + 1]] : []))

const run = (cmd, cmdArgs, opts = {}) =>
  execFileSync(cmd, cmdArgs, { cwd: root, encoding: 'utf8', stdio: ['ignore', 'pipe', 'pipe'], ...opts }).trim()
const fail = (msg) => { console.error(`\n✗ ${msg}\n`); process.exit(1) }
const step = (msg) => console.log(`→ ${msg}`)

// 1) Versión a publicar
const gradle = readFileSync(join(root, 'app/build.gradle.kts'), 'utf8')
const version = gradle.match(/versionName\s*=\s*"([^"]+)"/)?.[1]
if (!version) fail('No encontré versionName en app/build.gradle.kts')
const tag = `v${version}`
step(`Versión a publicar: ${tag}`)

// 2) Controles
if (!existsSync(join(root, 'keystore.properties'))) fail('Falta keystore.properties: el APK saldría sin firmar.')
if (run('git', ['status', '--porcelain'])) fail('Hay cambios sin commitear. Commiteá antes de publicar.')
const yaExiste = (() => {
  try { run('gh', ['release', 'view', tag, '--repo', RELEASES_REPO]); return true } catch { return false }
})()
if (yaExiste) fail(`${tag} ya está publicada. Subí versionName/versionCode en app/build.gradle.kts.`)
if (run('git', ['tag', '-l', tag])) fail(`La etiqueta ${tag} ya existe en este repo.`)
if (!notas.length && !dryRun) fail('Agregá al menos una novedad: --notas "Qué cambió"')

// 3) Compilar
step('Compilando APK firmado…')
// Ruta absoluta: cmd.exe puede no buscar ejecutables en el directorio actual.
const gradlew = join(root, process.platform === 'win32' ? 'gradlew.bat' : 'gradlew')
execFileSync(`"${gradlew}"`, ['assembleRelease', '--console=plain', '-q'], { cwd: root, stdio: 'inherit', shell: true })

const outDir = join(root, 'app/build/outputs/apk/release')
const meta = JSON.parse(readFileSync(join(outDir, 'output-metadata.json'), 'utf8'))
const built = meta.elements?.[0]?.versionName
if (built !== version) fail(`El APK compilado dice ${built}, se esperaba ${version}.`)

const pubDir = join(root, 'app/build/publicar')
mkdirSync(pubDir, { recursive: true })
const versioned = join(pubDir, `PrintAgent-${tag}.apk`)
const fixed = join(pubDir, 'PrintAgent.apk')
copyFileSync(join(outDir, 'app-release.apk'), versioned)
copyFileSync(join(outDir, 'app-release.apk'), fixed)

const body = notas.map((n) => `- ${n}`).join('\n')
const notesFile = join(pubDir, 'notas.md')
writeFileSync(notesFile, body + '\n')

if (dryRun) {
  console.log(`\n[dry-run] Se publicaría ${tag} en ${RELEASES_REPO} con:\n${body || '(sin notas)'}\n`)
  process.exit(0)
}

// 4) Publicar
step(`Creando release ${tag} en ${RELEASES_REPO}…`)
run('gh', ['release', 'create', tag, versioned, fixed,
  '--repo', RELEASES_REPO, '--title', `Print Agent ${version}`, '--notes-file', notesFile, '--latest'])

// 5) Etiquetar el código fuente que generó este APK
step('Etiquetando el commit fuente…')
run('git', ['tag', '-a', tag, '-m', `Print Agent ${version}`])
run('git', ['push', 'origin', tag])

console.log(`\n✓ Publicada ${tag}\n  Página:  ${PAGE}\n  Release: https://github.com/${RELEASES_REPO}/releases/tag/${tag}\n`)
