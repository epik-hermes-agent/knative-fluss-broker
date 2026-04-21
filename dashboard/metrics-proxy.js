/**
 * Metrics proxy for the data-flow-dashboard.
 * Aggregates live data from Flink, SQL Gateway, and LocalStack S3.
 * No external dependencies — uses Node.js built-in http module.
 *
 * Usage: node metrics-proxy.js
 * Endpoint: http://localhost:3001/metrics
 */

const http = require('http');
const https = require('https');

const PORT = 3001;

// ── Fetch helper ──────────────────────────────────────────
function fetch(url, options = {}) {
  return new Promise((resolve, reject) => {
    const mod = url.startsWith('https') ? https : http;
    const req = mod.request(url, {
      method: options.method || 'GET',
      headers: options.headers || {},
      timeout: 5000,
    }, (res) => {
      let data = '';
      res.on('data', chunk => data += chunk);
      res.on('end', () => {
        try { resolve({ status: res.statusCode, data: JSON.parse(data) }); }
        catch { resolve({ status: res.statusCode, data }); }
      });
    });
    req.on('error', err => reject(err));
    req.on('timeout', () => { req.destroy(); reject(new Error('timeout')); });
    if (options.body) req.write(options.body);
    req.end();
  });
}

// ── SQL Gateway helpers ──────────────────────────────────
let sessionHandle = null;

async function sqlGatewaySession() {
  if (sessionHandle) return sessionHandle;
  const res = await fetch('http://localhost:8083/v1/sessions', {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: '{}',
  });
  sessionHandle = res.data.sessionHandle;
  return sessionHandle;
}

async function executeSql(statement) {
  const session = await sqlGatewaySession();
  const stmtRes = await fetch(`http://localhost:8083/v1/sessions/${session}/statements`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ statement }),
  });
  if (stmtRes.status !== 200) return null;
  const opHandle = stmtRes.data.operationHandle;
  // Fetch result
  const resultRes = await fetch(`http://localhost:8083/v1/sessions/${session}/operations/${opHandle}/result/0`);
  if (resultRes.status !== 200) return null;
  return resultRes.data;
}

// ── Data collectors ──────────────────────────────────────

async function getFlinkJobs() {
  try {
    const res = await fetch('http://localhost:8081/jobs/overview');
    if (res.status !== 200) return { jobs: [], running: 0, failed: 0 };
    const jobs = res.data.jobs || [];
    return {
      jobs: jobs.map(j => ({
        id: j.jid,
        name: j.name,
        state: j.state,
        duration: j.duration,
        tasksRunning: j.tasks.running,
        tasksTotal: j.tasks.total,
        tasksFailed: j.tasks.failed,
      })),
      running: jobs.filter(j => j.state === 'RUNNING').length,
      failed: jobs.filter(j => j.tasks.failed > 0).length,
    };
  } catch (e) {
    return { jobs: [], running: 0, failed: 0, error: e.message };
  }
}

async function getFlinkTaskmanagers() {
  try {
    const res = await fetch('http://localhost:8081/taskmanagers');
    if (res.status !== 200) return { count: 0 };
    const tms = res.data.taskmanagers || [];
    return {
      count: tms.length,
      totalSlots: tms.reduce((s, tm) => s + (tm.slotsNumber || 0), 0),
      freeSlots: tms.reduce((s, tm) => s + (tm.freeSlots || 0), 0),
    };
  } catch (e) {
    return { count: 0, error: e.message };
  }
}

async function getFlussTables() {
  try {
    // Create catalog and list databases
    await executeSql("CREATE CATALOG IF NOT EXISTS fluss_cat WITH ('type'='fluss','bootstrap.servers'='fluss-coordinator:9125')");
    const dbResult = await executeSql("SHOW DATABASES FROM fluss_cat");
    const databases = [];
    if (dbResult?.results?.data) {
      for (const row of dbResult.results.data) {
        databases.push(row.fields[0]);
      }
    }

    // For each non-default database, list tables and count rows
    const tables = [];
    for (const db of databases) {
      if (db === 'default_database') continue;
      try {
        const tblResult = await executeSql(`SHOW TABLES FROM fluss_cat.${db}`);
        if (tblResult?.results?.data) {
          for (const row of tblResult.results.data) {
            const tableName = row.fields[0];
            let rowCount = 0;
            try {
              const countResult = await executeSql(`SELECT COUNT(*) AS cnt FROM fluss_cat.${db}.\`${tableName}\``);
              if (countResult?.results?.data?.[0]?.fields?.[0] != null) {
                rowCount = countResult.results.data[0].fields[0];
              }
            } catch {}
            tables.push({ database: db, table: tableName, rows: rowCount });
          }
        }
      } catch {}
    }

    return { databases: databases.length, tables };
  } catch (e) {
    return { databases: 0, tables: [], error: e.message };
  }
}

