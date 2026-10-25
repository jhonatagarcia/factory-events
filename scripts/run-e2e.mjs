import { spawn, spawnSync } from 'node:child_process'
import { fileURLToPath } from 'node:url'
import { resolve, dirname } from 'node:path'
import { openSync, closeSync } from 'node:fs'

const root = resolve(dirname(fileURLToPath(import.meta.url)), '..')
const compose = ['compose', '-f', 'compose.e2e.yaml']
let backend
let log
function run(command, args, cwd = root) {
  const result = spawnSync(command, args, { cwd, stdio: 'inherit', env: process.env })
  if (result.status !== 0) throw new Error(`${command} falhou: ${result.status ?? result.error}`)
}
async function ready() {
  for (let i = 0; i < 90; i++) {
    if (backend.exitCode !== null) throw new Error('Backend E2E encerrou. Consulte e2e-backend.log.')
    try {
      const response = await fetch('http://127.0.0.1:18080/actuator/health', { signal: AbortSignal.timeout(1000) })
      if (response.ok) return
    } catch { /* Wait for application initialization. */ }
    await new Promise((done) => setTimeout(done, 1000))
  }
  throw new Error('Tempo de inicialização excedido. Consulte e2e-backend.log.')
}
async function cleanup() {
  if (backend && backend.exitCode === null) {
    backend.kill('SIGTERM')
    await new Promise((done) => {
      backend.once('exit', done)
      setTimeout(() => { backend.kill('SIGKILL'); done() }, 5000).unref()
    })
  }
  if (log !== undefined) closeSync(log)
  spawnSync('docker', [...compose, 'down'], { cwd: root, stdio: 'inherit' })
}
try {
  // Bind only to localhost; no production/development database is touched.
  run('docker', [...compose, 'up', '-d', '--wait'])
  run(process.platform === 'win32' ? 'mvnw.cmd' : './mvnw', ['-B', '-ntp', '-DskipTests', 'package'], resolve(root, 'factory-events-backend'))
  log = openSync(resolve(root, 'e2e-backend.log'), 'w')
  backend = spawn('java', ['-jar', 'factory-events-backend/target/factory-events-backend-0.0.1-SNAPSHOT.jar'], {
    cwd: root, env: { ...process.env, SERVER_PORT: '18080',
      DB_URL: 'jdbc:postgresql://127.0.0.1:55433/events_e2e', DB_USERNAME: 'e2e', DB_PASSWORD: 'e2e_local' },
    stdio: ['ignore', log, log],
  })
  await ready()
  run(process.platform === 'win32' ? 'npx.cmd' : 'npx', ['playwright', 'test'], resolve(root, 'factory-events-frontend'))
  run('docker', [...compose, 'exec', '-T', 'postgres', 'psql', '-U', 'e2e', '-d', 'events_e2e', '-v', 'ON_ERROR_STOP=1', '-c', `
    DO $$ BEGIN
      IF EXISTS (SELECT 1 FROM events e LEFT JOIN event_audits a USING(event_id)
          WHERE e.event_type = 'QUALITY' AND a.id IS NULL)
      OR EXISTS (SELECT 1 FROM event_audits a JOIN events e USING(event_id) WHERE e.event_type <> 'QUALITY')
      THEN RAISE EXCEPTION 'Auditoria inconsistente'; END IF;
    END $$;
  `])
} catch (error) {
  console.error(error.message)
  process.exitCode = 1
} finally {
  await cleanup()
}