async function getS3Objects() {
  try {
    // List buckets
    const bucketsRes = await fetch('http://localhost:4566/');
    // List objects in all buckets (use ListBuckets then ListObjects)
    const bucketsXml = bucketsRes.data;
    const bucketNames = [];
    const bucketRegex = /<Name>([^<]+)<\/Name>/g;
    let match;
    while ((match = bucketRegex.exec(bucketsXml)) !== null) {
      bucketNames.push(match[1]);
    }

    let totalObjects = 0;
    let totalSize = 0;
    const buckets = [];

    for (const bucket of bucketNames) {
      try {
        const objRes = await fetch(`http://localhost:4566/${bucket}?list-type=2`);
        const objXml = objRes.data;
        const keyCount = (objXml.match(/<Key>/g) || []).length;
        const sizeMatches = [...objXml.matchAll(/<Size>(\d+)<\/Size>/g)];
        const bucketSize = sizeMatches.reduce((s, m) => s + parseInt(m[1]), 0);
        totalObjects += keyCount;
        totalSize += bucketSize;
        buckets.push({ name: bucket, objects: keyCount, size: bucketSize });
      } catch {}
    }

    return { buckets, totalObjects, totalSize, totalSizeFormatted: formatBytes(totalSize) };
  } catch (e) {
    return { buckets: [], totalObjects: 0, totalSize: 0, error: e.message };
  }
}

function formatBytes(bytes) {
  if (bytes < 1024) return bytes + ' B';
  if (bytes < 1024 * 1024) return (bytes / 1024).toFixed(1) + ' KB';
  if (bytes < 1024 * 1024 * 1024) return (bytes / (1024 * 1024)).toFixed(1) + ' MB';
  return (bytes / (1024 * 1024 * 1024)).toFixed(2) + ' GB';
}

// ── Previous snapshot for delta calculation ──────────────
let prevSnapshot = null;
let prevTimestamp = null;

function calculateThroughput(current, previous, elapsedMs) {
  if (!previous || elapsedMs === 0) return null;
  const rates = {};
  for (const table of current.tables || []) {
    const prevTable = (previous.tables || []).find(t => t.database === table.database && t.table === table.table);
    if (prevTable) {
      const delta = table.rows - prevTable.rows;
      rates[`${table.database}.${table.table}`] = {
        delta,
        ratePerSec: (delta / (elapsedMs / 1000)).toFixed(1),
        currentRows: table.rows,
      };
    }
  }
  return rates;
}

// ── HTTP server ──────────────────────────────────────────
const server = http.createServer(async (req, res) => {
  // CORS
  res.setHeader('Access-Control-Allow-Origin', '*');
  res.setHeader('Access-Control-Allow-Methods', 'GET');
  res.setHeader('Content-Type', 'application/json');

  if (req.url === '/metrics') {
    try {
      const timestamp = Date.now();
      const [flinkJobs, taskmanagers, flussTables, s3] = await Promise.all([
        getFlinkJobs(),
        getFlinkTaskmanagers(),
        getFlussTables(),
        getS3Objects(),
      ]);

      // Calculate throughput from delta
      const elapsedMs = prevTimestamp ? timestamp - prevTimestamp : 0;
      const throughput = calculateThroughput(flussTables, prevSnapshot, elapsedMs);
      prevSnapshot = flussTables;
      prevTimestamp = timestamp;

      const totalRows = (flussTables.tables || []).reduce((s, t) => s + t.rows, 0);

      const metrics = {
        timestamp,
        flink: {
          ...flinkJobs,
          taskmanagers,
        },
        fluss: {
          totalDatabases: flussTables.databases,
          totalTables: (flussTables.tables || []).length,
          totalRows,
          tables: flussTables.tables,
          throughput,
        },
        iceberg: {
          ...s3,
        },
        // Derived metrics for dashboard
        dashboard: {
          sourceRate: throughput
            ? Object.values(throughput).reduce((s, v) => s + parseFloat(v.ratePerSec || 0), 0).toFixed(0)
            : '—',
          tieringJobs: flinkJobs.jobs.filter(j => j.name.includes('Tiering')),
          insertJobs: flinkJobs.jobs.filter(j => j.name.includes('insert')),
          flussHealthy: flussTables.databases > 0,
          tieringHealthy: flinkJobs.running > 0,
        },
      };

      res.writeHead(200);
      res.end(JSON.stringify(metrics, null, 2));
    } catch (e) {
      res.writeHead(500);
      res.end(JSON.stringify({ error: e.message }));
    }
  } else if (req.url === '/health') {
    res.writeHead(200);
    res.end(JSON.stringify({ status: 'ok' }));
  } else {
    res.writeHead(404);
    res.end(JSON.stringify({ error: 'not found' }));
  }
});

server.listen(PORT, () => {
  console.log(`Metrics proxy running at http://localhost:${PORT}/metrics`);
});
